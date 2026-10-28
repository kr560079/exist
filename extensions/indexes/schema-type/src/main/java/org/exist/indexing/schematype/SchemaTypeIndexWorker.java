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

import org.exist.collections.Collection;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.dom.persistent.DocumentSet;
import org.exist.dom.persistent.IStoredNode;
import org.exist.dom.persistent.NodeProxy;
import org.exist.dom.persistent.NodeSet;
import org.exist.indexing.*;
import org.exist.indexing.StreamListener.ReindexMode;
import org.exist.util.Occurrences;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.DBBroker;
import org.exist.storage.NodePath;
import org.exist.util.DatabaseConfigurationException;
import org.exist.xquery.QueryRewriter;
import org.exist.xquery.XQueryContext;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.annotation.Nullable;
import java.util.Map;

public class SchemaTypeIndexWorker implements IndexWorker {

    static final String SCHEMA_TYPE_ELEMENT = "schema-type";
    static final String TYPED_VALUES_ELEMENT = "typed-values";

    private final DBBroker broker;
    private final SchemaTypeIndex index;
    private DocumentImpl document;
    private ReindexMode mode = ReindexMode.UNKNOWN;

    public SchemaTypeIndexWorker(final DBBroker broker, final SchemaTypeIndex index) {
        this.broker = broker;
        this.index = index;
    }

    @Override
    public String getIndexId() {
        return SchemaTypeIndex.ID;
    }

    @Override
    public String getIndexName() {
        return index.getIndexName();
    }

    @Override
    public Object configure(final IndexController controller, final NodeList configNodes, final Map<String, String> namespaces)
            throws DatabaseConfigurationException {
        Element schemaTypeEl = null;
        Element typedValuesEl = null;
        for (int i = 0; i < configNodes.getLength(); i++) {
            final Node node = configNodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            switch (node.getLocalName()) {
                case SCHEMA_TYPE_ELEMENT -> {
                    if (schemaTypeEl != null) {
                        throw new DatabaseConfigurationException("Only one <" + SCHEMA_TYPE_ELEMENT + "> element is allowed per <index>");
                    }
                    schemaTypeEl = (Element) node;
                }
                case TYPED_VALUES_ELEMENT -> {
                    if (typedValuesEl != null) {
                        throw new DatabaseConfigurationException("Only one <" + TYPED_VALUES_ELEMENT + "> element is allowed per <index>");
                    }
                    typedValuesEl = (Element) node;
                }
                default -> { }
            }
        }
        if (schemaTypeEl == null) {
            if (typedValuesEl != null) {
                throw new DatabaseConfigurationException("<" + TYPED_VALUES_ELEMENT + "> requires a <" + SCHEMA_TYPE_ELEMENT + "> element");
            }
            return null;
        }
        return SchemaTypeConfig.parse(broker, schemaTypeEl, typedValuesEl, namespaces);
    }

    @Override
    public void setDocument(final DocumentImpl doc) {
        setDocument(doc, ReindexMode.UNKNOWN);
    }

    @Override
    public void setDocument(final DocumentImpl doc, final ReindexMode mode) {
        this.document = doc;
        this.mode = mode;
    }

    @Override
    public void setMode(final ReindexMode mode) {
        this.mode = mode;
    }

    @Override
    public DocumentImpl getDocument() {
        return document;
    }

    @Override
    public ReindexMode getMode() {
        return mode;
    }

    @Override
    public <T extends IStoredNode> IStoredNode getReindexRoot(final IStoredNode<T> node, final NodePath path, final boolean insert, final boolean includeSelf) {
        return null;
    }

    @Override
    public StreamListener getListener() {
        return null;
    }

    @Override
    public MatchListener getMatchListener(final DBBroker broker, final NodeProxy proxy) {
        return null;
    }

    @Override
    public void flush() {
    }

    @Override
    public void removeCollection(final Collection collection, final DBBroker broker, final boolean configOnlyReindex) throws PermissionDeniedException {
    }

    @Override
    public boolean checkIndex(final DBBroker broker) {
        return true;
    }

    @Override
    public Occurrences[] scanIndex(final XQueryContext context, final DocumentSet docs, final NodeSet contextSet, final Map<?, ?> hints) {
        return null;
    }

    @Override
    public @Nullable QueryRewriter getQueryRewriter(final XQueryContext context) {
        return null;
    }
}
