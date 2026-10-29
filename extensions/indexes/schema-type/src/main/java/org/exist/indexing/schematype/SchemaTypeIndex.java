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
import org.exist.backup.RawDataBackup;
import org.exist.indexing.AbstractIndex;
import org.exist.indexing.IndexWorker;
import org.exist.indexing.RawBackupSupport;
import org.exist.security.Subject;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.btree.BTree;
import org.exist.storage.btree.DBException;
import org.exist.storage.index.BFile;
import org.exist.storage.lock.ManagedLock;
import org.exist.storage.txn.Txn;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.schema.SchemaKey;
import org.exist.util.DatabaseConfigurationException;
import org.exist.util.FileUtils;
import org.exist.util.LockException;
import org.exist.xquery.schema.CompiledSchema;
import org.exist.xquery.schema.TypeAnnotationProvider;
import org.w3c.dom.Element;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Index module for schema-aware XQuery: per-collection schema configuration, type annotations of stored nodes
 * and typed values (see {@link TypeIndexStore} for the storage layout).
 */
public class SchemaTypeIndex extends AbstractIndex implements RawBackupSupport {

    public static final String ID = TypeAnnotationProvider.INDEX_ID;

    public static final short FILE_FORMAT_VERSION_ID = 1;

    private static final Logger LOG = LogManager.getLogger(SchemaTypeIndex.class);
    private static final String DEFAULT_FILE = "schema-type.dbx";

    public static final class Factory implements org.exist.indexing.IndexFactory {
        @Override
        public String getDefaultId() {
            return "schema-type-index";
        }

        @Override
        public Class<? extends AbstractIndex> getIndexClass() {
            return SchemaTypeIndex.class;
        }
    }

    private Path dataFile;
    private BFile db;
    private TypeIndexStore store;

    /** the resolved local type tables, by collection id */
    private final ConcurrentHashMap<Integer, LocalTypeTable> tables = new ConcurrentHashMap<>();
    private final Object tableLock = new Object();
    /** the ids of the collections with metadata, i.e. with entries; lets unconfigured collections skip all I/O */
    private final Set<Integer> collectionsWithMeta = ConcurrentHashMap.newKeySet();
    /** collections whose schema failed to load, with the reason: a store failed with EXSA0002 */
    private final ConcurrentHashMap<Integer, String> unavailable = new ConcurrentHashMap<>();
    /** unstable type ids already reported */
    private final Set<String> reportedUnstable = ConcurrentHashMap.newKeySet();
    /** the automatic reindex pending or last failed, per collection; updated atomically */
    private final ConcurrentHashMap<Integer, ReindexState> reindexes = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger scheduledReindexCount = new java.util.concurrent.atomic.AtomicInteger();
    private java.util.concurrent.ExecutorService reindexExecutor;
    /** when a collection's schema was last looked up while it was unavailable (milliseconds), to rate-limit retries */
    private final ConcurrentHashMap<Integer, Long> lastLoadAttempt = new ConcurrentHashMap<>();

    /** how soon a queried collection with an unavailable schema tries to load it again */
    static final long UNAVAILABLE_RETRY_MILLIS = 30_000;
    /** how soon a failed automatic reindex is scheduled again by a stale query */
    static final long DEFAULT_REINDEX_RETRY_MILLIS = 60_000;
    volatile long reindexRetryMillis = DEFAULT_REINDEX_RETRY_MILLIS;
    /** test seam: the next automatic reindex fails */
    volatile boolean failNextReindex;

    /** failedAt is 0 while the reindex for the key is pending, else when it failed */
    private record ReindexState(SchemaKey schema, long failedAt) {
    }

    @Override
    public void configure(final BrokerPool pool, final Path dataDir, final Element config) throws DatabaseConfigurationException {
        super.configure(pool, dataDir, config);
        String fileName = DEFAULT_FILE;
        if (config != null && config.hasAttribute("file")) {
            fileName = config.getAttribute("file");
        }
        dataFile = dataDir.resolve(fileName);
    }

    @Override
    public void open() throws DatabaseConfigurationException {
        try {
            db = new BFile(pool, (byte) 0, FILE_FORMAT_VERSION_ID, false, dataFile, pool.getCacheManager(), 1.4, 0.07);
        } catch (final DBException e) {
            throw new DatabaseConfigurationException("Failed to create index file: " + dataFile.toAbsolutePath() + ": " + e.getMessage());
        }
        store = new TypeIndexStore(db, pool.getLockManager(), pool.getNodeFactory());
        try {
            collectionsWithMeta.addAll(store.collectionIdsWithMeta());
        } catch (final IOException e) {
            throw new DatabaseConfigurationException("Failed to read index file: " + dataFile.toAbsolutePath() + ": " + e.getMessage());
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Opened schema type index: {}", dataFile.toAbsolutePath());
        }
    }

