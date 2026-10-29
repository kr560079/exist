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

import org.exist.dom.persistent.DocumentSet;
import org.exist.dom.persistent.NodeProxy;
import org.exist.dom.persistent.NodeSet;
import org.exist.numbering.NodeId;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.Type;
import org.w3c.dom.Node;

import java.util.Set;
import java.util.StringTokenizer;

/**
 * Resolves {@code fn:id}, {@code fn:element-with-id} and {@code fn:idref} through the schema-typed IDs of stored
 * documents: nodes whose type annotation derives from {@code xs:ID}, {@code xs:IDREF} or {@code xs:IDREFS}.
 *
 * <p>Nothing is done (and no index is read) when the schema type index is not enabled; the provider skips
 * collections without a {@code schema-type} configuration.</p>
 */
public final class SchemaIds {

    private SchemaIds() {
    }

    /**
     * Add the elements with a schema-typed ID among {@code ids}.
     *
     * @param ctx           the context
     * @param docs          the documents to search
     * @param ids           the (whitespace-free) ID values to find
     * @param result        receives the elements
     * @param parentOfIdElement false for {@code fn:id}, which returns an element that itself has the ID property;
     *                      true for {@code fn:element-with-id}, which returns its parent element instead
     * @throws XPathException {@code EXSA0002} or {@code EXSA0003} from the index
     */
    public static void addElementsWithId(final XQueryContext ctx, final DocumentSet docs, final Set<String> ids,
            final NodeSet result, final boolean parentOfIdElement) throws XPathException {
        final TypeAnnotationProvider provider = TypeAnnotationProvider.of(ctx.getBroker());
        if (provider == null || ids.isEmpty()) {
            return;
        }
        for (final NodeProxy attribute : provider.nodesOfType(docs, Type.ID, true, Type.ATTRIBUTE)) {
            if (ids.contains(attribute.getStringValue().strip())) {
                addParent(result, attribute);
            }
        }
        for (final NodeProxy element : provider.nodesOfType(docs, Type.ID, true, Type.ELEMENT)) {
            if (ids.contains(element.getStringValue().strip())) {
                if (parentOfIdElement) {
                    addParent(result, element);
                } else {
                    result.add(new NodeProxy(element.getOwnerDocument(), element.getNodeId(), Node.ELEMENT_NODE));
                }
            }
        }
    }

    /**
     * Add the attributes and elements whose IDREF or IDREFS value contains one of {@code ids}.
     *
     * @throws XPathException {@code EXSA0002} or {@code EXSA0003} from the index
     */
    public static void addNodesWithIdref(final XQueryContext ctx, final DocumentSet docs, final Set<String> ids,
            final NodeSet result) throws XPathException {
        final TypeAnnotationProvider provider = TypeAnnotationProvider.of(ctx.getBroker());
        if (provider == null || ids.isEmpty()) {
            return;
        }
        for (final int kind : new int[] {Type.ATTRIBUTE, Type.ELEMENT}) {
            for (final NodeProxy node : provider.nodesOfType(docs, Type.IDREF, true, kind)) {
                if (ids.contains(node.getStringValue().strip())) {
                    result.add(node);
                }
            }
            for (final NodeProxy node : provider.nodesOfType(docs, Type.IDREFS, true, kind)) {
                final StringTokenizer tokens = new StringTokenizer(node.getStringValue());
                while (tokens.hasMoreTokens()) {
                    if (ids.contains(tokens.nextToken())) {
                        result.add(node);
                        break;
                    }
                }
            }
        }
    }

    private static void addParent(final NodeSet result, final NodeProxy node) {
        final NodeId parent = node.getNodeId().getParentId();
        if (parent != null && !NodeId.DOCUMENT_NODE.equals(parent)) {
            result.add(new NodeProxy(node.getOwnerDocument(), parent, Node.ELEMENT_NODE));
        }
    }
}
