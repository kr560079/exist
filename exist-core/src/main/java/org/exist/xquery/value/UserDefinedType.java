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
package org.exist.xquery.value;

import org.apache.xerces.xs.XSTypeDefinition;
import org.exist.dom.QName;
import org.exist.xquery.schema.SchemaKey;

import javax.annotation.Nullable;

/**
 * A type defined by an XML Schema, registered in {@link UserTypeRegistry}.
 *
 * @param code the type code, {@code >= UserTypeRegistry.USER_TYPE_BASE}
 * @param name the type name, or null for an anonymous type
 * @param anonymousId identifier for anonymous types
 * @param baseCode the code of the base type
 * @param primitiveCode the code of the primitive built-in type (simple types)
 * @param variety the variety of the type
 * @param schema the schema which defines the type
 * @param definition the Xerces type definition
 * @param memberCodes union members, {@code {itemType}} for lists,
 *                    or {@code {simpleContentType}} for complex types with simple content
 */
public record UserDefinedType(int code, @Nullable QName name, String anonymousId, int baseCode, int primitiveCode,
        Variety variety, SchemaKey schema, XSTypeDefinition definition, int[] memberCodes) {
}
