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
import org.exist.xquery.schema.AnnotationLookupException;
import org.exist.xquery.schema.TypeAnnotations;
import org.exist.xquery.value.Type;
import org.w3c.dom.Node;

import javax.annotation.Nullable;
import javax.xml.stream.XMLStreamReader;

/**
 * Tests the kind, optionally the name, and the type annotation of an element or attribute:
 * {@code element(N, T)}, {@code element(*, T)}, {@code attribute(N, T)} and {@code attribute(*, T)}.
 *
 * <p>The test is always reported as a wildcard test without a name, so that a location step
 * evaluates it node by node instead of looking the name up in the structural index, which
 * knows nothing of types.</p>
 */
public class TypedNodeTest extends TypeTest {

    private final XQueryContext context;
    @Nullable private final NameTest nameTest;
    private final int typeCode;
    private final boolean nillable;

    /**
     * @param context  the context of the query
     * @param nodeKind {@link Type#ELEMENT} or {@link Type#ATTRIBUTE}
     * @param name     the name of the node, or null for any name
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

    @Override
    public boolean matches(final NodeProxy proxy) {
        if (nameTest != null && !nameTest.matches(proxy)) {
            return false;
        }
        if (nameTest == null && !super.matches(proxy)) {
            return false;
        }
        return hasType(proxy);
    }

    @Override
    public boolean matches(final Node other) {
        if (nameTest != null ? !nameTest.matches(other) : !super.matches(other)) {
            return false;
        }
        try {
            return TypeAnnotations.matches(context, other, typeCode, nillable);
        } catch (final XPathException e) {
            // NodeTest#matches cannot throw it: LocationStep unwraps it
            throw new AnnotationLookupException(e);
        }
    }

    @Override
    public boolean matches(final XMLStreamReader reader) {
        if (nameTest != null ? !nameTest.matches(reader) : !super.matches(reader)) {
            return false;
        }
        // no annotation is stored in a stream: the node is unannotated
        return Type.subTypeOf(nodeType == Type.ELEMENT ? Type.UNTYPED : Type.UNTYPED_ATOMIC, typeCode);
    }

    @Override
    public boolean matches(final QName name) {
        return nameTest != null && nameTest.matches(name);
    }

    @Override
    public QName getName() {
        return null;
    }

    @Override
    public boolean isWildcardTest() {
        return true;
    }

    private boolean hasType(final NodeProxy proxy) {
        try {
            return TypeAnnotations.matches(context, proxy, typeCode, nillable);
        } catch (final XPathException e) {
            // NodeTest#matches cannot throw it: LocationStep unwraps it
            throw new AnnotationLookupException(e);
        }
    }

    @Override
    public String toString() {
        return Type.getTypeName(nodeType) + "(" + (nameTest == null ? "*" : nameTest.getName().getStringValue())
                + ", " + Type.getTypeName(typeCode) + (nillable ? "?" : "") + ")";
    }
}
