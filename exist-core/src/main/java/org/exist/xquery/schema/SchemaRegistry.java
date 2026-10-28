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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.xerces.impl.xs.XMLSchemaLoader;
import org.apache.xerces.xni.parser.XMLErrorHandler;
import org.apache.xerces.xni.parser.XMLParseException;
import org.apache.xerces.xs.XSModel;
import org.exist.storage.BrokerPoolService;
import org.exist.storage.DBBroker;
import org.exist.util.Configuration;
import org.exist.util.XMLReaderObjectFactory;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Loads XML Schemas from the database and the XML catalog, compiles them into
 * {@link CompiledSchema}s and caches them by the SHA-256 of their sources, so that the
 * store-time index module and {@code import schema} share one compiled instance.
 *
 * <p>Nothing is loaded eagerly: the registry is empty until the first {@link #load} call.
 * The cache is a bounded LRU (access order) whose size is set by
 * {@code <schema-registry cache-size="8"/>} under {@code <xquery>} in {@code conf.xml}.</p>
 */
public class SchemaRegistry implements BrokerPoolService {

    private static final Logger LOG = LogManager.getLogger(SchemaRegistry.class);

    /** Configuration property holding the maximum number of cached compiled schemas. */
    public static final String PROPERTY_CACHE_SIZE = "xquery.schema-registry.cache-size";
    public static final int DEFAULT_CACHE_SIZE = 8;
    /** Configuration property: server-wide gate that must be on before any collection may load remote schema documents. */
    public static final String PROPERTY_ALLOW_REMOTE = "xquery.schema-registry.allow-remote";

    private static final String FEATURE_DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String FEATURE_EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";
    private static final String FEATURE_EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities";

    private final Object lock = new Object();
    private int cacheSize;
    private @Nullable LSResourceResolver catalogResolver;
    // guarded by lock; the server-level gate for remote schema access
    private boolean remoteGate;
    // guarded by lock; access-ordered so that eldest = least recently used
    private LinkedHashMap<SchemaKey, CompiledSchema> cache;
    // guarded by lock; remembers what a (location, allowRemote) resolved to, so unchanged schemas are not re-parsed
    private LinkedHashMap<MemoKey, MemoEntry> memo = newMemo();
    private final java.util.concurrent.atomic.AtomicInteger parseCount = new java.util.concurrent.atomic.AtomicInteger();

    public SchemaRegistry() {
        this(DEFAULT_CACHE_SIZE);
    }

    SchemaRegistry(final int cacheSize) {
        this.cacheSize = Math.max(1, cacheSize);
        this.cache = newCache();
    }

    private LinkedHashMap<SchemaKey, CompiledSchema> newCache() {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<SchemaKey, CompiledSchema> eldest) {
                if (size() > cacheSize) {
                    memo.values().removeIf(m -> m.key.equals(eldest.getKey()));
                    return true;
                }
                return false;
            }
        };
    }

    private LinkedHashMap<MemoKey, MemoEntry> newMemo() {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<MemoKey, MemoEntry> eldest) {
                return size() > cacheSize;
            }
        };
    }

    private record MemoKey(String href, boolean allowRemote) {
    }

    /** The key a location resolved to, and the version of every database document it depended on. */
    private record MemoEntry(SchemaKey key, Map<String, Long> versions) {
        boolean isCurrent(final DBBroker broker) {
            for (final Map.Entry<String, Long> dep : versions.entrySet()) {
                if (ChecksummingResourceResolver.databaseVersion(broker, dep.getKey()) != dep.getValue()) {
                    return false;
                }
            }
            return true;
        }
    }

    /** @return how many full schema parses have been performed (for tests) */
    int parseCount() {
        return parseCount.get();
    }

    @Override
    public void configure(final Configuration configuration) {
        synchronized (lock) {
            this.cacheSize = Math.max(1, configuration.getInteger(PROPERTY_CACHE_SIZE, DEFAULT_CACHE_SIZE));
            this.memo = newMemo();
            this.cache = newCache();
            this.remoteGate = Boolean.TRUE.equals(configuration.getProperty(PROPERTY_ALLOW_REMOTE));
        }
        final Object resolver = configuration.getProperty(XMLReaderObjectFactory.CATALOG_RESOLVER);
        this.catalogResolver = resolver instanceof LSResourceResolver ls ? ls : null;
    }

    /**
     * Loads and compiles the schema at {@code href} together with everything it includes or imports.
     *
     * @param broker the broker used to read schema documents from the database
     * @param href the location of the schema, e.g. {@code xmldb:exist:///db/schemas/trade.xsd}
     * @param allowRemote whether the caller asks for {@code http}, {@code https} and {@code ftp} locations to be fetched;
     *     honoured only if the server-level {@code allow-remote} gate is on
     * @return the compiled schema; the cached instance if the same sources were loaded before
     * @throws SchemaLoadException if the schema cannot be read, is not valid or uses a forbidden construct
     */
    public CompiledSchema load(final DBBroker broker, final String href, final boolean allowRemote) throws SchemaLoadException {
        final boolean gate;
        synchronized (lock) {
            gate = remoteGate;
        }
        final boolean effectiveAllowRemote = allowRemote && gate;
        final MemoKey memoKey = new MemoKey(href, effectiveAllowRemote);
        final MemoEntry memoEntry;
        synchronized (lock) {
            memoEntry = memo.get(memoKey);
        }
        if (memoEntry != null && memoEntry.isCurrent(broker)) {
            synchronized (lock) {
                final CompiledSchema cached = cache.get(memoEntry.key);
                if (cached != null) {
                    return cached;
                }
            }
        }
        parseCount.incrementAndGet();
        final ChecksummingResourceResolver resolver = new ChecksummingResourceResolver(broker, effectiveAllowRemote, allowRemote && !gate, catalogResolver);
        final LocatingErrorHandler errors = new LocatingErrorHandler();

        XSModel model = null;
        try {
            final XMLSchemaLoader loader = new XMLSchemaLoader();
            setFeature(loader, FEATURE_DISALLOW_DOCTYPE, true);
            // The schema loader does not recognise the external-entity features; they cannot matter
            // because DOCTYPE declarations (hence entity declarations) are rejected above. If a
            // future Xerces does recognise them, they must be set, and failing to do so is fatal.
            final java.util.List<String> recognized = java.util.Arrays.asList(loader.getRecognizedFeatures());
            if (recognized.contains(FEATURE_EXTERNAL_GENERAL_ENTITIES)) {
                setFeature(loader, FEATURE_EXTERNAL_GENERAL_ENTITIES, false);
            }
            if (recognized.contains(FEATURE_EXTERNAL_PARAMETER_ENTITIES)) {
                setFeature(loader, FEATURE_EXTERNAL_PARAMETER_ENTITIES, false);
            }
            loader.setErrorHandler(errors);
            loader.setParameter("resource-resolver", resolver);
            final LSInput root = resolver.resolveRoot(href);
            model = loader.load(root);
        } catch (final ChecksummingResourceResolver.ResolutionException e) {
            throw toException(e);
        } catch (final RuntimeException e) {
            if (resolver.failure() != null) {
                throw toException(resolver.failure());
            }
            if (errors.first != null) {
                throw errors.first;
            }
            throw new SchemaLoadException("unable to load schema " + href + ": " + e.getMessage(), href, -1, -1, e);
        }

        if (resolver.failure() != null) {
            throw toException(resolver.failure());
        }
        if (errors.first != null) {
            throw errors.first;
        }
        if (model == null) {
            throw new SchemaLoadException("unable to load schema " + href, href, -1, -1, null);
        }

        final SchemaKey key = checksum(resolver.documents());
        synchronized (lock) {
            if (resolver.uncheckable()) {
                memo.remove(memoKey);
            } else {
                memo.put(memoKey, new MemoEntry(key, Map.copyOf(resolver.databaseVersions())));
            }
            final CompiledSchema cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        final CompiledSchema compiled = CompiledSchema.compile(key, model);
        synchronized (lock) {
            final CompiledSchema raced = cache.putIfAbsent(key, compiled);
            return raced != null ? raced : compiled;
        }
    }

    /**
     * @param key the key of a schema
     * @return the cached schema, if it is still cached; counts as a use for the LRU
     */
    public Optional<CompiledSchema> get(final SchemaKey key) {
        synchronized (lock) {
            return Optional.ofNullable(cache.get(key));
        }
    }

    /**
     * @param namespaceUri a target namespace
     * @return the most recently used cached schema that declares {@code namespaceUri} as a target namespace
     */
    public Optional<CompiledSchema> loadedForNamespace(final String namespaceUri) {
        synchronized (lock) {
            CompiledSchema found = null;
            for (final CompiledSchema schema : cache.values()) {   // iteration runs least to most recently used
                if (schema.targetNamespaces().contains(namespaceUri)) {
                    found = schema;
                }
            }
            return Optional.ofNullable(found);
        }
    }

    /** Fails closed: a security feature that cannot be set means the schema must not be loaded. */
    private static void setFeature(final XMLSchemaLoader loader, final String feature, final boolean value) throws SchemaLoadException {
        try {
            loader.setFeature(feature, value);
        } catch (final RuntimeException e) {
            throw new SchemaLoadException("unable to configure secure schema loading (" + feature + "): " + e.getMessage(), null, -1, -1, e);
        }
    }

    private static SchemaLoadException toException(final ChecksummingResourceResolver.ResolutionException e) {
        return new SchemaLoadException(e.getMessage(), e.systemId(), -1, -1, e);
    }

    private static SchemaKey checksum(final Map<String, byte[]> documents) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (final byte[] bytes : documents.values()) {   // a TreeMap: ordered by system ID
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).flip());
                digest.update(bytes);
            }
            return new SchemaKey(HexFormat.of().formatHex(digest.digest()));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Remembers the first error (or fatal error), with its location, and aborts loading. */
    private static final class LocatingErrorHandler implements XMLErrorHandler {
        @Nullable SchemaLoadException first;

        @Override
        public void warning(final String domain, final String key, final XMLParseException e) {
            LOG.debug("Schema warning: {}", e.getMessage());
        }

        @Override
        public void error(final String domain, final String key, final XMLParseException e) {
            record(e);
        }

        @Override
        public void fatalError(final String domain, final String key, final XMLParseException e) {
            record(e);
        }

        private void record(final XMLParseException e) {
            if (first == null) {
                first = new SchemaLoadException(e.getMessage(), e.getExpandedSystemId(), e.getLineNumber(), e.getColumnNumber(), e);
            }
        }
    }
}
