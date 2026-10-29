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

import org.exist.EXistException;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.dom.persistent.NodeHandle;
import org.exist.dom.persistent.NodeProxy;
import org.exist.storage.DBBroker;
import org.exist.xquery.ErrorCodes;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.NodeValue;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.UserDefinedType;
import org.exist.xquery.value.UserTypeRegistry;
import org.w3c.dom.Node;

import javax.annotation.Nullable;

/**
 * Looks up and tests the type annotation of nodes.
 *
 * <p>Stored nodes of collections configured with the schema type index carry the annotation stored in the index
 * (see {@link TypeAnnotationProvider}); every other node is unannotated: elements are {@code xs:untyped} and
 * attributes {@code xs:untypedAtomic}.</p>
 *
 * <p>Testing a stored node against a user-defined type checks type identity (spec 7.6): if the node's document was
 * annotated with a different schema for the type's namespace than the one the query imported, the test raises
 * {@code EXSA0001} instead of silently not matching.</p>
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
        if (node instanceof NodeProxy proxy) {
            final TypeAnnotation annotation = proxy.getTypeAnnotation();
            if (annotation != null) {
                return annotation;
            }
        }
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
        if (node instanceof NodeHandle stored) {
            // a stored DOM node, e.g. visited by a descendant step over a virtual node set
            return annotationOf(ctx, new NodeProxy(stored));
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
    public static boolean matches(@Nullable final XQueryContext ctx, final NodeValue node, final int typeCode,
            final boolean nillable) throws XPathException {
        final TypeAnnotation annotation = annotationOf(ctx, node);
        if (typeCode >= UserTypeRegistry.USER_TYPE_BASE && node instanceof NodeProxy proxy) {
            checkSameSchema(ctx, proxy, annotation, typeCode);
        }
        return matches(annotation, typeCode, nillable);
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
    public static boolean matches(@Nullable final XQueryContext ctx, final Node node, final int typeCode,
            final boolean nillable) throws XPathException {
        if (node instanceof NodeValue nodeValue) {
            return matches(ctx, nodeValue, typeCode, nillable);
        }
        if (node instanceof NodeHandle stored) {
            return matches(ctx, new NodeProxy(stored), typeCode, nillable);
        }
        return matches(annotationOf(ctx, node), typeCode, nillable);
    }

    /**
     * Raise {@code EXSA0001} if the stored node was annotated with another schema for the namespace of the
     * user-defined type than the one the type comes from.
     */
    private static void checkSameSchema(@Nullable final XQueryContext ctx, final NodeProxy node,
            final TypeAnnotation annotation, final int typeCode) throws XPathException {
        final UserDefinedType requested = UserTypeRegistry.get(typeCode);
        if (requested == null || requested.name() == null) {
            return;
        }
        final UserDefinedType annotated = UserTypeRegistry.get(annotation.typeCode());
        final SchemaKey nodeSchema;
        if (annotated != null) {
            nodeSchema = annotated.schema();
        } else if (annotation == TypeAnnotation.UNTYPED) {
            // no schema: the collection of the node is not configured
            return;
        } else {
            nodeSchema = schemaOf(ctx, node.getOwnerDocument());
        }
        if (nodeSchema == null || nodeSchema.equals(requested.schema())) {
            return;
        }
        final String namespace = requested.name().getNamespaceURI();
        final DocumentImpl doc = node.getOwnerDocument();
        // the namespaces of every compiled schema are kept, whether or not it is still cached
        final java.util.Set<String> covered = UserTypeRegistry.namespacesOf(nodeSchema);
        if (covered != null && covered.contains(namespace == null ? "" : namespace)) {
            throw new XPathException((org.exist.xquery.Expression) null, ErrorCodes.EXSA0001,
                    "Type " + requested.name().toURIQualifiedName() + " comes from a different schema (" + requested.schema().sha256()
                            + ") than the one document " + doc.getURI() + " was annotated with (" + nodeSchema.sha256() + ")");
        }
    }

    private static @Nullable SchemaKey schemaOf(@Nullable final XQueryContext ctx, final DocumentImpl doc) throws XPathException {
        if (ctx != null) {
            final SchemaKey cached = ctx.getAnnotatingSchema(doc.getDocId());
            if (cached != null) {
                return cached;
            }
        }
        final SchemaKey key;
        try (final DBBroker broker = doc.getBrokerPool().getBroker()) {
            final TypeAnnotationProvider provider = TypeAnnotationProvider.of(broker);
            key = provider == null ? null : provider.schemaOf(doc);
        } catch (final EXistException e) {
            return null;
        }
        if (ctx != null && key != null) {
            ctx.setAnnotatingSchema(doc.getDocId(), key);
        }
        return key;
    }

    private static boolean matches(final TypeAnnotation annotation, final int typeCode, final boolean nillable) {
        if (annotation.nilled() && !nillable) {
            return false;
        }
        return Type.subTypeOf(annotation.typeCode(), typeCode);
    }

    private static TypeAnnotation unannotated(final int kind) {
        return kind == Type.ELEMENT ? TypeAnnotation.UNTYPED : TypeAnnotation.UNTYPED_ATOMIC;
    }
}
