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
package org.exist.collections;

import org.exist.Indexer;
import org.exist.Namespaces;
import org.exist.collections.triggers.DocumentTriggers;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.security.Permission;
import org.exist.storage.DBBroker;
import org.exist.storage.lock.ManagedDocumentLock;
import org.exist.storage.txn.Txn;
import org.exist.util.serializer.DOMStreamer;
import org.exist.xmldb.XmldbURI;
import org.xml.sax.ContentHandler;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.LexicalHandler;

/**
 * Internal class used to track required fields between calls to
 * {@link org.exist.collections.Collection#validateXMLResource(Txn, DBBroker, XmldbURI, InputSource)} and
 * {@link org.exist.collections.Collection#store(Txn, DBBroker, IndexInfo, InputSource)}.
 * 
 * @author wolf
 */
public class IndexInfo {

    private final Indexer indexer;
    private final CollectionConfiguration collectionConfig;
    private final ManagedDocumentLock documentLock;

    private ContentHandler tap;
    private DOMStreamer streamer;
    private DocumentTriggers docTriggers;
    private boolean creating = false;
    private Permission oldDocPermissions = null;

    IndexInfo(final Indexer indexer, final CollectionConfiguration collectionConfig, final ManagedDocumentLock documentLock) {
        this.indexer = indexer;
        this.collectionConfig = collectionConfig;
        this.documentLock = documentLock;
    }

    public Indexer getIndexer() {
        return indexer;
    }

    //XXX: make protected
    public void setTriggers(final DocumentTriggers triggersVisitor) {
        this.docTriggers = triggersVisitor;
    }

    //XXX: make protected
    public DocumentTriggers getTriggers() {
        return docTriggers;
    }

    public void setCreating(final boolean creating) {
        this.creating = creating;
    }

    public boolean isCreating() {
        return creating;
    }
    
    public void setOldDocPermissions(final Permission oldDocPermissions) {
        this.oldDocPermissions = oldDocPermissions;
    }
    
    public Permission getOldDocPermissions() {
        return oldDocPermissions;
    }

    void setReader(final XMLReader reader, final EntityResolver entityResolver) throws SAXException {
        if(entityResolver != null) {
            reader.setEntityResolver(entityResolver);
        }
        reader.setProperty(Namespaces.SAX_LEXICAL_HANDLER, getLexicalHandler());
        reader.setContentHandler(getContentHandler());
        reader.setErrorHandler(indexer);
    }

    /**
     * The same content handler {@link #setReader(XMLReader, EntityResolver)} wires onto an
     * {@link XMLReader} -- exposed so callers that validate via a {@link javax.xml.validation.ValidatorHandler}
     * instead of an {@code XMLReader} (which has no equivalent {@code setReader}-style helper) can feed
     * the same indexing/trigger pipeline through a SAX-driven validation pass.
     */
    ContentHandler getContentHandler() {
        final ContentHandler base = docTriggers == null ? indexer : docTriggers;
        return tap == null ? base : tee(base);
    }

    /**
     * Also sends the SAX events of the document being validated to {@code tapHandler} (or stops doing so
     * if null), so that the content is available before anything is written.
     */
    <T extends ContentHandler & LexicalHandler> void setTap(final T tapHandler) {
        this.tap = tapHandler;
    }

    private ContentHandler tee(final ContentHandler base) {
        return new Tee(base, (LexicalHandler) base, tap, (LexicalHandler) tap);
    }

    /**
     * Sends every SAX event to the primary handler, then to the tap (called directly: this runs for every event
     * of every document validated into a collection with a tap).
     */
    private static final class Tee implements ContentHandler, LexicalHandler {
        private final ContentHandler primary;
        private final LexicalHandler primaryLexical;
        private final ContentHandler secondary;
        private final LexicalHandler secondaryLexical;

        Tee(final ContentHandler primary, final LexicalHandler primaryLexical, final ContentHandler secondary,
                final LexicalHandler secondaryLexical) {
            this.primary = primary;
            this.primaryLexical = primaryLexical;
            this.secondary = secondary;
            this.secondaryLexical = secondaryLexical;
        }

