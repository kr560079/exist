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

import org.apache.xerces.dom.DOMInputImpl;
import org.exist.collections.Collection;
import org.exist.dom.persistent.BinaryDocument;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.dom.persistent.LockedDocument;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.DBBroker;
import org.exist.storage.lock.Lock.LockMode;
import org.exist.storage.serializers.Serializer;
import org.exist.util.LockException;
import org.exist.xmldb.XmldbURI;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;

import javax.annotation.Nullable;
import javax.xml.transform.OutputKeys;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Resolves the documents that make up a schema (the root document plus any includes, imports
 * and redefines) from the database ({@code xmldb:exist://} URIs) and from the XML catalog, and
 * remembers the bytes of every document it hands to Xerces so that the caller can compute a
 * checksum over the complete set of sources.
 *
 * <p>Instances are single use and not thread safe: create one per schema load.</p>
 */
class ChecksummingResourceResolver implements LSResourceResolver {

    private static final String XMLDB_EXIST_PREFIX = "xmldb:exist://";
    private static final String XMLDB_PREFIX = "xmldb://";

    /** Unchecked carrier used to get a failure through the Xerces call stack. */
    static final class ResolutionException extends RuntimeException {
        private final @Nullable String systemId;

        ResolutionException(final String message, @Nullable final String systemId, @Nullable final Throwable cause) {
            super(message, cause);
            this.systemId = systemId;
        }

        @Nullable String systemId() {
            return systemId;
        }
    }

    private final DBBroker broker;
    private final boolean allowRemote;
    private final boolean disabledByServer;
    private final @Nullable LSResourceResolver catalogResolver;
    private final Map<String, byte[]> documents = new TreeMap<>();
    private @Nullable ResolutionException failure;
    private final Map<String, Long> databaseVersions = new java.util.HashMap<>();
    private final java.util.Set<String> catalogIds = new java.util.HashSet<>();
    private boolean uncheckable;

    ChecksummingResourceResolver(final DBBroker broker, final boolean allowRemote, final boolean disabledByServer,
            @Nullable final LSResourceResolver catalogResolver) {
        this.broker = broker;
        this.allowRemote = allowRemote;
        this.disabledByServer = disabledByServer;
        this.catalogResolver = catalogResolver;
    }

    /** @return the bytes of every resolved document, keyed (and ordered) by system ID */
    Map<String, byte[]> documents() {
        return documents;
    }

    /** @return database document id to last-modified time for every database document resolved */
    Map<String, Long> databaseVersions() {
        return databaseVersions;
    }

    /** @return true if some dependency (catalog, file or remote) cannot be cheaply re-validated */
    boolean uncheckable() {
        return uncheckable;
    }

    /**
     * The last-modified times of the database documents of a schema, grouped by collection with their URIs parsed
     * once, so that checking whether they are still current opens each collection once (instead of parsing,
     * resolving and opening the collection of every document on every check).
     */
    static final class DatabaseVersions {

        private record Dependency(XmldbURI name, long version) {
        }

        /** collection URI to the documents in it; null if some id is not a database document */
        private final @Nullable Map<XmldbURI, List<Dependency>> byCollection;

        DatabaseVersions(final Map<String, Long> versions) {
            Map<XmldbURI, List<Dependency>> groups = new LinkedHashMap<>();
            for (final Map.Entry<String, Long> version : versions.entrySet()) {
                final String path = stripXmldbScheme(version.getKey());
                if (path == null) {
                    // never current, as databaseVersion(): -1
                    groups = null;
                    break;
                }
                XmldbURI uri;
                try {
                    uri = XmldbURI.create(path).toCollectionPathURI();
                } catch (final IllegalArgumentException e) {
                    groups = null;
                    break;
                }
                if (!uri.startsWith(XmldbURI.ROOT_COLLECTION_URI)) {
                    uri = XmldbURI.ROOT_COLLECTION_URI.append(uri);
                }
                groups.computeIfAbsent(uri.removeLastSegment(), k -> new ArrayList<>())
                        .add(new Dependency(uri.lastSegment(), version.getValue()));
            }
            this.byCollection = groups;
        }

        /**
         * @return true if every document still exists, is readable by the current subject (as for
         * {@link #databaseVersion}) and has the recorded last-modified time
         */
        boolean areCurrent(final DBBroker broker) {
            if (byCollection == null) {
                return false;
            }
            for (final Map.Entry<XmldbURI, List<Dependency>> group : byCollection.entrySet()) {
                try (final Collection collection = broker.openCollection(group.getKey(), LockMode.READ_LOCK)) {
                    if (collection == null) {
                        return false;
                    }
                    for (final Dependency dependency : group.getValue()) {
                        try (final LockedDocument locked = collection.getDocumentWithLock(broker, dependency.name(), LockMode.READ_LOCK)) {
                            if (locked == null || locked.getDocument().getLastModified() != dependency.version()) {
                                return false;
                            }
                        }
                    }
                } catch (final PermissionDeniedException | LockException | RuntimeException e) {
                    return false;
                }
            }
            return true;
        }
    }

    /** @return the last-modified time of the database document {@code id}, or -1 if it is not readable */
    static long databaseVersion(final DBBroker broker, final String id) {
        final String path = stripXmldbScheme(id);
        if (path == null) {
            return -1;
        }
        try (final LockedDocument locked = broker.getXMLResource(XmldbURI.create(path), LockMode.READ_LOCK)) {
            return locked == null ? -1 : locked.getDocument().getLastModified();
        } catch (final PermissionDeniedException | RuntimeException e) {
            return -1;
        }
    }

    /** @return the first resolution failure, if any occurred */
    @Nullable ResolutionException failure() {
        return failure;
    }

    /**
     * Resolves the root document of a schema.
     *
     * @param href the location of the schema
     * @return the input to hand to the schema loader
     * @throws ResolutionException if the document cannot be resolved
     */
    LSInput resolveRoot(final String href) {
        final LSInput input = resolveResource(null, null, null, href, null);
        if (input == null) {
            throw fail(new ResolutionException("schema not found: " + href, href, null));
        }
        return input;
    }

    @Override
    public @Nullable LSInput resolveResource(final String type, @Nullable final String namespaceURI, @Nullable final String publicId,
            @Nullable final String systemId, @Nullable final String baseURI) {
        try {
            return doResolve(type, namespaceURI, publicId, systemId, baseURI);
        } catch (final ResolutionException e) {
            throw fail(e);
        }
    }

    private ResolutionException fail(final ResolutionException e) {
        if (failure == null) {
            failure = e;
        }
        return e;
    }

    private @Nullable LSInput doResolve(@Nullable final String type, @Nullable final String namespaceURI, @Nullable final String publicId,
            @Nullable final String systemId, @Nullable final String baseURI) {
        if (systemId == null) {
            // e.g. an xs:import with no schemaLocation: nothing to load from here
            return null;
        }

        // 1) database documents
        final String dbPath = toDatabasePath(systemId, baseURI);
        if (dbPath != null) {
            final String id = XMLDB_EXIST_PREFIX + dbPath;
            return record(id, publicId, readFromDatabase(dbPath, id));
        }

        // 2) the XML catalog
        if (catalogResolver != null) {
            final LSInput catalogued = catalogResolver.resolveResource(type, namespaceURI, publicId, systemId, baseURI);
            if (catalogued != null) {
                return recordCatalogued(catalogued, systemId, publicId);
            }
        }

        // 3) direct fetch: only http(s), and only when remote access is enabled; or a relative
        //    reference from a document that the catalog supplied
        final String absolute = absolutize(systemId, baseURI);
        final String scheme = schemeOf(absolute);
        final boolean http = "http".equals(scheme) || "https".equals(scheme);
        final boolean relativeToCatalogued = baseURI != null && catalogIds.contains(baseURI) && schemeOf(systemId) == null
                && "file".equals(scheme);
        if (http) {
            checkRemote(absolute);
        } else if (!relativeToCatalogued) {
            throw new ResolutionException("schema location not permitted (only database, catalog and, if enabled, http(s) sources are allowed): " + absolute, absolute, null);
        }
        try (final InputStream is = openRemote(absolute)) {
            uncheckable = true;
            return record(absolute, publicId, is.readAllBytes());
        } catch (final IOException | IllegalArgumentException e) {
            throw new ResolutionException("unable to read schema document " + absolute + ": " + e.getMessage(), absolute, e);
        }
    }

    /**
     * Resolves the root document of a schema from the XML catalog alone, by its target namespace; the namespace
     * is never fetched as a location.
     *
     * @param namespaceUri the target namespace
     * @return the input to hand to the schema loader
     * @throws ResolutionException if the catalog has no schema for the namespace
     */
    LSInput resolveRootFromCatalog(final String namespaceUri) {
        try {
            final LSInput catalogued = catalogResolver == null ? null
                    : catalogResolver.resolveResource(javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI, namespaceUri, null, null, null);
            if (catalogued == null || catalogued.getSystemId() == null) {
                throw new ResolutionException("no schema for namespace " + namespaceUri + " in the catalog", null, null);
            }
            return recordCatalogued(catalogued, catalogued.getSystemId(), null);
        } catch (final ResolutionException e) {
            throw fail(e);
        } catch (final RuntimeException e) {
            throw fail(new ResolutionException("unable to resolve namespace " + namespaceUri + " in the catalog: " + e.getMessage(), null, e));
        }
    }

    private LSInput recordCatalogued(final LSInput catalogued, final String systemId, @Nullable final String publicId) {
        final String id = catalogued.getSystemId() != null ? catalogued.getSystemId() : systemId;
        final String catalogDbPath = toDatabasePath(id, null);
        if (catalogDbPath != null) {
            final String dbId = XMLDB_EXIST_PREFIX + catalogDbPath;
            return record(dbId, publicId, readFromDatabase(catalogDbPath, dbId));
        }
        checkRemote(id);
        catalogIds.add(id);
        uncheckable = true;
        return record(id, publicId, readAll(catalogued, id));
    }

    /** how long to wait for a remote schema location to accept the connection */
    static final int REMOTE_CONNECT_TIMEOUT_MS = 10_000;
    /** how long to wait for data from a remote schema location */
    static final int REMOTE_READ_TIMEOUT_MS = 30_000;

    private static InputStream openRemote(final String location) throws IOException {
        final java.net.URLConnection connection = URI.create(location).toURL().openConnection();
        connection.setConnectTimeout(REMOTE_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(REMOTE_READ_TIMEOUT_MS);
        return connection.getInputStream();
    }

    private LSInput record(final String id, @Nullable final String publicId, final byte[] bytes) {
        documents.put(id, bytes);
        final DOMInputImpl input = new DOMInputImpl(publicId, id, null, new ByteArrayInputStream(bytes), null);
        return input;
    }

    private void checkRemote(final String uri) {
        final String scheme = schemeOf(uri);
        if (scheme != null && (scheme.equals("http") || scheme.equals("https") || scheme.equals("ftp"))) {
            if (!allowRemote) {
                throw new ResolutionException(disabledByServer
                        ? "remote schema access disabled by server configuration: " + uri
                        : "remote schema access disabled: " + uri, uri, null);
            }
        }
    }

    private static @Nullable String schemeOf(final String uri) {
        final int colon = uri.indexOf(':');
        if (colon < 2) {   // none, or a single letter (a Windows drive)
            return null;
        }
        for (int i = 0; i < colon; i++) {
            final char c = uri.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.')) {
                return null;
            }
        }
        return uri.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * @return the absolute database path that {@code systemId} denotes (resolved against
     *     {@code baseURI} when relative), or null if it does not denote a database document
     */
    private static @Nullable String toDatabasePath(final String systemId, @Nullable final String baseURI) {
        final String direct = stripXmldbScheme(systemId);
        if (direct != null) {
            return direct;
        }
        if (systemId.startsWith("/") && baseURI == null) {
            return normalize(systemId);
        }
        if (baseURI != null && schemeOf(systemId) == null) {
            final String basePath = stripXmldbScheme(baseURI);
            if (basePath != null) {
                try {
                    return normalize(new URI(null, null, basePath, null).resolve(new URI(systemId)).getPath());
                } catch (final URISyntaxException e) {
                    throw new ResolutionException("invalid schema location: " + systemId, systemId, e);
                }
            }
        }
        return null;
    }

    static @Nullable String stripXmldbScheme(final String uri) {
        String rest;
        if (uri.startsWith(XMLDB_EXIST_PREFIX)) {
            rest = uri.substring(XMLDB_EXIST_PREFIX.length());
        } else if (uri.startsWith(XMLDB_PREFIX)) {
            rest = uri.substring(XMLDB_PREFIX.length());
        } else {
            return null;
        }
        if (!rest.startsWith("/")) {
            // skip an authority such as localhost
            final int slash = rest.indexOf('/');
            rest = slash < 0 ? "/" : rest.substring(slash);
        }
        while (rest.startsWith("//")) {
            rest = rest.substring(1);
        }
        return normalize(rest);
    }

    private static String normalize(final String path) {
        return URI.create("file:///").resolve(path.replace(" ", "%20")).normalize().getPath().replace("%20", " ");
    }

    private static String absolutize(final String systemId, @Nullable final String baseURI) {
        if (schemeOf(systemId) != null || baseURI == null) {
            return systemId;
        }
        try {
            return new URI(baseURI).resolve(systemId).toString();
        } catch (final URISyntaxException | IllegalArgumentException e) {
            return systemId;
        }
    }

    private byte[] readFromDatabase(final String path, final String id) {
        try (final LockedDocument locked = broker.getXMLResource(XmldbURI.create(path), LockMode.READ_LOCK)) {
            if (locked == null) {
                throw new ResolutionException("schema document not found: " + id, id, null);
            }
            final DocumentImpl doc = locked.getDocument();
            databaseVersions.put(id, doc.getLastModified());
            if (doc instanceof BinaryDocument binary) {
                try (final InputStream is = broker.getBinaryResource(binary)) {
                    return is.readAllBytes();
                }
            }
            final Serializer serializer = broker.borrowSerializer();
            try {
                final Properties props = new Properties();
                props.setProperty(OutputKeys.INDENT, "no");
                props.setProperty(OutputKeys.ENCODING, "UTF-8");
                props.setProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
                props.setProperty(org.exist.storage.serializers.EXistOutputKeys.EXPAND_XINCLUDES, "no");
                props.setProperty(org.exist.storage.serializers.EXistOutputKeys.PROCESS_XSL_PI, "no");
                props.setProperty(org.exist.storage.serializers.EXistOutputKeys.ADD_EXIST_ID, "none");
                serializer.setProperties(props);
                return serializer.serialize(doc).getBytes(StandardCharsets.UTF_8);
            } finally {
                broker.returnSerializer(serializer);
            }
        } catch (final PermissionDeniedException e) {
            throw new ResolutionException("permission denied reading schema document " + id, id, e);
        } catch (final org.xml.sax.SAXException | IOException e) {
            throw new ResolutionException("unable to read schema document " + id + ": " + e.getMessage(), id, e);
        }
    }

    private static byte[] readAll(final LSInput input, final String id) {
        try {
            final InputStream bytes = input.getByteStream();
            if (bytes != null) {
                try (bytes) {
                    return bytes.readAllBytes();
                }
            }
            final Reader chars = input.getCharacterStream();
            if (chars != null) {
                try (chars) {
                    final StringBuilder sb = new StringBuilder();
                    final char[] buf = new char[8192];
                    int n;
                    while ((n = chars.read(buf)) != -1) {
                        sb.append(buf, 0, n);
                    }
                    return sb.toString().getBytes(StandardCharsets.UTF_8);
                }
            }
            if (input.getStringData() != null) {
                return input.getStringData().getBytes(StandardCharsets.UTF_8);
            }
            try (final InputStream is = openRemote(id)) {
                return is.readAllBytes();
            }
        } catch (final IOException | IllegalArgumentException e) {
            throw new ResolutionException("unable to read schema document " + id + ": " + e.getMessage(), id, e);
        }
    }
}
