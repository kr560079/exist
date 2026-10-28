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
        final Object primary = base;
        final Object secondary = tap;
        return (ContentHandler) java.lang.reflect.Proxy.newProxyInstance(IndexInfo.class.getClassLoader(),
                new Class<?>[] {ContentHandler.class, LexicalHandler.class}, (proxy, method, args) -> {
                    try {
                        final Object result = method.invoke(primary, args);
                        method.invoke(secondary, args);
                        return result;
                    } catch (final java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
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