    @Override
    public void close() throws DBException {
        synchronized (this) {
            if (reindexExecutor != null) {
                reindexExecutor.shutdownNow();
                reindexExecutor = null;
            }
        }
        if (db != null) {
            db.close();
        }
        tables.clear();
        unavailable.clear();
        collectionsWithMeta.clear();
    }

    @Override
    public void sync() throws DBException {
        if (db != null) {
            // as the core indexes: a concurrent write must not change a page while it is being written out
            try (final ManagedLock<ReentrantLock> lock = pool.getLockManager().acquireBtreeWriteLock(db.getLockName())) {
                db.flush();
            } catch (final LockException e) {
                throw new DBException("Failed to lock the schema type index for sync: " + e.getMessage());
            }
        }
    }

    @Override
    public void remove() throws DBException {
        if (db != null) {
            db.closeAndRemove();
        }
        tables.clear();
        unavailable.clear();
        collectionsWithMeta.clear();
    }

    @Override
    public String getIndexId() {
        return ID;
    }

    @Override
    public boolean checkIndex(final DBBroker broker) {
        return true;
    }

    @Override
    public IndexWorker getWorker(final DBBroker broker) {
        return new SchemaTypeIndexWorker(broker, this);
    }

    @Override
    public BTree getStorage() {
        return db;
    }

    TypeIndexStore store() {
        return store;
    }

    /**
     * Get the local type table of a collection, resolved against its current schema.
     */
    LocalTypeTable table(final int collectionId, final CompiledSchema schema) throws IOException {
        final LocalTypeTable cached = tables.get(collectionId);
        if (cached != null && cached.schema() == schema) {
            return cached;
        }
        synchronized (tableLock) {
            final LocalTypeTable again = tables.get(collectionId);
            if (again != null && again.schema() == schema) {
                return again;
            }
            CollectionMeta meta = store.getCollectionMeta(collectionId);
            if (meta == null) {
                meta = new CollectionMeta(schema.key(), Map.of());
            }
            final LocalTypeTable table = new LocalTypeTable(meta, schema);
            tables.put(collectionId, table);
            return table;
        }
    }

    /**
     * Allocate local ids for the given type ids (append-only) and record the collection's schema key,
     * persisting the collection metadata if it changed.
     *
     * @return the resulting table
     */
    LocalTypeTable allocate(final int collectionId, final CompiledSchema schema, final Collection<String> typeIds) throws IOException {
        synchronized (tableLock) {
            final LocalTypeTable current = table(collectionId, schema);
            final CollectionMeta meta = current.meta();
            // collectionsWithMeta tracks every collection whose metadata is stored: no need to read it to know
            boolean changed = !meta.schema().equals(schema.key())
                    || (!collectionsWithMeta.contains(collectionId) && store.getCollectionMeta(collectionId) == null);
            final Map<String, Short> known = meta.localTypeIds();
            Map<String, Short> ids = null;
            short next = 0;
            for (final String typeId : typeIds) {
                if (!known.containsKey(typeId) && (ids == null || !ids.containsKey(typeId))) {
                    if (ids == null) {
                        ids = new LinkedHashMap<>(known);
                        next = meta.nextLocalTypeId();
                    } else if (next == Short.MAX_VALUE) {
                        throw new IllegalStateException("Local type id table is full");
                    } else {
                        next++;
                    }
                    ids.put(typeId, next);
                    changed = true;
                }
            }
            if (!changed) {
                return current;
            }
            if (ids == null) {
                ids = new LinkedHashMap<>(known);
            }
            final CollectionMeta updated = new CollectionMeta(schema.key(), ids);
            store.putCollectionMeta(collectionId, updated);
            collectionsWithMeta.add(collectionId);
            final LocalTypeTable table = new LocalTypeTable(updated, schema);
            tables.put(collectionId, table);
            return table;
        }
    }

    /**
     * Forget the metadata of a removed collection.
     */
    void dropCollection(final int collectionId) throws IOException {
        synchronized (tableLock) {
            store.removeCollectionMeta(collectionId);
            collectionsWithMeta.remove(collectionId);
            tables.remove(collectionId);
        }
    }

    /**
     * @return whether the collection has metadata, and so possibly entries; answered from memory
     */
    boolean hasMeta(final int collectionId) {
        return collectionsWithMeta.contains(collectionId);
    }

    void markAvailable(final int collectionId) {
        if (!unavailable.isEmpty()) {
            unavailable.remove(collectionId);
        }
        lastLoadAttempt.remove(collectionId);
    }

    /**
     * @return true if the collection was not marked before (so the failure is logged once)
     */
    boolean markUnavailable(final int collectionId, final String reason) {
        lastLoadAttempt.put(collectionId, System.currentTimeMillis());
        return unavailable.put(collectionId, reason) == null;
    }

    boolean hasUnavailable() {
        return !unavailable.isEmpty();
    }

