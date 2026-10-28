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

import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.NodeValue;
import org.exist.xquery.value.Type;
import org.w3c.dom.Node;

/**
 * Looks up and tests the type annotation of nodes.
 *
 * <p>No collection has a schema yet, so every node is unannotated: elements are
 * {@code xs:untyped} and attributes {@code xs:untypedAtomic}.</p>
 */
public final class TypeAnnotations {

    private TypeAnnotations() {
    }

    /**
     * Get the type annotation of a node.
     *
     * @param ctx  the context of the query
     * @param node the node
     * @return the annotation of the node
     * @throws XPathException if the annotation cannot be determined
     */
    public static TypeAnnotation annotationOf(final XQueryContext ctx, final NodeValue node) throws XPathException {
        int kind = node.getType();
        if (kind == Type.NODE) {
            kind = Type.fromDomNodeType(node.getNode().getNodeType());
        }
        return unannotated(kind);
    }

    /**
     * Get the type annotation of a DOM node.
     *
     * @param ctx  the context of the query
     * @param node the node
     * @return the annotation of the node
     * @throws XPathException if the annotation cannot be determined
     */
    public static TypeAnnotation annotationOf(final XQueryContext ctx, final Node node) throws XPathException {
        if (node instanceof NodeValue nodeValue) {
            return annotationOf(ctx, nodeValue);
        }
        return unannotated(Type.fromDomNodeType(node.getNodeType()));
    }

    /**
     * Test whether the annotation of a node is a subtype of the given type.
     *
     * @param ctx      the context of the query
     * @param node     the node
     * @param typeCode the {@link Type} code the annotation must be a subtype of
     * @param nillable true if a nilled node may match
     * @return true if the node matches
     * @throws XPathException if the annotation cannot be determined
     */
    public static boolean matches(final XQueryContext ctx, final NodeValue node, final int typeCode,
            final boolean nillable) throws XPathException {
        return matches(annotationOf(ctx, node), typeCode, nillable);
    }

    /**
     * Test whether the annotation of a DOM node is a subtype of the given type.
     *
     * @param ctx      the context of the query
     * @param node     the node
     * @param typeCode the {@link Type} code the annotation must be a subtype of
     * @param nillable true if a nilled node may match
     * @return true if the node matches
     * @throws XPathException if the annotation cannot be determined
     */
    public static boolean matches(final XQueryContext ctx, final Node node, final int typeCode,
            final boolean nillable) throws XPathException {
        return matches(annotationOf(ctx, node), typeCode, nillable);
    }

    private static boolean matches(final TypeAnnotation annotation, final int typeCode, final boolean nillable) {
        if (annotation.nilled() && !nillable) {
            return false;
        }
        return Type.subTypeOf(annotation.typeCode(), typeCode);
    }

    private static TypeAnnotation unannotated(final int kind) {
        return new TypeAnnotation(kind == Type.ELEMENT ? Type.UNTYPED : Type.UNTYPED_ATOMIC, false);
    }
}
