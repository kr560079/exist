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

import org.exist.collections.Collection;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.dom.persistent.DocumentSet;
import org.exist.dom.persistent.NodeProxy;
import org.exist.dom.persistent.NodeSet;
import org.exist.indexing.IndexController;
import org.exist.indexing.IndexWorker;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.Txn;
import org.exist.util.DatabaseConfigurationException;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.XPathException;
import org.w3c.dom.NodeList;
import org.xml.sax.ContentHandler;
import org.xml.sax.ext.LexicalHandler;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * Supplies the type annotations of stored nodes. Implemented by the index worker of the schema type index.
 */
public interface TypeAnnotationProvider {

    /** The id of the index whose worker provides the annotations. */
    String INDEX_ID = "org.exist.indexing.schematype.SchemaTypeIndex";

    /**
     * Get the type annotation of a stored node.
     *
     * @param node the node
     * @return null if the collection of the node has no schema; {@link TypeAnnotation#ANY_TYPE} (elements)
     *         or {@link TypeAnnotation#UNTYPED_ATOMIC} (attributes) if it has a schema but the node is not
     *         annotated (not {@link TypeAnnotation#assessed() assessed}); otherwise the stored annotation, assessed
     * @throws XPathException {@code EXSA0002} if the schema is unavailable, {@code EXSA0003} if the index
     *         is stale for the document of the node
     */
    @Nullable TypeAnnotation annotationOf(NodeProxy node) throws XPathException;

    /**
     * Find the nodes annotated with a type.
     *
     * @param docs           the documents to search
     * @param typeCode       the {@link org.exist.xquery.value.Type} code of the type
     * @param includeDerived true to also find nodes annotated with types derived from {@code typeCode}
     * @param nodeKind       the {@link org.exist.xquery.value.Type} code of the node kind (element or attribute)
     * @return the matching nodes
     * @throws XPathException {@code EXSA0002} or {@code EXSA0003}
     */
    NodeSet nodesOfType(DocumentSet docs, int typeCode, boolean includeDerived, int nodeKind) throws XPathException;

    /**
     * Get the schema the document was annotated with.
     *
     * @param doc the document
     * @return the key of the schema, or null if the collection of the document has no schema
     * @throws XPathException {@code EXSA0002} if the schema is unavailable
     */
    @Nullable SchemaKey schemaOf(DocumentImpl doc) throws XPathException;

    /**
     * Find the schema that collection configurations give a target namespace, for an {@code import schema} without
     * location hints. Only the configurations of collections the broker's current subject may read count; the schema
     * is loaded as the configuration's owner (who configured it for the readers of the collection).
     *
     * @param namespaceUri the target namespace, {@code ""} for no namespace
     * @return the schema (recording the configured collection it came from), or null if no readable configuration has
     *     a schema for the namespace
     * @throws XPathException {@code XQST0059} if readable configurations have different schemas for the namespace,
     *     or the configured schema cannot be loaded
     */
    default @Nullable ImportedSchema configuredSchema(final String namespaceUri) throws XPathException {
        return null;
    }

    /**
     * Resolve, as {@link #configuredSchema(String)} does, the key of the schema that collection configurations
     * readable by the broker's current subject give a namespace, without loading it.
     *
     * @param namespaceUri the target namespace
     * @return the key of the configured schema, or null if no readable configuration has one for the namespace
     * @throws XPathException {@code XQST0059} if readable configurations have different schemas for the namespace
     */
    default @Nullable SchemaKey configuredSchemaKey(final String namespaceUri) throws XPathException {
        return null;
    }

    /**
     * @param collectionUri a collection
     * @return the key of the schema the collection's own configuration has, or null if it has none (or no usable one)
     */
    default @Nullable SchemaKey configuredSchemaKeyOf(final XmldbURI collectionUri) {
        return null;
    }

    /**
     * A check of a document about to be stored, fed with its SAX events before anything is written. It vetoes the
     * store by throwing a {@link org.xml.sax.SAXException}.
     */
    interface StoreCheck extends ContentHandler, LexicalHandler {
    }

    /**
     * Get the check of a document about to be stored into a collection.
     *
     * @param collection the collection the document is stored into
     * @param name       the name of the document
     * @return the check, or null if the collection has no schema (nothing is then done for the store)
     */
    default @Nullable StoreCheck storeCheck(final Collection collection, final XmldbURI name) {
        return null;
    }

    /**
     * Check the schema-type sections of a collection configuration that is being stored: unlike when a stored
     * configuration is loaded, an unusable schema rejects them.
     *
     * @param controller  the index controller
     * @param configNodes the {@code schema-type} and {@code typed-values} elements of an {@code index} element
     * @param namespaces  the namespaces in scope
     * @throws DatabaseConfigurationException if the configuration is unusable
     */
    default void checkConfiguration(final IndexController controller, final NodeList configNodes, final Map<String, String> namespaces)
            throws DatabaseConfigurationException {
    }

    /**
     * Called after a collection configuration document was stored and loaded, in the store's transaction: a
     * collection configured with {@code reindex-on-schema-change} whose schema key changed is reindexed.
     *
     * @param broker               the broker of the storing user
     * @param txn                  the store's transaction
     * @param dataCollectionUri    the collection the configuration applies to
     */
    default void configurationStored(final DBBroker broker, final Txn txn, final XmldbURI dataCollectionUri) {
    }

    /**
     * Called around the loading of a stored collection configuration: the subject its schema documents are read as.
     *
     * @param owner the owner of the configuration document, null if it cannot be determined (and after the loading)
     */
    default void setConfigurationOwner(@Nullable final org.exist.security.Subject owner) {
    }

    /**
     * Get the provider of a broker.
     *
     * @param broker the broker
     * @return the provider, or null if the schema type index is not configured
     */
    static @Nullable TypeAnnotationProvider of(final DBBroker broker) {
        final IndexWorker worker = broker.getIndexController().getWorkerByIndexId(INDEX_ID);
        return worker instanceof TypeAnnotationProvider provider ? provider : null;
    }
}
