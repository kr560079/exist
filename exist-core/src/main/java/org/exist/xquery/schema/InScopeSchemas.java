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

import org.apache.xerces.xs.XSAttributeDeclaration;
import org.apache.xerces.xs.XSElementDeclaration;
import org.apache.xerces.xs.XSTypeDefinition;
import org.exist.Namespaces;
import org.exist.dom.QName;
import org.exist.xquery.ErrorCodes;
import org.exist.xquery.XPathException;
import org.exist.xquery.value.Type;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The in-scope schema definitions of a module (XQuery 3.1 §2.1.1): the schemas brought in by its
 * {@code import schema} declarations, by target namespace.
 *
 * <p>Instances are immutable; {@link #with(String, CompiledSchema)} returns an extended copy. A module without
 * {@code import schema} has {@link #EMPTY}, for which type-name resolution is exactly the built-in lookup.</p>
 */
public final class InScopeSchemas {

    /** No imported schema. */
    public static final InScopeSchemas EMPTY = new InScopeSchemas(Map.of());

    private final Map<String, CompiledSchema> byNamespace;

    private InScopeSchemas(final Map<String, CompiledSchema> byNamespace) {
        this.byNamespace = byNamespace;
    }

    /**
     * @param namespaceUri the target namespace of an imported schema, {@code ""} for no namespace
     * @param schema the schema
     * @return a copy of these definitions with the schema imported for the namespace
     */
    public InScopeSchemas with(final String namespaceUri, final CompiledSchema schema) {
        final Map<String, CompiledSchema> extended = new HashMap<>(byNamespace);
        extended.put(namespaceUri, schema);
        return new InScopeSchemas(Map.copyOf(extended));
    }

    /** @return true if no schema is imported */
    public boolean isEmpty() {
        return byNamespace.isEmpty();
    }

    /**
     * @param namespaceUri a target namespace, {@code ""} for no namespace
     * @return the schema imported for the namespace
     */
    public Optional<CompiledSchema> schemaFor(final String namespaceUri) {
        return Optional.ofNullable(byNamespace.get(namespaceUri));
    }

    /**
     * Resolve a type name: the built-in types, then the types of the schema imported for the name's namespace.
     *
     * @param name the type name
     * @return the {@link Type} code of the type
     * @throws XPathException {@code XPST0008} if the name is in an imported namespace but the schema defines no such
     *     type; otherwise, for a name in no imported namespace, the error of the built-in lookup
     */
    public int resolveTypeCode(final QName name) throws XPathException {
        final CompiledSchema schema = imported(name);
        if (schema == null) {
            return Type.getType(name);
        }
        final XSTypeDefinition def = schema.namedType(key(name)).orElseThrow(() -> undeclared("type", name));
        return schema.typeCode(def);
    }

    /**
     * @param name an element name
     * @return the global element declaration of the name in the schema imported for its namespace
     * @throws XPathException {@code XPST0008} if there is no such declaration
     */
    public XSElementDeclaration resolveElementDeclaration(final QName name) throws XPathException {
        final CompiledSchema schema = imported(name);
        if (schema == null) {
            throw undeclared("element declaration", name);
        }
        return schema.globalElement(key(name)).orElseThrow(() -> undeclared("element declaration", name));
    }

    /**
     * @param name an attribute name
     * @return the global attribute declaration of the name in the schema imported for its namespace
     * @throws XPathException {@code XPST0008} if there is no such declaration
     */
    public XSAttributeDeclaration resolveAttributeDeclaration(final QName name) throws XPathException {
        final CompiledSchema schema = imported(name);
        if (schema == null) {
            throw undeclared("attribute declaration", name);
        }
        return schema.globalAttribute(key(name)).orElseThrow(() -> undeclared("attribute declaration", name));
    }

    /** @return the schema imported for the namespace of the name; null for the XML Schema namespace (built-ins) */
    private @Nullable CompiledSchema imported(final QName name) {
        if (byNamespace.isEmpty()) {
            return null;
        }
        final String ns = namespaceOf(name);
        if (Namespaces.SCHEMA_NS.equals(ns)) {
            return null;
        }
        return byNamespace.get(ns);
    }

    private static String namespaceOf(final QName name) {
        final String ns = name.getNamespaceURI();
        return ns == null ? "" : ns;
    }

    /** the compiled schema's maps are keyed by plain element QNames */
    private static QName key(final QName name) {
        return new QName(name.getLocalPart(), namespaceOf(name));
    }

    private static XPathException undeclared(final String what, final QName name) {
        return new XPathException((org.exist.xquery.Expression) null, ErrorCodes.XPST0008,
                "No " + what + " " + name.toURIQualifiedName() + " in the in-scope schema definitions");
    }
}
