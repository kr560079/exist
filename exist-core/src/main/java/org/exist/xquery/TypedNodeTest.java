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
package org.exist.xquery;

import org.exist.dom.QName;
import org.exist.dom.persistent.NodeProxy;
import org.exist.dom.memtree.NodeImpl;
import org.exist.dom.memtree.ReferenceNode;
import org.exist.stax.EmbeddedXMLStreamReader;
import org.exist.xquery.schema.AnnotationLookupException;
import org.exist.xquery.schema.TypeAnnotations;
import org.exist.xquery.value.Type;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import javax.annotation.Nullable;
import javax.xml.stream.XMLStreamReader;

/**
 * Tests the kind, optionally the name, and the type annotation of an element or attribute:
 * {@code element(N, T)}, {@code element(*, T)}, {@code attribute(N, T)} and {@code attribute(*, T)}, with
 * {@code T?} for nillable element tests. With the kind {@link Type#DOCUMENT} it tests the document element of
 * a document node: {@code document-node(element(N, T))}.
 *
 * <p>An element or attribute test with a name reports that name and is not a wildcard test: a location step
 * looks the name up in the structural index, which knows nothing of types, and then keeps the nodes whose type
 * annotation matches ({@link #matchesType(NodeProxy)}). A test without a name, and a document node test, are
 * wildcard tests without a name, evaluated node by node (or answered from the schema type index by its query
 * rewriter).</p>
 */
public class TypedNodeTest extends TypeTest {

    protected final XQueryContext context;
    @Nullable private final NameTest nameTest;
    protected final int typeCode;
    protected final boolean nillable;

    /**
     * @param context  the context of the query
     * @param nodeKind {@link Type#ELEMENT}, {@link Type#ATTRIBUTE} or {@link Type#DOCUMENT}
     * @param name     the name of the node (of the document element for a document node), or null for any name
     * @param typeCode the {@link Type} code the type annotation of the node must be a subtype of
     * @param nillable true if a nilled node may match
     * @throws XPathException if the name is not valid
     */
    public TypedNodeTest(final XQueryContext context, final int nodeKind, @Nullable final QName name,
            final int typeCode, final boolean nillable) throws XPathException {
        super(nodeKind);
        this.context = context;
        this.nameTest = name == null ? null : new NameTest(nodeKind, name);
        this.typeCode = typeCode;
        this.nillable = nillable;
    }

    /**
     * Test the name of a node of the right kind.
     *
     * @param node an element, attribute or document node
     * @return true if the name matches
     */
    protected boolean matchesName(final Node node) {
        return nameTest == null || nameTest.matchesName(node);
    }

    /**
     * Test the name of the current node of a stream, of the right kind.
     *
     * @param reader the stream
     * @return true if the name matches
     */
    protected boolean matchesName(final XMLStreamReader reader) {
        return nameTest == null || nameTest.matches(reader);
    }

    /**
     * @param name a node name
     * @return true if this test can only match nodes of that name
     */
    @Override
    public boolean matches(final QName name) {
        return nameTest != null && nameTest.matches(name);
    }

    /** @return true if a node without a stored type annotation never matches (schema-element, schema-attribute) */
    protected boolean requiresAnnotation() {
        return false;
    }

    @Override
    public boolean matches(final NodeProxy proxy) {
        final Node node;
        if (proxy.getNodeType() == NodeProxy.UNKNOWN_NODE_TYPE) {
            // e.g. a node found in a value index: its kind is that of the stored node
            node = proxy.getNode();
            if (!super.matches(node)) {
                return false;
            }
        } else {
            if (!super.matches(proxy)) {
                return false;
            }
            node = proxy.getNode();
        }
        if (node == null || !matchesName(node)) {
            return false;
        }
        if (nodeType == Type.DOCUMENT) {
            return hasType(((Document) node).getDocumentElement());
        }
        try {
            return TypeAnnotations.matches(context, proxy, typeCode, nillable, requiresAnnotation());
        } catch (final XPathException e) {
            // NodeTest#matches cannot throw it: LocationStep unwraps it
            throw new AnnotationLookupException(e);
        }
    }

    @Override
    public boolean matches(final Node other) {
        if (other == null) {
            return false;
        }
        if (other.getNodeType() == NodeImpl.REFERENCE_NODE) {
            return matches(((ReferenceNode) other).getReference());
        }
        if (!super.matches(other) || !matchesName(other)) {
            return false;
        }
        return hasType(nodeType == Type.DOCUMENT ? ((Document) other).getDocumentElement() : other);
    }

    @Override
    public boolean matches(final XMLStreamReader reader) {
        if (!super.matches(reader) || !matchesName(reader)) {
            return false;
        }
        if (reader instanceof EmbeddedXMLStreamReader stored) {
            // a stream over stored nodes, e.g. of a child step: test the stored node
            return hasType(stored.getNode());
        }
        // no annotation is stored in any other stream: the node is unannotated
        return !requiresAnnotation()
                && Type.subTypeOf(nodeType == Type.ATTRIBUTE ? Type.UNTYPED_ATOMIC : Type.UNTYPED, typeCode);
    }

    /** @return the name of the test, or null for a test of any name and for a document node test */
    @Override
    public @Nullable QName getName() {
        return nameTest == null || nodeType == Type.DOCUMENT ? null : nameTest.getName();
    }

    @Override
    public boolean isWildcardTest() {
        return getName() == null;
    }

    /** @return the {@link Type} code the type annotation of a matching node must be a subtype of */
    public int getTypeCode() {
        return typeCode;
    }

    /** @return true if a nilled node may match */
    public boolean isNillable() {
        return nillable;
    }

    /** @return true if a node without a stored type annotation never matches (schema-element, schema-attribute) */
    public boolean isAnnotationRequired() {
        return requiresAnnotation();
    }

    /**
     * Test the type annotation (and the nilled property) of a stored element or attribute already known to have the
     * right kind and name, e.g. found by the name in the structural index.
     *
     * @param proxy an element or attribute
     * @return true if its type annotation matches
     * @throws AnnotationLookupException if the annotation cannot be determined
     */
    public boolean matchesType(final NodeProxy proxy) {
        if (nodeType == Type.DOCUMENT) {
            return matches(proxy);
        }
        try {
            return TypeAnnotations.matches(context, proxy, typeCode, nillable, requiresAnnotation());
        } catch (final XPathException e) {
            // NodeTest#matches cannot throw it: LocationStep unwraps it
            throw new AnnotationLookupException(e);
        }
    }

    /** test the type annotation of an element or attribute (the document element of a document node) */
    private boolean hasType(@Nullable final Node node) {
        if (node == null) {
            return false;
        }
        if (typeCode == Type.ANY_TYPE && nillable && !requiresAnnotation()) {
            // element(N, xs:anyType?), e.g. of document-node(element(N)): every element matches
            return true;
        }
        try {
            return TypeAnnotations.matches(context, node, typeCode, nillable, requiresAnnotation());
        } catch (final XPathException e) {
            // NodeTest#matches cannot throw it: LocationStep unwraps it
            throw new AnnotationLookupException(e);
        }
    }

    @Override
    public String toString() {
        final String test = Type.getTypeName(nodeType == Type.DOCUMENT ? Type.ELEMENT : nodeType) + "("
                + (nameTest == null ? "*" : nameTest.getName().getStringValue())
                + ", " + Type.getTypeName(typeCode) + (nillable ? "?" : "") + ")";
        return nodeType == Type.DOCUMENT ? "document-node(" + test + ")" : test;
    }
}
