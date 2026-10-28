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
package org.exist.indexing.schematype;

import org.exist.dom.QName;
import org.exist.xquery.schema.CompiledSchema;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.UserDefinedType;
import org.exist.xquery.value.UserTypeRegistry;

import javax.annotation.Nullable;
import javax.xml.XMLConstants;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The local type id table of a collection resolved against its compiled schema: maps the local ids persisted
 * in the index to the runtime type codes of this JVM, and back.
 *
 * <p>Immutable; a new table is built when ids are allocated.</p>
 */
final class LocalTypeTable {

    /** the prefix of anonymous type ids derived from an identity hash, which are not stable across JVMs */
    static final String UNSTABLE_ANONYMOUS_PREFIX = "#anon:#";

    private static final String XS_NS = XMLConstants.W3C_XML_SCHEMA_NS_URI;

    private final CollectionMeta meta;
    private final CompiledSchema schema;
    private final Map<Short, Integer> localToCode = new HashMap<>();
    private final Map<Integer, Short> codeToLocal = new HashMap<>();

    LocalTypeTable(final CollectionMeta meta, final CompiledSchema schema) {
        this.meta = meta;
        this.schema = schema;
        for (final Map.Entry<String, Short> entry : meta.localTypeIds().entrySet()) {
            final int code = resolve(entry.getKey());
            if (code >= 0) {
                localToCode.put(entry.getValue(), code);
                codeToLocal.put(code, entry.getValue());
            }
        }
    }

    CollectionMeta meta() {
        return meta;
    }

    CompiledSchema schema() {
        return schema;
    }

    /**
     * @return the runtime type code of a local type id, or -1 if the type is not defined by the current schema
     */
    int codeOf(final short localTypeId) {
        final Integer code = localToCode.get(localTypeId);
        return code == null ? -1 : code;
    }

    /**
     * @return the local type id of a runtime type code, or null if none is allocated
     */
    @Nullable Short localOf(final int typeCode) {
        return codeToLocal.get(typeCode);
    }

    /**
     * @return every allocated local type id, including those the current schema no longer defines
     */
    Collection<Short> allLocalIds() {
        return meta.localTypeIds().values();
    }

    /**
     * @return the local type ids of {@code typeCode} and, if {@code includeDerived}, of the types derived from it
     */
    short[] matching(final int typeCode, final boolean includeDerived) {
        final short[] buf = new short[localToCode.size()];
        int n = 0;
        for (final Map.Entry<Short, Integer> entry : localToCode.entrySet()) {
            final int code = entry.getValue();
            if (code == typeCode || (includeDerived && subTypeOf(code, typeCode))) {
                buf[n++] = entry.getKey();
            }
        }
        final short[] result = new short[n];
        System.arraycopy(buf, 0, result, 0, n);
        return result;
    }

    private static boolean subTypeOf(final int code, final int typeCode) {
        try {
            return Type.subTypeOf(code, typeCode);
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    private int resolve(final String typeId) {
        final Optional<Integer> code = schema.typeById(typeId).map(schema::typeCode);
        if (code.isPresent()) {
            return code.get();
        }
        if (typeId.startsWith("{" + XS_NS + "}")) {
            try {
                return Type.getType("xs:" + typeId.substring(XS_NS.length() + 2));
            } catch (final org.exist.xquery.XPathException e) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * The stable id of a type: {@code {ns}local} for named types, built-in ones included, or
     * {@code #anon:<declaration path>} for anonymous types.
     *
     * @return the id, or null if the type has none (unknown code)
     */
    static @Nullable String typeIdOf(final int typeCode) {
        final UserDefinedType userType = UserTypeRegistry.get(typeCode);
        if (userType != null) {
            final QName name = userType.name();
            return name == null ? userType.anonymousId() : clark(name.getNamespaceURI(), name.getLocalPart());
        }
        final String name = Type.getTypeName(typeCode);
        if (name == null || !name.startsWith("xs:")) {
            return null;
        }
        return clark(XS_NS, name.substring(3));
    }

    /**
     * @return the name of a named type, or null for an anonymous or unknown type
     */
    static @Nullable QName typeNameOf(final int typeCode) {
        final UserDefinedType userType = UserTypeRegistry.get(typeCode);
        if (userType != null) {
            return userType.name();
        }
        final String name = Type.getTypeName(typeCode);
        if (name == null || !name.startsWith("xs:")) {
            return null;
        }
        return new QName(name.substring(3), XS_NS);
    }

    static boolean isStable(final String typeId) {
        return !typeId.startsWith(UNSTABLE_ANONYMOUS_PREFIX);
    }

    private static String clark(@Nullable final String ns, final String local) {
        return "{" + (ns == null ? "" : ns) + "}" + local;
    }
}
