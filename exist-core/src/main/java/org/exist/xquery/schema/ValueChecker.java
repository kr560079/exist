/*
 * eXist-db Open Source Native XML Database
 * Copyright (C) 2001 The eXist-db Authors
 *
 * info@exist-db.org
 * http://www.exist-db.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.exist.xquery.schema;

import org.apache.xerces.impl.dv.InvalidDatatypeValueException;
import org.apache.xerces.impl.dv.ValidatedInfo;
import org.apache.xerces.impl.Constants;
import org.apache.xerces.impl.dv.XSSimpleType;
import org.apache.xerces.impl.dv.xs.TypeValidatorHelper;
import org.apache.xerces.impl.validation.ValidationState;
import org.apache.xerces.util.NamespaceSupport;
import org.apache.xerces.xs.XSComplexTypeDefinition;
import org.apache.xerces.xs.XSSimpleTypeDefinition;
import org.apache.xerces.xs.XSTypeDefinition;
import org.exist.xquery.XPathException;
import org.exist.xquery.value.UserDefinedType;
import org.exist.xquery.value.UserTypeRegistry;
import org.exist.xquery.value.Variety;

import javax.annotation.Nullable;
import javax.xml.XMLConstants;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Checks lexical values against the simple types of a compiled schema.
 *
 * <p>Two tiers: a value of a built-in type, or of an atomic user type without facets beyond those of its
 * built-in ancestor, is checked by constructing its typed value (tier 1); a value of any other user type
 * (pattern, enumeration, range or other facets, lists and unions) is validated by Xerces against the
 * type definition (tier 2). Results are cached per instance in an LRU cache of
 * {@value #CACHE_SIZE} {@code (typeCode, lexical)} entries.</p>
 *
 * <p>Limitation: tier 2 validates with an empty namespace context, so prefixed values of user types
 * derived from {@code xs:QName} or {@code xs:NOTATION} are rejected.</p>
 */
public final class ValueChecker {

    static final int CACHE_SIZE = 4096;

    private record Key(int typeCode, String lexical) {
    }

    private final CompiledSchema schema;
    private final Map<Key, Boolean> cache = new LinkedHashMap<>(CACHE_SIZE * 4 / 3 + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Key, Boolean> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    /**
     * @param schema the schema whose types are checked
     */
    public ValueChecker(final CompiledSchema schema) {
        this.schema = schema;
    }

    /**
     * Check a lexical value against a simple type, or a complex type with simple content.
     *
     * @param typeCode the {@link org.exist.xquery.value.Type} code of a built-in type or of a type of the schema
     * @param lexical  the lexical value
     * @return true if the value is valid for the type
     * @throws IllegalArgumentException if the type is a user type of another schema
     */
    public boolean isValid(final int typeCode, final String lexical) {
        final Key key = new Key(typeCode, lexical);
        synchronized (cache) {
            final Boolean cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        final boolean valid = check(typeCode, lexical);
        synchronized (cache) {
            cache.put(key, valid);
        }
        return valid;
    }

    private boolean check(final int typeCode, final String lexical) {
        final UserDefinedType userType = UserTypeRegistry.get(typeCode);
        if (userType == null) {
            return parses(typeCode, lexical);
        }
        if (!userType.schema().equals(schema.key())) {
            throw new IllegalArgumentException("Type " + typeCode + " is not defined by schema " + schema.key());
        }
        final XSSimpleTypeDefinition simple = simpleTypeOf(userType.definition());
        if (simple == null) {
            // complex type without simple content: no lexical value to check
            return userType.variety() == Variety.COMPLEX_MIXED;
        }
        if (userType.variety() == Variety.ATOMIC && !hasOwnFacets(simple)) {
            return parses(typeCode, lexical);
        }
        return validate(simple, lexical);
    }

    private static boolean parses(final int typeCode, final String lexical) {
        try {
            TypedValues.fromLexical(lexical, typeCode, null);
            return true;
        } catch (final XPathException e) {
            return false;
        }
    }

    /**
     * Test whether an atomic type adds facets to those of its built-in ancestor.
     */
    private static boolean hasOwnFacets(final XSSimpleTypeDefinition type) {
        XSTypeDefinition builtIn = type;
        while (builtIn instanceof XSSimpleTypeDefinition && !isBuiltIn(builtIn)) {
            builtIn = builtIn.getBaseType();
        }
        if (!(builtIn instanceof XSSimpleTypeDefinition builtInSimple)) {
            return true;
        }
        return type.getDefinedFacets() != builtInSimple.getDefinedFacets()
                || type.getMultiValueFacets().getLength() != builtInSimple.getMultiValueFacets().getLength()
                || type.getFacets().getLength() != builtInSimple.getFacets().getLength();
    }

    private static boolean isBuiltIn(final XSTypeDefinition def) {
        return XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(def.getNamespace()) && !def.getAnonymous();
    }

    private static @Nullable XSSimpleTypeDefinition simpleTypeOf(final XSTypeDefinition def) {
        if (def instanceof XSComplexTypeDefinition complex) {
            return complex.getContentType() == XSComplexTypeDefinition.CONTENTTYPE_SIMPLE
                    ? complex.getSimpleType() : null;
        }
        return (XSSimpleTypeDefinition) def;
    }

    /**
     * Validate a lexical value against a type definition with Xerces, checking all facets.
     *
     * @param definition a simple type, or a complex type with simple content
     * @param lexical    the lexical value
     * @return true if the value is valid
     */
    static boolean validate(final XSTypeDefinition definition, final String lexical) {
        final XSSimpleTypeDefinition simple = simpleTypeOf(definition);
        if (!(simple instanceof XSSimpleType xercesType)) {
            return false;
        }
        final ValidationState state = new ValidationState();
        state.setExtraChecking(false);
        state.setFacetChecking(true);
        state.setTypeValidatorHelper(TypeValidatorHelper.getInstance(Constants.SCHEMA_VERSION_1_0));
        // an empty namespace context: only unprefixed QName/NOTATION values validate
        state.setNamespaceSupport(new NamespaceSupport());
        try {
            xercesType.validate(lexical, state, new ValidatedInfo());
            return true;
        } catch (final InvalidDatatypeValueException e) {
            return false;
        }
    }
}