    @Nullable String unavailableReason(final int collectionId) {
        return unavailable.get(collectionId);
    }

    /**
     * Schedule the asynchronous reindex of a collection whose schema key changed, at most once per
     * (collection, schema key) while it is pending, and again only after a failure and a back-off. It runs on a
     * background thread as {@code subject} (the owner of the collection configuration, or for a stored configuration
     * the storing user), never as the user whose query found the index stale.
     *
     * @return true if a reindex for this key is pending (scheduled now or before), false if none is
     */
    boolean scheduleReindex(final org.exist.collections.Collection collection, final SchemaKey key, @Nullable final Subject subject) {
        if (subject == null || pool.isShuttingDownOrDown()) {
            return false;
        }
        final int collectionId = collection.getId();
        final long now = System.currentTimeMillis();
        final boolean[] pending = {false};
        final boolean[] submit = {false};
        reindexes.compute(collectionId, (id, current) -> {
            if (current != null && current.schema().equals(key)) {
                if (current.failedAt() == 0) {
                    pending[0] = true;
                    return current;
                }
                if (now - current.failedAt() < reindexRetryMillis) {
                    return current;     // failed recently: not pending, not retried yet
                }
            }
            pending[0] = true;
            submit[0] = true;
            return new ReindexState(key, 0);
        });
        if (submit[0]) {
            scheduledReindexCount.incrementAndGet();
            final XmldbURI uri = collection.getURI();
            try {
                synchronized (this) {
                    if (reindexExecutor == null) {
                        reindexExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                            final Thread thread = new Thread(pool.getThreadGroup(), r, "schema-type-reindex");
                            thread.setDaemon(true);
                            return thread;
                        });
                    }
                    reindexExecutor.execute(() -> reindex(collectionId, key, uri, subject));
                }
            } catch (final java.util.concurrent.RejectedExecutionException e) {
                finishReindex(collectionId, key, true);
                return false;
            }
        }
        return pending[0];
    }

    private void reindex(final int collectionId, final SchemaKey key, final XmldbURI uri, final Subject subject) {
        boolean failed = true;
        try {
            if (pool.isShuttingDownOrDown()) {
                return;
            }
            if (failNextReindex) {
                failNextReindex = false;
                throw new IllegalStateException("reindex failure forced by a test");
            }
            try (final DBBroker broker = pool.get(Optional.of(subject));
                 final Txn txn = pool.getTransactionManager().beginTransaction()) {
                LOG.info("The schema of collection {} changed: reindexing its schema type index", uri);
                final SchemaTypeIndexWorker worker = (SchemaTypeIndexWorker) broker.getIndexController().getWorkerByIndexId(ID);
                final int skipped = worker.reindexGoverned(uri, txn);
                txn.commit();
                failed = skipped > 0;
                if (failed) {
                    LOG.warn("The reindex of collection {} left out {} document(s) or collection(s)", uri, skipped);
                }
            }
        } catch (final Exception e) {
            LOG.error("Error reindexing collection {} after a schema change: {}", uri, e.getMessage(), e);
        } finally {
            finishReindex(collectionId, key, failed);
        }
    }

    private void finishReindex(final int collectionId, final SchemaKey key, final boolean failed) {
        reindexes.computeIfPresent(collectionId, (id, current) -> {
            if (!current.schema().equals(key)) {
                return current;     // superseded by a reindex for another key
            }
            return failed ? new ReindexState(key, System.currentTimeMillis()) : null;
        });
    }

    /**
     * @return true if the schema of a collection that is unavailable may be looked up again now (at most every
     *     {@link #UNAVAILABLE_RETRY_MILLIS}); records the attempt
     */
    boolean mayRetryLoad(final int collectionId) {
        final long now = System.currentTimeMillis();
        final boolean[] allowed = {false};
        lastLoadAttempt.compute(collectionId, (id, last) -> {
            if (last == null || now - last >= UNAVAILABLE_RETRY_MILLIS) {
                allowed[0] = true;
                return now;
            }
            return last;
        });
        return allowed[0];
    }

    /** @return true if no automatic reindex is pending (for tests) */
    boolean reindexIdle() {
        return reindexes.values().stream().noneMatch(state -> state.failedAt() == 0);
    }

    /** @return how many automatic reindexes were scheduled (for tests) */
    int scheduledReindexCount() {
        return scheduledReindexCount.get();
    }

    /**
     * @return true the first time an unstable type id is reported
     */
    boolean reportUnstable(final String typeId) {
        return reportedUnstable.add(typeId);
    }

    @Override
    public void backupToArchive(final RawDataBackup backup) throws IOException {
        // do not use try-with-resources here, closing the OutputStream will close the entire backup
        try {
            final OutputStream os = backup.newEntry(FileUtils.fileName(db.getFile()));
            db.backupToStream(os);
        } finally {
            backup.closeEntry();
        }
    }
}
