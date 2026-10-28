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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.exist.EXistException;
import org.exist.collections.Collection;
import org.exist.dom.QName;
import org.exist.dom.persistent.DefaultDocumentSet;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.dom.persistent.DocumentSet;
import org.exist.dom.persistent.IStoredNode;
import org.exist.dom.persistent.NewArrayNodeSet;
import org.exist.dom.persistent.NodeProxy;
import org.exist.dom.persistent.NodeSet;
import org.exist.indexing.*;
import org.exist.indexing.StreamListener.ReindexMode;
import org.exist.indexing.schematype.TypeIndexStore.Member;
import org.exist.indexing.schematype.TypeIndexStore.StoredAnnotation;
import org.exist.numbering.NodeId;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.DBBroker;
import org.exist.storage.IndexSpec;
import org.exist.storage.Indexable;
import org.exist.storage.NodePath;
import org.exist.util.DatabaseConfigurationException;
import org.exist.util.Occurrences;
import org.exist.xquery.QueryRewriter;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.schema.SchemaKey;
import org.exist.xquery.schema.TypeAnnotation;
import org.exist.xquery.schema.TypeAnnotationProvider;
import org.exist.xquery.schema.TypedValues;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.Type;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The worker of the schema type index: assigns types to the nodes of documents stored in collections with a
 * {@code <schema-type>} configuration, maintains their entries through updates and removals, and provides the
 * annotations to the query engine.
 *
 * <p>Collections without a configuration are untouched: the worker returns no listener for them.</p>
 */
public class SchemaTypeIndexWorker implements IndexWorker, TypeAnnotationProvider {

    private static final Logger LOG = LogManager.getLogger(SchemaTypeIndexWorker.class);

    static final String SCHEMA_TYPE_ELEMENT = "schema-type";
    static final String TYPED_VALUES_ELEMENT = "typed-values";

    /** the size of the annotation cache (ruling R3) */
    static final int CACHE_SIZE = 10_000;

    private final DBBroker broker;
    private final SchemaTypeIndex index;
    private final SchemaTypeStreamListener listener = new SchemaTypeStreamListener(this);
    private DocumentImpl document;
    private @Nullable SchemaTypeConfig config;
    private ReindexMode mode = ReindexMode.UNKNOWN;
    /** the current document is being removed: drop its entries on the next flush */
    private boolean removeAllPending;

    private record CacheKey(int docId, String nodeId) {
    }

    /** a cached lookup, valid while the document's generation is unchanged */
    private record Cached(long generation, @Nullable StoredAnnotation annotation) {
    }

