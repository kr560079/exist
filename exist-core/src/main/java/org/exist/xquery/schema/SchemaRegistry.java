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

    private static final String FEATURE_DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String FEATURE_EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";
    private static final String FEATURE_EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities";

    private final Object lock = new Object();
    private int cacheSize;
    private @Nullable LSResourceResolver catalogResolver;
    // guarded by lock; access-ordered so that eldest = least recently used
    private LinkedHashMap<SchemaKey, CompiledSchema> cache;

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
                return size() > cacheSize;
            }
        };
    }

    @Override
    public void configure(final Configuration configuration) {
        synchronized (lock) {
            this.cacheSize = Math.max(1, configuration.getInteger(PROPERTY_CACHE_SIZE, DEFAULT_CACHE_SIZE));
            this.cache = newCache();
        }
        final Object resolver = configuration.getProperty(XMLReaderObjectFactory.CATALOG_RESOLVER);
        this.catalogResolver = resolver instanceof LSResourceResolver ls ? ls : null;
    }

    /**
     * Loads and compiles the schema at {@code href} together with everything it includes or imports.
     *
     * @param broker the broker used to read schema documents from the database
     * @param href the location of the schema, e.g. {@code xmldb:exist:///db/schemas/trade.xsd}
     * @param allowRemote whether {@code http}, {@code https} and {@code ftp} locations may be fetched
     * @return the compiled schema; the cached instance if the same sources were loaded before
     * @throws SchemaLoadException if the schema cannot be read, is not valid or uses a forbidden construct
     */
    public CompiledSchema load(final DBBroker broker, final String href, final boolean allowRemote) throws SchemaLoadException {
        final ChecksummingResourceResolver resolver = new ChecksummingResourceResolver(broker, allowRemote, catalogResolver);
        final LocatingErrorHandler errors = new LocatingErrorHandler();

        XSModel model = null;
        try {
            final XMLSchemaLoader loader = new XMLSchemaLoader();
            setFeature(loader, FEATURE_DISALLOW_DOCTYPE, true);
            setFeature(loader, FEATURE_EXTERNAL_GENERAL_ENTITIES, false);
            setFeature(loader, FEATURE_EXTERNAL_PARAMETER_ENTITIES, false);
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

    private static void setFeature(final XMLSchemaLoader loader, final String feature, final boolean value) {
        try {
            loader.setFeature(feature, value);
        } catch (final RuntimeException e) {
            LOG.warn("Schema loader does not support feature {}: {}", feature, e.getMessage());
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