        @Override
        public void setDocumentLocator(final org.xml.sax.Locator locator) {
            primary.setDocumentLocator(locator);
            secondary.setDocumentLocator(locator);
        }

        @Override
        public void startDocument() throws SAXException {
            primary.startDocument();
            secondary.startDocument();
        }

        @Override
        public void endDocument() throws SAXException {
            primary.endDocument();
            secondary.endDocument();
        }

        @Override
        public void declaration(final String version, final String encoding, final String standalone) throws SAXException {
            primary.declaration(version, encoding, standalone);
            secondary.declaration(version, encoding, standalone);
        }

        @Override
        public void startPrefixMapping(final String prefix, final String uri) throws SAXException {
            primary.startPrefixMapping(prefix, uri);
            secondary.startPrefixMapping(prefix, uri);
        }

        @Override
        public void endPrefixMapping(final String prefix) throws SAXException {
            primary.endPrefixMapping(prefix);
            secondary.endPrefixMapping(prefix);
        }

        @Override
        public void startElement(final String uri, final String localName, final String qName,
                final org.xml.sax.Attributes atts) throws SAXException {
            primary.startElement(uri, localName, qName, atts);
            secondary.startElement(uri, localName, qName, atts);
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) throws SAXException {
            primary.endElement(uri, localName, qName);
            secondary.endElement(uri, localName, qName);
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) throws SAXException {
            primary.characters(ch, start, length);
            secondary.characters(ch, start, length);
        }

        @Override
        public void ignorableWhitespace(final char[] ch, final int start, final int length) throws SAXException {
            primary.ignorableWhitespace(ch, start, length);
            secondary.ignorableWhitespace(ch, start, length);
        }

        @Override
        public void processingInstruction(final String target, final String data) throws SAXException {
            primary.processingInstruction(target, data);
            secondary.processingInstruction(target, data);
        }

        @Override
        public void skippedEntity(final String name) throws SAXException {
            primary.skippedEntity(name);
            secondary.skippedEntity(name);
        }

        @Override
        public void startDTD(final String name, final String publicId, final String systemId) throws SAXException {
            primaryLexical.startDTD(name, publicId, systemId);
            secondaryLexical.startDTD(name, publicId, systemId);
        }

        @Override
        public void endDTD() throws SAXException {
            primaryLexical.endDTD();
            secondaryLexical.endDTD();
        }

        @Override
        public void startEntity(final String name) throws SAXException {
            primaryLexical.startEntity(name);
            secondaryLexical.startEntity(name);
        }

        @Override
        public void endEntity(final String name) throws SAXException {
            primaryLexical.endEntity(name);
            secondaryLexical.endEntity(name);
        }

        @Override
        public void startCDATA() throws SAXException {
            primaryLexical.startCDATA();
            secondaryLexical.startCDATA();
        }

        @Override
        public void endCDATA() throws SAXException {
            primaryLexical.endCDATA();
            secondaryLexical.endCDATA();
        }

        @Override
        public void comment(final char[] ch, final int start, final int length) throws SAXException {
            primaryLexical.comment(ch, start, length);
            secondaryLexical.comment(ch, start, length);
        }
    }

    /**
     * The same lexical handler {@link #setReader(XMLReader, EntityResolver)} wires onto an
     * {@link XMLReader} -- see {@link #getContentHandler()} for why this is also exposed
     * separately.
     */
    LexicalHandler getLexicalHandler() {
        return tap == null ? (docTriggers == null ? indexer : docTriggers) : (LexicalHandler) getContentHandler();
    }

    void setDOMStreamer(final DOMStreamer streamer) {
        this.streamer = streamer;
        streamer.setContentHandler(getContentHandler());
        streamer.setLexicalHandler(getLexicalHandler());
    }

    public DOMStreamer getDOMStreamer() {
        return this.streamer;
    }

    public DocumentImpl getDocument() {
        return indexer.getDocument();
    }

    public CollectionConfiguration getCollectionConfig() {
        return collectionConfig;
    }

    public ManagedDocumentLock getDocumentLock() {
        return documentLock;
    }
}
