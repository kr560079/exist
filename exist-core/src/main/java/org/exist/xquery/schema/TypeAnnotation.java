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

import org.exist.xquery.value.Type;

/**
 * The type annotation of a node: the type code of its type, whether it is nilled, and whether a schema assessed it.
 *
 * @param typeCode the {@link Type} code of the annotated type, built-in or user-defined
 * @param nilled   true if the node is nilled ({@code xsi:nil="true"} on an element whose declaration is nillable)
 * @param assessed true if the annotation was assigned by schema assessment (stored in the schema type index); false
 *                 for the default annotation of a node no schema assessed: an in-memory node, a node of a collection
 *                 without a schema, or a stored node no type could be assigned to. Only assessed nodes are instances
 *                 of schema declarations ({@code schema-element(N)}, {@code schema-attribute(N)}).
 */
public record TypeAnnotation(int typeCode, boolean nilled, boolean assessed) {

    /** An element of a collection without a schema. */
    public static final TypeAnnotation UNTYPED = new TypeAnnotation(Type.UNTYPED, false, false);

    /** An element of a collection with a schema which carries no stored annotation. */
    public static final TypeAnnotation ANY_TYPE = new TypeAnnotation(Type.ANY_TYPE, false, false);

    /** An attribute which carries no stored annotation. */
    public static final TypeAnnotation UNTYPED_ATOMIC = new TypeAnnotation(Type.UNTYPED_ATOMIC, false, false);

    /**
     * An annotation assigned by schema assessment.
     *
     * @param typeCode the {@link Type} code of the annotated type
     * @param nilled   true if the node is nilled
     * @return the annotation
     */
    public static TypeAnnotation assessed(final int typeCode, final boolean nilled) {
        return new TypeAnnotation(typeCode, nilled, true);
    }
}
