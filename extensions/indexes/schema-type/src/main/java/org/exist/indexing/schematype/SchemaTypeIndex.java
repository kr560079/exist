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
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.btree.BTree;
import org.exist.storage.btree.DBException;
import org.exist.storage.index.BFile;
import org.exist.util.DatabaseConfigurationException;
import org.exist.util.FileUtils;
import org.exist.xquery.schema.CompiledSchema;
import org.exist.xquery.schema.TypeAnnotationProvider;
import org.w3c.dom.Element;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    /** unstable type ids already reported */
    private final Set<String> reportedUnstable = ConcurrentHashMap.newKeySet();

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
        if (db != null) {
            db.close();
        }
        tables.clear();
        collectionsWithMeta.clear();
    }

    @Override
    public void sync() throws DBException {
        if (db != null) {
            db.flush();
        }
    }

    @Override
    public void remove() throws DBException {
        if (db != null) {
            db.closeAndRemove();
        }
        tables.clear();
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
            boolean changed = !meta.schema().equals(schema.key()) || store.getCollectionMeta(collectionId) == null;
            final Map<String, Short> ids = new LinkedHashMap<>(meta.localTypeIds());
            for (final String typeId : typeIds) {
                if (!ids.containsKey(typeId)) {
                    final short next = new CollectionMeta(meta.schema(), ids).nextLocalTypeId();
                    ids.put(typeId, next);
                    changed = true;
                }
            }
            if (!changed) {
                return current;
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
