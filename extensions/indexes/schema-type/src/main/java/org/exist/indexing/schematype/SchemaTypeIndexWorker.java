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
import org.apache.xerces.xs.XSTypeDefinition;
import org.exist.collections.Collection;
import org.exist.collections.CollectionConfigurationManager;
import org.exist.dom.QName;
import org.exist.dom.persistent.DefaultDocumentSet;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.dom.persistent.ElementImpl;
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
import org.exist.security.Subject;
import org.exist.storage.DBBroker;
import org.exist.storage.IndexSpec;
import org.exist.storage.NodePath;
import org.exist.storage.txn.Txn;
import org.exist.util.DatabaseConfigurationException;
import org.exist.util.Occurrences;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.ErrorCodes;
import org.exist.xquery.QueryRewriter;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.schema.CompiledSchema;
import org.exist.xquery.schema.ImportedSchema;
import org.exist.xquery.schema.SchemaKey;
import org.exist.xquery.schema.SchemaLoadException;
import org.exist.xquery.schema.TypeAnnotation;
import org.exist.xquery.schema.TypeAnnotationProvider;
import org.exist.xquery.schema.TypeAnnotationProvider.StoreCheck;
import org.exist.xquery.schema.TypedValues;
import org.exist.xquery.value.AtomicValue;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.UserDefinedType;
import org.exist.xquery.value.UserTypeRegistry;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** the owner of the collection configuration being loaded, see {@link #setConfigurationOwner} */
    private @Nullable Subject configOwner;
    /** this worker is reindexing documents on its own behalf (an automatic reindex): unassignable nodes do not reject */
    private boolean tolerant;
    /** the current document is being removed: drop its entries on the next flush */
    private boolean removeAllPending;

    /*
     * The configuration of the collection of the last node whose annotation was looked up: atomization asks for the
     * annotation of every node, and looking the configuration up walks the collection's path. Valid while the
     * collection, its path and the generation of the collection configurations are unchanged.
     */
    private @Nullable Collection annotationCollection;
    private @Nullable XmldbURI annotationCollectionUri;
    private long annotationConfigGeneration;
    private @Nullable SchemaTypeConfig annotationConfig;

    private record CacheKey(int docId, String nodeId) {
    }

    /** a cached lookup, valid while the document's generation is unchanged */
    private record Cached(long generation, @Nullable StoredAnnotation annotation) {
    }

    /** cached document metadata, valid while the document's generation is unchanged */
    private record CachedMeta(long generation, @Nullable DocMeta meta) {
    }

    private final Map<Integer, CachedMeta> metaCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Integer, CachedMeta> eldest) {
            return size() > CACHE_SIZE;
        }
    };

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
        // an existing configuration is being (re)loaded: a schema that cannot be loaded does not unconfigure the collection
        return configure(configNodes, namespaces, true);
    }

    @Override
    public void checkConfiguration(final IndexController controller, final NodeList configNodes, final Map<String, String> namespaces)
            throws DatabaseConfigurationException {
        // a configuration is being stored: an unusable schema rejects it
        configure(configNodes, namespaces, false);
    }

    private @Nullable Object configure(final NodeList configNodes, final Map<String, String> namespaces, final boolean lenient)
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
        return SchemaTypeConfig.parse(broker, schemaTypeEl, typedValuesEl, namespaces, lenient, lenient ? configOwner : broker.getCurrentSubject());
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
        // only collections which have entries need a removal; others are skipped without any I/O
        if (mode == ReindexMode.REMOVE_ALL_NODES && document != null && document.getCollection() != null
                && index.hasMeta(document.getCollection().getId())) {
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

    /** true while documents are being reindexed on request, rather than stored, copied, moved or updated */
    boolean isExplicitReindex() {
        return tolerant || broker.getIndexController().isExplicitReindex();
    }

    @Nullable SchemaTypeConfig currentConfig() {
        return config;
    }

    /**
     * The reindex root of a change is the parent element of the changed node (the element itself when its
     * content changes, {@code includeSelf}): the subtree is then reassigned from the parent's stored annotation
     * (see {@link SchemaTypeStreamListener}), and the other indexes only re-stream that subtree.
     */
    @Override
    public <T extends IStoredNode> IStoredNode getReindexRoot(final IStoredNode<T> node, final NodePath path, final boolean insert, final boolean includeSelf) {
        if (configOf(node.getOwnerDocument().getCollection()) == null) {
            return null;
        }
        if (includeSelf && node.getNodeType() == Node.ELEMENT_NODE) {
            return node;
        }
        final IStoredNode parent = node.getParentStoredNode();
        if (parent != null && parent.getNodeType() == Node.ELEMENT_NODE) {
            return parent;
        }
        return node.getNodeType() == Node.ELEMENT_NODE ? node : null;
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
                index.store().removeDocument(document.getDocId());
            }
            switch (listener.kind()) {
                case FULL, SEEDED -> writePass();
                case REMOVE -> removeSubtrees();
                default -> { }
            }
        } catch (final IOException e) {
            LOG.error("Error writing the schema type index: {}", e.getMessage(), e);
            listener.reset();
        }
    }

    private void removeSubtrees() throws IOException {
        final int docId = listener.docId();
        for (final NodeId root : listener.removalRoots()) {
            if (root.getTreeLevel() == 1) {
                index.store().removeAnnotations(docId);
                break;
            }
            index.store().removeNodes(docId, root);
        }
        listener.reset();
    }

    // --- seeds for partial assignment ---

    /**
     * Describe the parent of the root of a seeded pass: its stored annotation, path and in-scope namespaces.
     *
     * @param root an element below the root element, or an attribute
     */
    SchemaTypeStreamListener.Seed seedFor(final IStoredNode<?> root) {
        try {
            final IStoredNode parent = root.getParentStoredNode();
            if (!(parent instanceof ElementImpl element) || config == null || config.schema() == null) {
                return SchemaTypeStreamListener.Seed.UNANNOTATED;
            }
            final StoredAnnotation annotation = index.store().getAnnotation(document.getDocId(), element.getNodeId());
            if (annotation == null) {
                // an unannotated parent (unassignable, skip or lax content, or removed): nothing below is typed
                return SchemaTypeStreamListener.Seed.UNANNOTATED;
            }
            final LocalTypeTable table = index.table(document.getCollection().getId(), config.schema());
            final int code = table.codeOf(annotation.localTypeId());
            final XSTypeDefinition type = code < 0 ? null : definitionOf(code, config);
            if (type == null) {
                return SchemaTypeStreamListener.Seed.UNANNOTATED;
            }
            final Map<QName, Integer> preceding = root.getNodeType() == Node.ELEMENT_NODE
                    ? Map.of(root.getQName(), precedingSameName(element, root)) : Map.of();
            return new SchemaTypeStreamListener.Seed(type, annotation.nilled(), pathOf(element), preceding, inScope(element));
        } catch (final IOException e) {
            LOG.error("Error reading the schema type index: {}", e.getMessage(), e);
            return SchemaTypeStreamListener.Seed.UNANNOTATED;
        }
    }

    private static @Nullable XSTypeDefinition definitionOf(final int code, final SchemaTypeConfig cfg) {
        final UserDefinedType userType = UserTypeRegistry.get(code);
        if (userType != null) {
            return userType.definition();
        }
        final QName name = LocalTypeTable.typeNameOf(code);
        return name == null ? null : cfg.schema().namedType(name).orElse(null);
    }

    /** the path of a stored element, as in {@link TypeAssigner.Assignment#path()} */
    private static String pathOf(final ElementImpl element) {
        final Deque<String> steps = new ArrayDeque<>();
        IStoredNode current = element;
        while (current instanceof ElementImpl e) {
            final IStoredNode parent = e.getParentStoredNode();
            final int position = parent instanceof ElementImpl p ? precedingSameName(p, e) + 1 : 1;
            steps.push(e.getQName().getStringValue() + '[' + position + ']');
            current = parent;
        }
        final StringBuilder path = new StringBuilder();
        for (final String step : steps) {
            path.append('/').append(step);
        }
        return path.toString();
    }

    /** whether a stored element has an element sibling with its name */
    boolean hasSameNameSibling(final ElementImpl element) {
        if (!(element.getParentStoredNode() instanceof ElementImpl parent)) {
            return false;
        }
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final IStoredNode<?> sibling = (IStoredNode<?>) children.item(i);
            if (sibling.getNodeType() == Node.ELEMENT_NODE && !sibling.getNodeId().equals(element.getNodeId())
                    && sibling.getQName().equals(element.getQName())) {
                return true;
            }
        }
        return false;
    }

    /** the number of element children of {@code parent} before {@code child} with the same name as {@code child} */
    private static int precedingSameName(final ElementImpl parent, final IStoredNode<?> child) {
        final NodeList children = parent.getChildNodes();
        int count = 0;
        for (int i = 0; i < children.getLength(); i++) {
            final IStoredNode<?> sibling = (IStoredNode<?>) children.item(i);
            if (sibling.getNodeId().equals(child.getNodeId())) {
                break;
            }
            if (sibling.getNodeType() == Node.ELEMENT_NODE && sibling.getQName().equals(child.getQName())) {
                count++;
            }
        }
        return count;
    }

    /** the namespaces in scope at a stored element */
    private static Map<String, String> inScope(final ElementImpl element) {
        final Map<String, String> scope = new HashMap<>();
        IStoredNode current = element;
        while (current instanceof ElementImpl e) {
            for (final Iterator<String> prefixes = e.getPrefixes(); prefixes.hasNext(); ) {
                final String prefix = prefixes.next();
                scope.putIfAbsent(prefix, e.getNamespaceForPrefix(prefix));
            }
            current = e.getParentStoredNode();
        }
        return scope;
    }

    // --- writing ---

    private void writePass() throws IOException {
        final SchemaTypeConfig cfg = listener.config();
        final int docId = listener.docId();
        final int collectionId = document.getCollection().getId();
        final boolean full = listener.kind() == SchemaTypeStreamListener.PassKind.FULL;
        final List<SchemaTypeStreamListener.Pending> work = listener.takePending();

        // the stable ids of the types to persist; types without one are left unannotated
        final Map<Integer, String> typeIds = new HashMap<>();
        final List<String> unassignable = new ArrayList<>();
        for (final SchemaTypeStreamListener.Pending p : work) {
            final TypeAssigner.Assignment a = p.assignment();
            if (a.status() == TypeAssigner.Status.UNASSIGNABLE) {
                unassignable.add(a.path());
            } else if (!typeIds.containsKey(a.typeCode())) {
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
        // the document's entries are empty from here on: a full pass's first write after clearing (or finding none)
        boolean empty = false;
        if (listener.takeClear()) {
            empty = full;
            if (full) {
                if (store.mayHaveEntries(docId)) {
                    store.removeAnnotations(docId);
                }
            } else {
                store.removeNodes(docId, listener.rootId());
            }
        }

        final Map<Short, List<Member>> members = new LinkedHashMap<>();
        final Map<TypedKey, List<Member>> typedValues = new LinkedHashMap<>();
        // the name summaries: flags and types by name
        final Map<TypeIndexStore.NameKey, Byte> summaryFlags = new HashMap<>(listener.takeNames());
        final Map<TypeIndexStore.NameKey, Set<Short>> summaryTypes = new HashMap<>();
        final Map<TypeIndexStore.NameKey, List<Member>> untyped = new HashMap<>(listener.takeUntyped());
        for (final SchemaTypeStreamListener.Pending p : work) {
            final TypeAssigner.Assignment a = p.assignment();
            final Short localTypeId = a.status() == TypeAssigner.Status.ANNOTATED && typeIds.containsKey(a.typeCode())
                    ? table.localOf(a.typeCode()) : null;
            if (localTypeId == null) {
                // an unassignable node is listed by the listener, with the untyped subtree it roots
                if (p.name() != null && a.status() != TypeAssigner.Status.UNASSIGNABLE) {
                    untyped.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(new Member(a.nodeId(), (short) a.nodeKind()));
                }
                continue;
            }
            if (p.name() != null) {
                summaryTypes.computeIfAbsent(p.name(), k -> new HashSet<>()).add(localTypeId);
            }
            store.putAnnotation(docId, a.nodeId(), localTypeId, a.nilled());
            final Member member = new Member(a.nodeId(), (short) a.nodeKind());
            members.computeIfAbsent(localTypeId, k -> new ArrayList<>()).add(member);
            if (!a.nilled()) {
                final QName typeName = LocalTypeTable.typeNameOf(a.typeCode());
                if (typeName != null && cfg.typedValueTypes().contains(typeName)) {
                    // a nilled node's typed value is empty: no entry; a value which was not collected cannot be found
                    final List<byte[]> keys = p.lexical() == null ? null : valueKeys(p.lexical(), a.typeCode());
                    if (keys == null) {
                        // the comparisons over this name in this document cannot be answered from the index
                        if (p.name() != null) {
                            untyped.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(member);
                        }
                        continue;
                    }
                    for (final byte[] key : keys) {
                        final List<Member> list = typedValues.computeIfAbsent(new TypedKey(localTypeId, key), k -> new ArrayList<>());
                        // a list value may repeat an item
                        if (list.isEmpty() || list.getLast() != member) {
                            list.add(member);
                        }
                    }
                }
            }
        }
        for (final Map.Entry<Short, List<Member>> entry : members.entrySet()) {
            store.addTypeMembers(entry.getKey(), docId, entry.getValue());
        }
        for (final Map.Entry<TypedKey, List<Member>> entry : typedValues.entrySet()) {
            store.putTypedValues(docId, entry.getKey().localTypeId(), entry.getKey().key(), entry.getValue());
        }
        for (final TypeIndexStore.NameKey name : summaryTypes.keySet()) {
            summaryFlags.putIfAbsent(name, (byte) 0);
        }
        for (final Map.Entry<TypeIndexStore.NameKey, List<Member>> entry : untyped.entrySet()) {
            summaryFlags.putIfAbsent(entry.getKey(), (byte) 0);
            store.addUntyped(docId, entry.getKey(), entry.getValue());
        }
        for (final Map.Entry<TypeIndexStore.NameKey, Byte> entry : summaryFlags.entrySet()) {
            store.mergeSummary(docId, entry.getKey(), entry.getValue(), summaryTypes.getOrDefault(entry.getKey(), Set.of()), !empty);
        }
        listener.recordUnassignable(unassignable);

        if (listener.rootEnded()) {
            final List<String> paths = listener.unassignablePaths();
            final int count = listener.unassignableCount();
            if (full) {
                store.putDocMeta(docId, new DocMeta(cfg.schema().key(),
                        count > 0 ? DocMeta.DocStatus.PARTIAL : DocMeta.DocStatus.TYPED, paths, cfg.typedValuesFingerprint()));
            } else if (listener.rootPath() != null) {
                updateDocMeta(docId, listener.rootPath(), paths, count);
            }
            if (count > 0) {
                LOG.warn("Document {} is partially typed by schema {}: {} unassignable node(s), e.g. {}",
                        document.getURI(), cfg.href(), count, paths.subList(0, Math.min(paths.size(), 5)));
            }
            listener.reset();
        }
    }

    /**
     * After a seeded pass: replace the unassignable paths recorded below the subtree root by the new ones.
     */
    private void updateDocMeta(final int docId, final String rootPath, final List<String> newPaths, final int newCount)
            throws IOException {
        final DocMeta meta = index.store().getDocMeta(docId);
        if (meta == null) {
            // not indexed as a whole (stale, see Task 9): leave it so
            return;
        }
        // a full list may have dropped paths: it cannot become TYPED again
        final boolean saturated = meta.unassignablePaths().size() >= DocMeta.MAX_PATHS;
        final List<String> paths = new ArrayList<>();
        for (final String path : meta.unassignablePaths()) {
            if (!(path.equals(rootPath) || path.startsWith(rootPath + "/"))) {
                paths.add(path);
            }
        }
        paths.addAll(newPaths);
        final boolean partial = saturated || newCount > newPaths.size() || !paths.isEmpty();
        final DocMeta updated = new DocMeta(meta.indexedWith(), partial ? DocMeta.DocStatus.PARTIAL : DocMeta.DocStatus.TYPED, paths,
                meta.typedValues());
        if (!updated.equals(meta)) {
            index.store().putDocMeta(docId, updated);
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
     * @return the typed keys ({@link TypedKeys}) of the items of the typed value of a node (none for an empty
     *         list), or null if the value cannot be built or an item has no key
     */
    private static @Nullable List<byte[]> valueKeys(final String lexical, final int typeCode) {
        final List<byte[]> keys = new ArrayList<>(1);
        try {
            final Sequence value = TypedValues.fromLexical(lexical, typeCode, null);
            for (final SequenceIterator i = value.iterate(); i.hasNext(); ) {
                final Item item = i.nextItem();
                final byte[] key = item instanceof AtomicValue atomic ? TypedKeys.encode(atomic) : null;
                if (key == null) {
                    // e.g. a value of a type without encoding, or too large
                    return null;
                }
                keys.add(key);
            }
        } catch (final XPathException e) {
            // an invalid value (check-values="false"): comparisons raise an error the index cannot
            return null;
        }
        return keys;
    }

    @Override
    public void removeCollection(final Collection collection, final DBBroker broker, final boolean configOnlyReindex) throws PermissionDeniedException {
        // collections which never had entries are skipped without any I/O
        if (!index.hasMeta(collection.getId())) {
            return;
        }
        try {
            for (final Iterator<DocumentImpl> i = collection.iteratorNoLock(broker); i.hasNext(); ) {
                index.store().removeDocument(i.next().getDocId());
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
        return new SchemaTypeQueryRewriter(context);
    }

    // --- TypeAnnotationProvider ---

    /**
     * The configuration of a collection was stored with a schema whose key differs from the one its documents were
     * indexed with: with {@code reindex-on-schema-change} schedule the reindex, to run as the storing user once the
     * store is done. It must not run here: the caller holds the lock of the configuration collection, and taking the
     * lock of the data collection while holding it is the opposite of the order a store into the data collection uses.
     */
    @Override
    public void configurationStored(final DBBroker broker, final Txn txn, final XmldbURI dataCollectionUri) {
        try {
            final Collection collection = broker.getCollection(dataCollectionUri);
            final SchemaTypeConfig cfg = configOf(collection);
            if (cfg == null || !cfg.reindexOnSchemaChange() || cfg.schema() == null) {
                return;
            }
            final CollectionMeta meta = index.store().getCollectionMeta(collection.getId());
            if (meta == null || meta.schema().equals(cfg.schema().key())) {
                return;
            }
            // only once the configuration is committed; if the store is aborted there is nothing to reindex
            final SchemaKey key = cfg.schema().key();
            final org.exist.security.Subject storingUser = broker.getCurrentSubject();
            txn.registerListener(new org.exist.storage.txn.TxnListener() {
                @Override
                public void commit() {
                    index.scheduleReindex(collection, key, storingUser);
                }

                @Override
                public void abort() {
                    // dropped
                }
            });
        } catch (final Exception e) {
            LOG.error("Error scheduling the reindex of collection {} after its configuration changed: {}", dataCollectionUri, e.getMessage(), e);
        }
    }

    @Override
    public void setConfigurationOwner(@Nullable final Subject owner) {
        configOwner = owner;
    }

    @Override
    public @Nullable StoreCheck storeCheck(final Collection collection, final XmldbURI name) {
        final SchemaTypeConfig cfg = configOf(collection);
        return cfg == null ? null : new SchemaTypeStoreCheck(this, collection, name, cfg, broker.getBrokerPool().getNodeFactory());
    }

    @Override
    public @Nullable TypeAnnotation annotationOf(final NodeProxy node) throws XPathException {
        final DocumentImpl doc = node.getOwnerDocument();
        final SchemaTypeConfig cfg = annotationConfigOf(doc.getCollection());
        if (cfg == null) {
            return null;
        }
        ensureUsable(doc, cfg);
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
            return code < 0 ? unannotated : TypeAnnotation.assessed(code, stored.nilled());
        } catch (final IOException e) {
            throw new XPathException((org.exist.xquery.Expression) null, "Error reading the schema type index: " + e.getMessage(), e);
        }
    }

    /**
     * {@link #configOf(Collection)} for {@link #annotationOf(NodeProxy)}, cached: no lookup (and no allocation) for
     * the nodes of a collection after the first while the configurations are unchanged.
     */
    private @Nullable SchemaTypeConfig annotationConfigOf(@Nullable final Collection collection) {
        if (collection == null) {
            return null;
        }
        final CollectionConfigurationManager manager = broker.getBrokerPool().getConfigurationManager();
        final long generation = manager == null ? 0 : manager.getGeneration();
        if (collection == annotationCollection && collection.getURI() == annotationCollectionUri
                && generation == annotationConfigGeneration) {
            return annotationConfig;
        }
        final SchemaTypeConfig cfg = configOf(collection);
        annotationCollection = collection;
        annotationCollectionUri = collection.getURI();
        annotationConfigGeneration = generation;
        annotationConfig = cfg;
        return cfg;
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
                ensureAvailable(collection, cfg);
                final short[] localTypeIds = index.table(collection.getId(), cfg.schema()).matching(typeCode, includeDerived);
                // local type ids are per collection: query the documents of this collection only
                final DefaultDocumentSet collectionDocs = new DefaultDocumentSet();
                for (final Iterator<DocumentImpl> i = docs.getDocumentIterator(); i.hasNext(); ) {
                    final DocumentImpl doc = i.next();
                    if (doc.getCollection() != null && doc.getCollection().getId() == collection.getId()) {
                        ensureUsable(doc, cfg);
                        collectionDocs.add(doc);
                    }
                }
                if (localTypeIds.length == 0) {
                    continue;
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

    /**
     * The candidate nodes of a typed step answered from the index: the nodes of the documents annotated with the type
     * or a type derived from it. The index can answer only if every document is in a collection with a usable schema
     * and its entries are current, and only for a user-defined type of the collection's schema; otherwise this
     * returns null and the caller evaluates the step as usual, which raises the errors ({@code EXSA0001},
     * {@code EXSA0002}, {@code EXSA0003}) the step raises.
     *
     * @param docs     the documents
     * @param typeCode the {@link Type} code of the type
     * @param nodeKind {@link Type#ELEMENT} or {@link Type#ATTRIBUTE}
     * @return the nodes, in document order, or null if the index cannot answer
     * @throws XPathException if the index cannot be read
     */
    @Nullable NodeSet candidatesOfType(final DocumentSet docs, final int typeCode, final int nodeKind) throws XPathException {
        final UserDefinedType requested = typeCode >= UserTypeRegistry.USER_TYPE_BASE ? UserTypeRegistry.get(typeCode) : null;
        // the documents by collection, in one pass
        final Map<Integer, DefaultDocumentSet> docsByCollection = new LinkedHashMap<>();
        final Map<Integer, Collection> collections = new HashMap<>();
        for (final Iterator<DocumentImpl> i = docs.getDocumentIterator(); i.hasNext(); ) {
            final DocumentImpl doc = i.next();
            final Collection collection = doc.getCollection();
            if (collection == null) {
                return null;
            }
            collections.putIfAbsent(collection.getId(), collection);
            docsByCollection.computeIfAbsent(collection.getId(), k -> new DefaultDocumentSet()).add(doc);
        }
        final List<NodeSet> found = new ArrayList<>();
        try {
            for (final Map.Entry<Integer, DefaultDocumentSet> entry : docsByCollection.entrySet()) {
                final Collection collection = collections.get(entry.getKey());
                final SchemaTypeConfig cfg = configOf(collection);
                if (cfg == null) {
                    // a collection without schema: its nodes are untyped, which only the step itself knows
                    return null;
                }
                try {
                    for (final Iterator<DocumentImpl> i = entry.getValue().getDocumentIterator(); i.hasNext(); ) {
                        ensureUsable(i.next(), cfg);
                    }
                } catch (final XPathException e) {
                    return null;
                }
                if (requested != null && requested.name() != null && !cfg.schema().key().equals(requested.schema())) {
                    // another version of the schema: EXSA0001
                    return null;
                }
                final short[] localTypeIds = index.table(collection.getId(), cfg.schema()).matching(typeCode, true);
                if (localTypeIds.length > 0) {
                    found.add(index.store().nodesOfType(entry.getValue(), localTypeIds));
                }
            }
        } catch (final IOException e) {
            throw new XPathException((org.exist.xquery.Expression) null, "Error reading the schema type index: " + e.getMessage(), e);
        }
        final NodeSet result = new NewArrayNodeSet();
        for (final NodeSet nodes : found) {
            for (final NodeProxy proxy : nodes) {
                if (matchesKind(proxy.getNodeType(), nodeKind)) {
                    result.add(proxy);
                }
            }
        }
        return result;
    }

    /**
     * The documents of one collection, with the collection's configuration and local type table.
     */
    record TypedScope(Collection collection, DefaultDocumentSet docs, SchemaTypeConfig config, LocalTypeTable table) {
    }

    /**
     * The scopes of a lookup in the typed-value table, one per collection of the documents; null if the table cannot
     * answer for every document: a document in a collection without schema or typed values, a document the schema
     * type index cannot answer for ({@code EXSA0002}, {@code EXSA0003}), or one whose typed values were written for
     * other typed-value types than the collection's current ones.
     *
     * @throws XPathException if the index cannot be read
     */
    @Nullable List<TypedScope> typedScopes(final DocumentSet docs) throws XPathException {
        final Map<Integer, DefaultDocumentSet> docsByCollection = new LinkedHashMap<>();
        final Map<Integer, Collection> collections = new HashMap<>();
        for (final Iterator<DocumentImpl> i = docs.getDocumentIterator(); i.hasNext(); ) {
            final DocumentImpl doc = i.next();
            final Collection collection = doc.getCollection();
            if (collection == null) {
                return null;
            }
            collections.putIfAbsent(collection.getId(), collection);
            docsByCollection.computeIfAbsent(collection.getId(), k -> new DefaultDocumentSet()).add(doc);
        }
        final List<TypedScope> scopes = new ArrayList<>(docsByCollection.size());
        try {
            for (final Map.Entry<Integer, DefaultDocumentSet> entry : docsByCollection.entrySet()) {
                final Collection collection = collections.get(entry.getKey());
                final SchemaTypeConfig cfg = configOf(collection);
                if (cfg == null || cfg.typedValueTypes().isEmpty()) {
                    return null;
                }
                final String fingerprint = cfg.typedValuesFingerprint();
                for (final Iterator<DocumentImpl> i = entry.getValue().getDocumentIterator(); i.hasNext(); ) {
                    final DocumentImpl doc = i.next();
                    try {
                        ensureUsable(doc, cfg);
                    } catch (final XPathException e) {
                        return null;
                    }
                    final DocMeta meta = docMeta(doc.getDocId());
                    if (meta == null || !fingerprint.equals(meta.typedValues())) {
                        return null;
                    }
                }
                scopes.add(new TypedScope(collection, entry.getValue(), cfg, index.table(collection.getId(), cfg.schema())));
            }
        } catch (final IOException e) {
            throw new XPathException((org.exist.xquery.Expression) null, "Error reading the schema type index: " + e.getMessage(), e);
        }
        return scopes;
    }

    /** whether some collection is configured with typed values (else no comparison is worth rewriting) */
    boolean hasTypedValueConfigurations() {
        final CollectionConfigurationManager manager = broker.getBrokerPool().getConfigurationManager();
        if (manager == null) {
            return false;
        }
        for (final Object spec : manager.getCustomIndexSpecsByCollection(SchemaTypeIndex.ID).values()) {
            if (spec instanceof SchemaTypeConfig cfg && !cfg.typedValueTypes().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** the tables of the index */
    TypeIndexStore store() {
        return index.store();
    }

    private static boolean matchesKind(final short domKind, final int nodeKind) {
        return switch (nodeKind) {
            case Type.ELEMENT -> domKind == Node.ELEMENT_NODE;
            case Type.ATTRIBUTE -> domKind == Node.ATTRIBUTE_NODE;
            default -> true;
        };
    }

    @Override
    public @Nullable ImportedSchema configuredSchema(final String namespaceUri) throws XPathException {
        final Resolution resolution = resolveConfigured(namespaceUri);
        if (resolution == null) {
            return null;
        }
        try {
            return loadSchemaWithDependencies(resolution.cfg())
                    .configuredOn(resolution.collection(), namespaceUri, resolution.key());
        } catch (final SchemaLoadException e) {
            LOG.debug("Unable to load the schema configured on {} for import schema: {}", resolution.collections(), e.getMessage());
            // only collections the user may read are listed
            throw new XPathException((org.exist.xquery.Expression) null, ErrorCodes.XQST0059,
                    "Configured schema unavailable for namespace '" + namespaceUri + "' on " + resolution.collections());
        }
    }

    @Override
    public @Nullable SchemaKey configuredSchemaKey(final String namespaceUri) throws XPathException {
        final Resolution resolution = resolveConfigured(namespaceUri);
        return resolution == null ? null : resolution.key();
    }

    @Override
    public @Nullable SchemaKey configuredSchemaKeyOf(final XmldbURI collectionUri) {
        final CollectionConfigurationManager manager = broker.getBrokerPool().getConfigurationManager();
        if (manager == null) {
            return null;
        }
        final Object spec = manager.getCustomIndexSpecsByCollection(SchemaTypeIndex.ID).get(collectionUri);
        return spec instanceof SchemaTypeConfig cfg && cfg.schema() != null ? cfg.schema().key() : null;
    }

    /**
     * The configured schema of a namespace for an import schema without hint.
     *
     * @param key the key of the configured schema
     * @param cfg a configuration with that schema
     * @param collection the first collection configured with it
     * @param collections the readable collections configured with it
     */
    private record Resolution(SchemaKey key, SchemaTypeConfig cfg, XmldbURI collection, List<String> collections) {
    }

    /**
     * Resolve the configured schema of a namespace, as the broker's current subject: only configurations of
     * collections it may read count.
     *
     * @return the resolution, or null if no readable configuration has a schema for the namespace
     * @throws XPathException {@code XQST0059} if readable configurations have different schemas for it
     */
    private @Nullable Resolution resolveConfigured(final String namespaceUri) throws XPathException {
        final CollectionConfigurationManager manager = broker.getBrokerPool().getConfigurationManager();
        if (manager == null) {
            return null;
        }
        final Subject subject = broker.getCurrentSubject();
        final Map<SchemaKey, SchemaTypeConfig> byKey = new java.util.LinkedHashMap<>();
        final Map<SchemaKey, XmldbURI> firstCollection = new java.util.LinkedHashMap<>();
        final Map<SchemaKey, List<String>> collectionsByKey = new java.util.LinkedHashMap<>();
        for (final Map.Entry<XmldbURI, Object> entry : manager.getCustomIndexSpecsByCollection(SchemaTypeIndex.ID).entrySet()) {
            if (!(entry.getValue() instanceof SchemaTypeConfig cfg) || cfg.schema() == null
                    || !cfg.schema().targetNamespaces().contains(namespaceUri) || !readable(entry.getKey(), subject)) {
                continue;
            }
            final SchemaKey key = cfg.schema().key();
            byKey.putIfAbsent(key, cfg);
            firstCollection.putIfAbsent(key, entry.getKey());
            collectionsByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(entry.getKey().getCollectionPath());
        }
        if (byKey.isEmpty()) {
            return null;
        }
        if (byKey.size() > 1) {
            throw new XPathException((org.exist.xquery.Expression) null, ErrorCodes.XQST0059,
                    "The schema for namespace '" + namespaceUri + "' is ambiguous: collections configure different schemas for it "
                            + collectionsByKey.values());
        }
        final SchemaKey key = byKey.keySet().iterator().next();
        return new Resolution(key, byKey.get(key), firstCollection.get(key), collectionsByKey.get(key));
    }

    /** whether the subject may read the collection */
    private boolean readable(final XmldbURI collectionUri, final Subject subject) {
        try (final Collection collection = broker.openCollection(collectionUri, org.exist.storage.lock.Lock.LockMode.READ_LOCK)) {
            return collection != null && collection.getPermissionsNoLock().validate(subject, org.exist.security.Permission.READ);
        } catch (final PermissionDeniedException e) {
            return false;
        }
    }

    @Override
    public @Nullable SchemaKey schemaOf(final DocumentImpl doc) throws XPathException {
        final SchemaTypeConfig cfg = configOf(doc.getCollection());
        if (cfg == null) {
            return null;
        }
        ensureAvailable(doc.getCollection(), cfg);
        try {
            final DocMeta meta = index.store().getDocMeta(doc.getDocId());
            return meta != null ? meta.indexedWith() : cfg.schema().key();
        } catch (final IOException e) {
            throw new XPathException((org.exist.xquery.Expression) null, "Error reading the schema type index: " + e.getMessage(), e);
        }
    }

    // --- availability and staleness (spec 8.1, 8.4) ---

    /**
     * Check that the collection's schema can still be loaded; called when a document is about to be indexed.
     * The registry answers from its memo unless a schema document changed, so this is cheap.
     *
     * @throws SchemaTypeException {@code EXSA0002} if it cannot
     */
    void verifySchemaAvailable(final SchemaTypeConfig cfg) {
        verifySchemaAvailable(document.getCollection(), cfg);
    }

    /** what the store check of the document being stored verified (one verification per store, not two) */
    private record StoreVerified(SchemaTypeConfig cfg, int collectionId, XmldbURI name) {
    }

    private @Nullable StoreVerified storeVerified;

    /** the store check verified {@code cfg} for the document {@code name} about to be stored into the collection */
    void verifiedForStore(final Collection collection, final XmldbURI name, final SchemaTypeConfig cfg) {
        storeVerified = new StoreVerified(cfg, collection.getId(), name);
    }

    /**
     * @return true if the store check verified {@code cfg} for the current document a moment ago, during the same
     * store (the record is used once)
     */
    boolean takeVerifiedForStore(final SchemaTypeConfig cfg) {
        final StoreVerified verified = storeVerified;
        storeVerified = null;
        return verified != null && verified.cfg() == cfg && document != null
                && verified.collectionId() == document.getCollection().getId()
                && verified.name().equals(document.getFileURI());
    }

    void verifySchemaAvailable(final Collection collection, final SchemaTypeConfig cfg) {
        final int collectionId = collection.getId();
        try {
            cfg.recover(loadSchema(cfg));
            index.markAvailable(collectionId);
        } catch (final SchemaLoadException e) {
            final String message = "the schema " + cfg.href() + " of collection " + collection.getURI()
                    + " is unavailable: " + e.getMessage();
            index.markUnavailable(collectionId, message);
            if (cfg.logOutageOnce()) {
                LOG.error(message, e);
            }
            // the schema's location, the collection and the cause only go to a caller who may read the collection
            if (!mayRead(collection)) {
                throw new SchemaTypeException(ErrorCodes.EXSA0002, GENERIC_UNAVAILABLE);
            }
            throw new SchemaTypeException(ErrorCodes.EXSA0002, message, e);
        }
    }

    /** the message of {@code EXSA0002} for a caller who may not read the collection: no location, collection or cause */
    private static final String GENERIC_UNAVAILABLE = "the schema of the collection is unavailable";

    /** as {@link #loadSchema(SchemaTypeConfig)}, with the sources of the schema */
    private ImportedSchema loadSchemaWithDependencies(final SchemaTypeConfig cfg) throws SchemaLoadException {
        final Subject owner = cfg.loadAs();
        if (owner == null) {
            throw new SchemaLoadException("the owner of the collection configuration document cannot be determined");
        }
        broker.pushSubject(owner);
        try {
            return broker.getBrokerPool().getSchemaRegistry().loadWithDependencies(broker, cfg.href(), cfg.allowRemote());
        } finally {
            broker.popSubject();
        }
    }

    /** load the configured schema (from the registry's memo unless a schema document changed) as the owner of the configuration */
    private CompiledSchema loadSchema(final SchemaTypeConfig cfg) throws SchemaLoadException {
        final Subject owner = cfg.loadAs();
        if (owner == null) {
            throw new SchemaLoadException("the owner of the collection configuration document cannot be determined");
        }
        broker.pushSubject(owner);
        try {
            return broker.getBrokerPool().getSchemaRegistry().load(broker, cfg.href(), cfg.allowRemote());
        } finally {
            broker.popSubject();
        }
    }

    /**
     * Raise {@code EXSA0002} if the collection's schema is unavailable, trying to load it again.
     */
    private void ensureAvailable(final Collection collection, final SchemaTypeConfig cfg) throws XPathException {
        final int collectionId = collection.getId();
        if (cfg.schema() != null && !(index.hasUnavailable() && index.unavailableReason(collectionId) != null)) {
            return;
        }
        final String known = index.unavailableReason(collectionId) != null ? index.unavailableReason(collectionId) : cfg.unavailableReason();
        if (!index.mayRetryLoad(collectionId)) {
            // looked a moment ago: do not hit the registry on every node
            throw unavailable(collection, known != null ? known
                    : "the schema " + cfg.href() + " of collection " + collection.getURI() + " is unavailable");
        }
        try {
            cfg.recover(loadSchema(cfg));
            index.markAvailable(collectionId);
        } catch (final SchemaLoadException e) {
            final String reason = index.unavailableReason(collectionId);
            throw unavailable(collection, reason != null ? reason
                    : cfg.unavailableReason() != null ? cfg.unavailableReason()
                    : "the schema " + cfg.href() + " of collection " + collection.getURI() + " is unavailable: " + e.getMessage());
        }
    }

    /**
     * {@code EXSA0002} with the detailed reason (it names the schema's location, the collection and the cause) only for
     * a caller who may read the collection.
     */
    private XPathException unavailable(final Collection collection, final String detailed) {
        return new XPathException((org.exist.xquery.Expression) null, ErrorCodes.EXSA0002,
                mayRead(collection) ? detailed : GENERIC_UNAVAILABLE);
    }

    /**
     * Raise {@code EXSA0002} if the schema of the document's collection is unavailable and {@code EXSA0003} if the
     * document was not indexed with the collection's current schema (or not at all). With
     * {@code reindex-on-schema-change} the collection is reindexed first.
     *
     * <p>The common case costs a map lookup and a key comparison against a cached {@link DocMeta}.</p>
     */
    private void ensureUsable(final DocumentImpl doc, final SchemaTypeConfig cfg) throws XPathException {
        ensureAvailable(doc.getCollection(), cfg);
        try {
            final SchemaKey current = cfg.schema().key();
            if (!isStale(doc, current)) {
                return;
            }
            final boolean pending = cfg.reindexOnSchemaChange()
                    && index.scheduleReindex(doc.getCollection(), current, cfg.loadAs());
            final DocMeta meta = docMeta(doc.getDocId());
            // the collection and the schemas are only named to a caller who may read the collection
            final boolean detailed = mayRead(doc.getCollection());
            final String message;
            if (!detailed) {
                message = "The schema type index of the document " + doc.getFileURI() + " is stale"
                        + (pending ? "; a reindex of the collection is pending, retry shortly"
                        : "; ask the owner of the collection to reindex it");
            } else {
                message = "The schema type index of the document " + doc.getURI()
                        + (meta == null ? " has no entries for the document, which was stored before the collection was configured"
                        : " was built with schema " + meta.indexedWith().sha256() + " but the collection now uses " + current.sha256())
                        + (pending ? "; a reindex of the collection is pending, retry shortly"
                        : "; reindex the collection with xmldb:reindex('" + doc.getCollection().getURI() + "')");
            }
            throw new XPathException((org.exist.xquery.Expression) null, ErrorCodes.EXSA0003, message);
        } catch (final IOException e) {
            throw new XPathException((org.exist.xquery.Expression) null, "Error reading the schema type index: " + e.getMessage(), e);
        }
    }

    /** whether the current subject may read the collection */
    boolean mayRead(@Nullable final Collection collection) {
        return collection != null
                && collection.getPermissionsNoLock().validate(broker.getCurrentSubject(), org.exist.security.Permission.READ);
    }

    /**
     * The state of a document's type index, for diagnostics.
     *
     * @param status      {@code typed}, {@code partial}, {@code stale}, {@code unavailable} (the schema cannot be
     *                    loaded) or {@code untyped}
     * @param schema      the checksum of the schema the document was indexed with, or null if it has no entries
     * @param unassignable the recorded paths of the unassignable subtrees
     */
    public record Status(String status, @Nullable String schema, List<String> unassignable) {
    }

    /**
     * Report the state of a document's type index without raising the errors a typed lookup does for a stale index.
     *
     * @param doc the document
     * @return the state; {@code untyped} if the document's collection is not configured with a schema,
     *     {@code unavailable} (no schema, no paths) if the collection's schema cannot be loaded
     * @throws XPathException if the index cannot be read
     */
    public Status statusOf(final DocumentImpl doc) throws XPathException {
        final SchemaTypeConfig cfg = configOf(doc.getCollection());
        if (cfg == null) {
            return new Status("untyped", null, List.of());
        }
        try {
            ensureAvailable(doc.getCollection(), cfg);
        } catch (final XPathException e) {
            if (e.getErrorCode() == ErrorCodes.EXSA0002) {
                return new Status("unavailable", null, List.of());
            }
            throw e;
        }
        try {
            final DocMeta meta = docMeta(doc.getDocId());
            if (meta == null) {
                return new Status("stale", null, List.of());
            }
            final String state = !meta.indexedWith().equals(cfg.schema().key()) ? "stale"
                    : meta.status() == DocMeta.DocStatus.PARTIAL ? "partial" : "typed";
            return new Status(state, meta.indexedWith().sha256(), meta.unassignablePaths());
        } catch (final IOException e) {
            throw new XPathException((org.exist.xquery.Expression) null, "Error reading the schema type index: " + e.getMessage(), e);
        }
    }

    private boolean isStale(final DocumentImpl doc, final SchemaKey current) throws IOException {
        final DocMeta meta = docMeta(doc.getDocId());
        return meta == null || !meta.indexedWith().equals(current);
    }

    /** the document's metadata, cached while the document's generation is unchanged */
    private @Nullable DocMeta docMeta(final int docId) throws IOException {
        final TypeIndexStore store = index.store();
        final long generation = store.generation(docId);
        final CachedMeta cached = metaCache.get(docId);
        if (cached != null && cached.generation() == generation) {
            return cached.meta();
        }
        final DocMeta meta = store.getDocMeta(docId);
        metaCache.put(docId, new CachedMeta(generation, meta));
        return meta;
    }

    // --- automatic reindex ---

    /**
     * Rebuild this index's entries for the documents governed by the schema-type configuration of a collection: the
     * collection itself and the descendants that inherit its configuration (those with a collection.xconf of their
     * own are left alone). Only this index is touched. Unassignable nodes do not reject here. A document that cannot
     * be read or indexed is skipped with a warning.
     *
     * @return the number of documents (or collections) that could not be reindexed, including those left out when the
     *     database began to shut down
     */
    int reindexGoverned(final XmldbURI collectionUri, final Txn txn) {
        try (final Collection root = broker.openCollection(collectionUri, org.exist.storage.lock.Lock.LockMode.READ_LOCK)) {
            final SchemaTypeConfig cfg = root == null ? null : configOf(root);
            if (cfg == null) {
                return 0;
            }
            return reindexTree(root, cfg, txn);
        } catch (final PermissionDeniedException e) {
            LOG.warn("Not reindexing collection {}: {}", collectionUri, e.getMessage());
            return 1;
        }
    }

    private int reindexTree(final Collection collection, final SchemaTypeConfig cfg, final Txn txn) {
        int failures = 0;
        final List<DocumentImpl> docs = new ArrayList<>();
        final List<XmldbURI> children = new ArrayList<>();
        try {
            for (final Iterator<DocumentImpl> i = collection.iterator(broker); i.hasNext(); ) {
                docs.add(i.next());
            }
            for (final Iterator<XmldbURI> i = collection.collectionIterator(broker); i.hasNext(); ) {
                children.add(collection.getURI().append(i.next()));
            }
        } catch (final PermissionDeniedException | org.exist.util.LockException e) {
            LOG.warn("Not reindexing the documents of collection {}: {}", collection.getURI(), e.getMessage());
            return 1;
        }
        tolerant = true;
        try {
            for (final DocumentImpl doc : docs) {
                if (broker.getBrokerPool().isShuttingDownOrDown()) {
                    return failures + 1;
                }
                // a document being updated is locked for write: wait for it rather than racing it
                try (final org.exist.storage.lock.ManagedDocumentLock ignored =
                             broker.getBrokerPool().getLockManager().acquireDocumentReadLock(doc.getURI())) {
                    reindexDocument(doc, txn);
                } catch (final Exception e) {
                    LOG.warn("Skipping document {} in the reindex after a schema change: {}", doc.getURI(), e.getMessage(), e);
                    failures++;
                }
            }
        } finally {
            tolerant = false;
            setDocument(null);
        }
        for (final XmldbURI childUri : children) {
            if (broker.getBrokerPool().isShuttingDownOrDown()) {
                return failures + 1;
            }
            try (final Collection child = broker.openCollection(childUri, org.exist.storage.lock.Lock.LockMode.READ_LOCK)) {
                // a descendant with a collection.xconf of its own has its own configuration: not governed by this one
                if (child == null) {
                    LOG.warn("Skipping collection {} in the reindex after a schema change: it was removed", childUri);
                    failures++;
                } else if (configOf(child) == cfg) {
                    failures += reindexTree(child, cfg, txn);
                } else {
                    LOG.info("Skipping collection {} in the reindex after a schema change: it has a configuration of its own", childUri);
                }
            } catch (final PermissionDeniedException e) {
                LOG.warn("Skipping collection {} in the reindex after a schema change: {}", childUri, e.getMessage());
                failures++;
            }
        }
        return failures;
    }

    private void reindexDocument(final DocumentImpl doc, final Txn txn) throws Exception {
        setDocument(doc, ReindexMode.STORE);
        final StreamListener streamListener = getListener();
        if (streamListener == null) {
            return;
        }
        streamListener.startIndexDocument(txn);
        try {
            final NodeList nodes = doc.getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                IndexUtils.scanNode(broker, txn, (IStoredNode<?>) nodes.item(i), streamListener);
            }
        } finally {
            streamListener.endIndexDocument(txn);
        }
        flush();
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
