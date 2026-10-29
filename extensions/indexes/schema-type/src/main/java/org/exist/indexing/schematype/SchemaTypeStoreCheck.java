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
import org.exist.dom.QName;
import org.exist.numbering.NodeId;
import org.exist.numbering.NodeIdFactory;
import org.exist.xquery.schema.TypeAnnotationProvider;
import org.exist.xquery.schema.ValueChecker;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DefaultHandler2;

import javax.xml.XMLConstants;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * The pre-write check of a document stored into a collection with a {@code <schema-type>} configuration, fed by
 * the SAX events of the validation pass of a store (nothing has been written yet, so a veto leaves no trace):
 * <ul>
 *   <li>the collection's schema must be available ({@code EXSA0002});</li>
 *   <li>with {@code on-unassignable="reject"}, the document is run through a {@link TypeAssigner} and the first node
 *       that cannot be typed fails the store ({@code EXSA0004}, node path and reason).</li>
 * </ul>
 * With {@code on-unassignable="untyped"} only the availability is checked, so the check is almost free.
 */
final class SchemaTypeStoreCheck extends DefaultHandler2 implements TypeAnnotationProvider.StoreCheck {

    private final SchemaTypeIndexWorker worker;
    private final Collection collection;
    private final SchemaTypeConfig config;
    private final NodeIdFactory ids;
    private final boolean reject;

    private TypeAssigner assigner;
    /** element id and the id of its last child so far, per open element */
    private final Deque<NodeId[]> open = new ArrayDeque<>();
    private final Deque<Map<String, String>> scopes = new ArrayDeque<>();
    private final Map<String, String> declared = new HashMap<>();
    private NodeId rootId;

    SchemaTypeStoreCheck(final SchemaTypeIndexWorker worker, final Collection collection, final SchemaTypeConfig config,
            final NodeIdFactory ids) {
        this.worker = worker;
        this.collection = collection;
        this.config = config;
        this.ids = ids;
        this.reject = config.onUnassignable() == OnUnassignable.REJECT;
    }

    @Override
    public void setDocumentLocator(final Locator locator) {
    }

    @Override
    public void startDocument() throws SAXException {
        try {
            worker.verifySchemaAvailable(collection, config);
        } catch (final SchemaTypeException e) {
            throw new SAXException(e.getMessage(), e);
        }
        if (reject) {
            assigner = new TypeAssigner(config.schema(), config.checkValues() ? new ValueChecker(config.schema()) : null);
        }
    }

    @Override
    public void startPrefixMapping(final String prefix, final String uri) {
        if (assigner != null) {
            declared.put(prefix, uri);
        }
    }

    @Override
    public void startElement(final String uri, final String localName, final String qName, final Attributes atts) throws SAXException {
        if (assigner == null) {
            return;
        }
        final Map<String, String> parent = scopes.peek();
        Map<String, String> scope = parent == null ? Map.of() : parent;
        if (!declared.isEmpty()) {
            scope = new HashMap<>(scope);
            scope.putAll(declared);
            declared.clear();
        }
        scopes.push(scope);
        final NodeId id = nextChildId();
        open.push(new NodeId[] {id, null});
        final QName name = qname(uri, localName, qName);
        final Map<String, String> inScope = scope;
        assigner.startElement(id, name, prefix -> resolvePrefix(inScope, name, prefix));
        for (int i = 0; i < atts.getLength(); i++) {
            final String attQName = atts.getQName(i);
            if (attQName.equals(XMLConstants.XMLNS_ATTRIBUTE) || attQName.startsWith(XMLConstants.XMLNS_ATTRIBUTE + ':')) {
                continue;
            }
            assigner.attribute(nextChildId(), qname(atts.getURI(i), atts.getLocalName(i), attQName), atts.getValue(i));
        }
        check();
    }

    @Override
    public void characters(final char[] ch, final int start, final int length) throws SAXException {
        if (assigner != null) {
            assigner.characters(new String(ch, start, length));
            check();
        }
    }

    @Override
    public void ignorableWhitespace(final char[] ch, final int start, final int length) throws SAXException {
        characters(ch, start, length);
    }

    @Override
    public void endElement(final String uri, final String localName, final String qName) throws SAXException {
        if (assigner != null) {
            assigner.endElement();
            open.pop();
            scopes.pop();
            check();
        }
    }

    /** fail the store on the first unassignable node */
    private void check() throws SAXException {
        for (final TypeAssigner.Assignment assignment : assigner.drain()) {
            if (assignment.status() == TypeAssigner.Status.UNASSIGNABLE) {
                final String reason = assignment.reason();
                final SchemaTypeException e = new SchemaTypeException(org.exist.xquery.ErrorCodes.EXSA0004, "node " + assignment.path()
                        + " cannot be assigned a type"
                        // the schema's location only goes to a caller who may read the collection
                        + (worker.mayRead(collection) ? " by schema " + config.href() : "") + ": " + (reason == null ? "unknown reason" : reason));
                throw new SAXException(e.getMessage(), e);
            }
        }
    }

    private NodeId nextChildId() {
        final NodeId[] parent = open.peek();
        if (parent == null) {
            if (rootId == null) {
                rootId = ids.createInstance();
                return rootId;
            }
            return rootId = rootId.nextSibling();
        }
        final NodeId id = parent[1] == null ? parent[0].newChild() : parent[1].nextSibling();
        parent[1] = id;
        return id;
    }

    private static QName qname(final String uri, final String local, final String qName) {
        final int colon = qName.indexOf(':');
        final String prefix = colon < 0 ? null : qName.substring(0, colon);
        return new QName(local.isEmpty() ? (colon < 0 ? qName : qName.substring(colon + 1)) : local, uri == null ? "" : uri, prefix);
    }

    private static String resolvePrefix(final Map<String, String> scope, final QName element, final String prefix) {
        if (XMLConstants.XML_NS_PREFIX.equals(prefix)) {
            return XMLConstants.XML_NS_URI;
        }
        final String uri = scope.get(prefix);
        if (uri != null) {
            return uri;
        }
        final String elementPrefix = element.getPrefix() == null ? "" : element.getPrefix();
        return elementPrefix.equals(prefix) ? element.getNamespaceURI() : null;
    }
}