    private final Map<CacheKey, Cached> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<CacheKey, Cached> eldest) {
            return size() > CACHE_SIZE;
        }
    };

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

    // --- lifecycle ---

    @Override
    public void setDocument(final DocumentImpl doc) {
        setDocument(doc, ReindexMode.UNKNOWN);
    }

    @Override
    public void setDocument(final DocumentImpl doc, final ReindexMode mode) {
        final boolean otherPassDoc = listener.kind() != SchemaTypeStreamListener.PassKind.NONE
                && (doc == null || doc.getDocId() != listener.docId());
        final boolean otherRemovedDoc = removeAllPending && (doc == null || document.getDocId() != doc.getDocId());
        if (otherPassDoc || otherRemovedDoc) {
            // another document: complete the work collected for the previous one
            flush();
        }
        this.document = doc;
        this.config = doc == null ? null : configOf(doc.getCollection());
        setMode(mode);
    }

    @Override
    public void setMode(final ReindexMode mode) {
        this.mode = mode;
        if (mode == ReindexMode.REMOVE_ALL_NODES && document != null) {
            removeAllPending = true;
        }
    }

    @Override
    public DocumentImpl getDocument() {
        return document;
    }

    @Override
    public ReindexMode getMode() {
        return mode;
    }

    @Nullable SchemaTypeConfig currentConfig() {
        return config;
    }

    /**
     * Any change to a typed document is handled by reassigning the types of the whole document, so the reindex
     * root is the root element: the assigner needs the types of all ancestors of a changed node.
     */
    @Override
    public <T extends IStoredNode> IStoredNode getReindexRoot(final IStoredNode<T> node, final NodePath path, final boolean insert, final boolean includeSelf) {
        if (configOf(node.getOwnerDocument().getCollection()) == null) {
            return null;
        }
        IStoredNode current = node;
        while (current != null && current.getNodeId().getTreeLevel() > 1) {
            current = current.getParentStoredNode();
        }
        return current;
    }

    @Override
    public StreamListener getListener() {
        if (config == null || mode == ReindexMode.REMOVE_ALL_NODES || mode == ReindexMode.REMOVE_BINARY) {
            return null;
        }
        return listener;
    }

    @Override
    public MatchListener getMatchListener(final DBBroker broker, final NodeProxy proxy) {
        return null;
    }

    @Override
    public void flush() {
        try {
            if (removeAllPending) {
                removeAllPending = false;
                removeDocument(document);
            }
            switch (listener.kind()) {
                case FULL -> writeFullPass();
                case SUBTREES -> removeSubtrees();
                default -> { }
            }
        } catch (final IOException e) {
            LOG.error("Error writing the schema type index: {}", e.getMessage(), e);
            listener.reset();
        }
    }

    private void removeDocument(final DocumentImpl doc) throws IOException {
        if (doc == null || doc.getCollection() == null) {
            return;
        }
        final CollectionMeta meta = index.store().getCollectionMeta(doc.getCollection().getId());
        if (meta != null) {
            index.store().removeDocument(doc.getDocId(), meta.localTypeIds().values());
        }
    }

    private void removeSubtrees() throws IOException {
        final int docId = listener.docId();
        final CollectionMeta meta = index.store().getCollectionMeta(document.getCollection().getId());
        if (meta != null) {
            for (final NodeId root : listener.removalRoots()) {
                if (root.getTreeLevel() == 1) {
                    index.store().removeAnnotations(docId, meta.localTypeIds().values());
                    break;
                }
                index.store().removeNodes(docId, root);
            }
        }
        listener.reset();
    }

    private void writeFullPass() throws IOException {
        final SchemaTypeConfig cfg = listener.config();
        final int docId = listener.docId();
        final int collectionId = document.getCollection().getId();
        final List<SchemaTypeStreamListener.Pending> work = listener.takePending();

        // the stable ids of the types to persist; types without one are left unannotated
        final Map<Integer, String> typeIds = new HashMap<>();
        final List<String> unassignable = new ArrayList<>();
        for (final SchemaTypeStreamListener.Pending p : work) {
            final TypeAssigner.Assignment a = p.assignment();
            if (a.status() == TypeAssigner.Status.UNASSIGNABLE) {
                unassignable.add(a.path());
            } else if (!typeIds.containsKey(a.typeCode()) && !isDefault(a)) {
                final String typeId = LocalTypeTable.typeIdOf(a.typeCode());
                if (typeId != null && LocalTypeTable.isStable(typeId)) {
                    typeIds.put(a.typeCode(), typeId);
                } else if (typeId != null && index.reportUnstable(typeId)) {
                    LOG.warn("Type {} of schema {} has no stable id: its nodes are left unannotated", typeId, cfg.href());
                }
            }
        }
        final LocalTypeTable table = index.allocate(collectionId, cfg.schema(), new LinkedHashSet<>(typeIds.values()));
        final TypeIndexStore store = index.store();
        if (listener.takeClear()) {
            store.removeAnnotations(docId, table.allLocalIds());
        }

        final Map<Short, List<Member>> members = new LinkedHashMap<>();
        final Map<TypedKey, List<Member>> typedValues = new LinkedHashMap<>();
        for (final SchemaTypeStreamListener.Pending p : work) {
            final TypeAssigner.Assignment a = p.assignment();
            if (a.status() != TypeAssigner.Status.ANNOTATED || !typeIds.containsKey(a.typeCode())) {
                continue;
            }
            final Short localTypeId = table.localOf(a.typeCode());
            if (localTypeId == null) {
                continue;
            }
            store.putAnnotation(docId, a.nodeId(), localTypeId, a.nilled());
            final Member member = new Member(a.nodeId(), (short) a.nodeKind());
            members.computeIfAbsent(localTypeId, k -> new ArrayList<>()).add(member);
            if (p.lexical() != null && !a.nilled()) {
                final QName typeName = LocalTypeTable.typeNameOf(a.typeCode());
                if (typeName != null && cfg.typedValueTypes().contains(typeName)) {
                    for (final byte[] key : valueKeys(p.lexical(), a.typeCode())) {
                        typedValues.computeIfAbsent(new TypedKey(localTypeId, key), k -> new ArrayList<>()).add(member);
                    }
                }
            }
        }
        for (final Map.Entry<Short, List<Member>> entry : members.entrySet()) {
            store.addTypeMembers(entry.getKey(), docId, entry.getValue());
        }
        for (final Map.Entry<TypedKey, List<Member>> entry : typedValues.entrySet()) {
            store.putTypedValues(entry.getKey().localTypeId(), entry.getKey().key(), docId, entry.getValue());
        }
        listener.recordUnassignable(unassignable);

        if (listener.rootEnded()) {
            final List<String> paths = listener.unassignablePaths();
            final int count = listener.unassignableCount();
            store.putDocMeta(docId, new DocMeta(cfg.schema().key(), count > 0 ? DocMeta.DocStatus.PARTIAL : DocMeta.DocStatus.TYPED, paths));
            if (count > 0) {
                LOG.warn("Document {} is partially typed by schema {}: {} unassignable node(s), e.g. {}",
                        document.getURI(), cfg.href(), count, paths.subList(0, Math.min(paths.size(), 5)));
            }
            listener.reset();
        }
    }

    private record TypedKey(short localTypeId, byte[] key) {
        @Override
        public boolean equals(final Object o) {
            return o instanceof TypedKey other && other.localTypeId == localTypeId && Arrays.equals(other.key, key);
        }

        @Override
        public int hashCode() {
            return 31 * localTypeId + Arrays.hashCode(key);
        }
    }

    /**
     * @return the index keys of the typed value of a node, empty if it cannot be built
     */
    private static List<byte[]> valueKeys(final String lexical, final int typeCode) {
        final List<byte[]> keys = new ArrayList<>(1);
        try {
            final Sequence value = TypedValues.fromLexical(lexical, typeCode, null);
            for (final SequenceIterator i = value.iterate(); i.hasNext(); ) {
                final Item item = i.nextItem();
                if (item instanceof Indexable indexable) {
                    try {
                        keys.add(indexable.serializeValue(0));
                    } catch (final EXistException e) {
                        // a type without an index encoding
                    }
                }
            }
        } catch (final XPathException e) {
            // an invalid value (check-values="false"): no typed value entry
        }
        return keys;
    }

    /**
     * Elements without a stored annotation are xs:anyType, attributes xs:untypedAtomic.
     */
    private static boolean isDefault(final TypeAssigner.Assignment a) {
        if (a.nilled()) {
            return false;
        }
        return a.nodeKind() == Node.ELEMENT_NODE ? a.typeCode() == Type.ANY_TYPE : a.typeCode() == Type.UNTYPED_ATOMIC;
    }

    @Override
    public void removeCollection(final Collection collection, final DBBroker broker, final boolean configOnlyReindex) throws PermissionDeniedException {
        try {
            final CollectionMeta meta = index.store().getCollectionMeta(collection.getId());
            if (meta == null) {
                return;
            }
            for (final Iterator<DocumentImpl> i = collection.iteratorNoLock(broker); i.hasNext(); ) {
                index.store().removeDocument(i.next().getDocId(), meta.localTypeIds().values());
            }
            if (!configOnlyReindex) {
                index.dropCollection(collection.getId());
            }
        } catch (final IOException e) {
            LOG.error("Error removing the schema type index entries of collection {}: {}", collection.getURI(), e.getMessage(), e);
        }
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

    // --- TypeAnnotationProvider ---

    @Override
    public @Nullable TypeAnnotation annotationOf(final NodeProxy node) throws XPathException {
        final DocumentImpl doc = node.getOwnerDocument();
        final SchemaTypeConfig cfg = configOf(doc.getCollection());
        if (cfg == null) {
            return null;
        }
        short kind = node.getNodeType();
        if (kind == NodeProxy.UNKNOWN_NODE_TYPE) {
            kind = node.getNode().getNodeType();
        }
        final TypeAnnotation unannotated = kind == Node.ELEMENT_NODE ? TypeAnnotation.ANY_TYPE : TypeAnnotation.UNTYPED_ATOMIC;
        if (kind != Node.ELEMENT_NODE && kind != Node.ATTRIBUTE_NODE) {
            return unannotated;
        }
        try {
            final StoredAnnotation stored = lookup(doc.getDocId(), node.getNodeId());
            if (stored == null) {
                return unannotated;
            }
            final int code = index.table(doc.getCollection().getId(), cfg.schema()).codeOf(stored.localTypeId());
            return code < 0 ? unannotated : new TypeAnnotation(code, stored.nilled());
        } catch (final IOException e) {
            throw new XPathException((org.exist.xquery.Expression) null, "Error reading the schema type index: " + e.getMessage(), e);
        }
    }

    private @Nullable StoredAnnotation lookup(final int docId, final NodeId nodeId) throws IOException {
        final TypeIndexStore store = index.store();
        final long generation = store.generation(docId);
        final CacheKey key = new CacheKey(docId, nodeId.toString());
        final Cached cached = cache.get(key);
        if (cached != null && cached.generation() == generation) {
            return cached.annotation();
        }
        final StoredAnnotation annotation = store.getAnnotation(docId, nodeId);
        cache.put(key, new Cached(generation, annotation));
        return annotation;
    }

    @Override
    public NodeSet nodesOfType(final DocumentSet docs, final int typeCode, final boolean includeDerived, final int nodeKind) throws XPathException {
        final NodeSet result = new NewArrayNodeSet();
        try {
            for (final Iterator<Collection> collections = docs.getCollectionIterator(); collections.hasNext(); ) {
                final Collection collection = collections.next();
                final SchemaTypeConfig cfg = configOf(collection);
                if (cfg == null) {
                    continue;
                }
                final short[] localTypeIds = index.table(collection.getId(), cfg.schema()).matching(typeCode, includeDerived);
                if (localTypeIds.length == 0) {
                    continue;
                }
                // local type ids are per collection: query the documents of this collection only
                final DefaultDocumentSet collectionDocs = new DefaultDocumentSet();
                for (final Iterator<DocumentImpl> i = docs.getDocumentIterator(); i.hasNext(); ) {
                    final DocumentImpl doc = i.next();
                    if (doc.getCollection() != null && doc.getCollection().getId() == collection.getId()) {
                        collectionDocs.add(doc);
                    }
                }
                final NodeSet found = index.store().nodesOfType(collectionDocs, localTypeIds);
                for (final NodeProxy proxy : found) {
                    if (matchesKind(proxy.getNodeType(), nodeKind)) {
                        result.add(proxy);
                    }
                }
            }
        } catch (final IOException e) {
            throw new XPathException((org.exist.xquery.Expression) null, "Error reading the schema type index: " + e.getMessage(), e);
        }
        return result;
    }

    private static boolean matchesKind(final short domKind, final int nodeKind) {
        return switch (nodeKind) {
            case Type.ELEMENT -> domKind == Node.ELEMENT_NODE;
            case Type.ATTRIBUTE -> domKind == Node.ATTRIBUTE_NODE;
            default -> true;
        };
    }

    @Override
    public @Nullable SchemaKey schemaOf(final DocumentImpl doc) throws XPathException {
        final SchemaTypeConfig cfg = configOf(doc.getCollection());
        if (cfg == null) {
            return null;
        }
        try {
            final DocMeta meta = index.store().getDocMeta(doc.getDocId());
            return meta != null ? meta.indexedWith() : cfg.schema().key();
        } catch (final IOException e) {
            throw new XPathException((org.exist.xquery.Expression) null, "Error reading the schema type index: " + e.getMessage(), e);
        }
    }

    // --- helpers ---

    private @Nullable SchemaTypeConfig configOf(@Nullable final Collection collection) {
        if (collection == null) {
            return null;
        }
        final IndexSpec spec = collection.getIndexConfiguration(broker);
        if (spec == null) {
            return null;
        }
        return spec.getCustomIndexSpec(SchemaTypeIndex.ID) instanceof SchemaTypeConfig cfg ? cfg : null;
    }

    SchemaTypeIndex index() {
        return index;
    }
}
