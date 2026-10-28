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
import org.exist.security.Subject;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.Txn;
import org.exist.test.ExistEmbeddedServer;
import org.exist.util.MimeType;
import org.exist.util.StringInputSource;
import org.exist.xmldb.XmldbURI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import static org.exist.util.PropertiesBuilder.propertiesBuilder;
import static org.junit.jupiter.api.Assertions.*;

public class SchemaRegistryTest {

    private static final String BASE = "xmldb:exist:///db/test-schemas/";
    private static final String TRADE = BASE + "trade.xsd";

    @RegisterExtension
    public static final ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(
            propertiesBuilder().put("xquery.schema-registry.cache-size", 2).build(), true, true);

    private DBBroker broker;
    private SchemaRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        broker = pool.get(Optional.of(pool.getSecurityManager().authenticate("admin", "")));
        registry = pool.getSchemaRegistry();
        store("trade.xsd", false);
        store("trade-common.xsd", false);
        store("unqualified.xsd", false);
        store("remote-import.xsd", false);
        store("doctype.xsd", true);
        store("broken.xsd", true);
    }

    @AfterEach
    void tearDown() {
        broker.close();
    }

    private static String fixture(final String name) throws IOException {
        try (final InputStream is = SchemaRegistryTest.class.getResourceAsStream("fixtures/" + name)) {
            assertNotNull(is, "missing fixture " + name);
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void store(final String name, final boolean binary) throws Exception {
        storeContent(name, fixture(name), binary);
    }

    private void storeContent(final String name, final String content, final boolean binary) throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final Txn txn = pool.getTransactionManager().beginTransaction();
             final Collection col = broker.getOrCreateCollection(txn, XmldbURI.create("/db/test-schemas"))) {
            broker.storeDocument(txn, XmldbURI.create(name), new StringInputSource(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    binary ? MimeType.BINARY_TYPE : MimeType.XML_TYPE, col);
            broker.saveCollection(txn, col);
            txn.commit();
        }
    }

    private CompiledSchema load(final String href) throws SchemaLoadException {
        return registry.load(broker, href, false);
    }

    @Test
    void loadsSchemaWithIncludeFromDatabase() throws SchemaLoadException {
        final CompiledSchema s = load(TRADE);
        assertTrue(s.targetNamespaces().contains("urn:example:trade"));
        assertEquals(s, registry.loadedForNamespace("urn:example:trade").orElseThrow());
        assertSame(s, registry.get(s.key()).orElseThrow());
    }

    @Test
    void sameBytesSameKeyAndCachedInstance() throws SchemaLoadException {
        assertSame(load(TRADE), load(TRADE));
    }

    @Test
    void editingIncludedFileChangesKey() throws Exception {
        final SchemaKey k1 = load(TRADE).key();
        storeContent("trade-common.xsd", fixture("trade-common.xsd").replace("</xs:schema>", "<!-- edited --></xs:schema>"), false);
        assertNotEquals(k1, load(TRADE).key());
    }

    @Test
    void remoteImportRejectedByDefault() {
        final SchemaLoadException e = assertThrows(SchemaLoadException.class, () -> load(BASE + "remote-import.xsd"));
        assertTrue(e.getMessage().contains("remote schema access disabled"), e.getMessage());
        assertTrue(e.getMessage().contains("http://schemas.invalid/elsewhere.xsd"), e.getMessage());
    }

    @Test
    void doctypeInSchemaRejected() {
        final SchemaLoadException e = assertThrows(SchemaLoadException.class, () -> load(BASE + "doctype.xsd"));
        assertTrue(e.getMessage().contains("DOCTYPE"), e.getMessage());
    }

    @Test
    void lruEvictsBeyondCacheSize() throws SchemaLoadException {
        final SchemaKey first = load(TRADE).key();
        load(BASE + "unqualified.xsd");
        assertTrue(registry.get(first).isPresent());   // touch: trade is now most recently used
        load(BASE + "unqualified.xsd");                 // unqualified most recent, trade older
        final String tiny = "tiny";
        assertDoesNotThrow(() -> storeContent("tiny.xsd",
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" targetNamespace=\"urn:tiny\"><xs:element name=\"" + tiny + "\"/></xs:schema>", false));
        load(BASE + "tiny.xsd");                        // third distinct schema: evicts least recently used (trade)
        assertTrue(registry.get(first).isEmpty());
    }

    @Test
    void parseErrorReportsLocation() {
        final SchemaLoadException e = assertThrows(SchemaLoadException.class, () -> load(BASE + "broken.xsd"));
        assertEquals(35, e.line());
        assertEquals(BASE + "broken.xsd", e.systemId());
    }

    private static String includingSchema(final String location) {
        return "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" targetNamespace=\"urn:inc\">"
                + "<xs:include schemaLocation=\"" + location + "\"/></xs:schema>";
    }

    @Test
    void fileIncludeFromDatabaseSchemaRejected() throws Exception {
        storeContent("file-include.xsd", includingSchema("file:///etc/hosts"), false);
        final SchemaLoadException e = assertThrows(SchemaLoadException.class, () -> load(BASE + "file-include.xsd"));
        assertTrue(e.getMessage().contains("file:///etc/hosts"), e.getMessage());
    }

    @Test
    void jarWrappedRemoteIncludeRejected() throws Exception {
        storeContent("jar-include.xsd", includingSchema("jar:http://schemas.invalid/x.jar!/a.xsd"), false);
        final SchemaLoadException e = assertThrows(SchemaLoadException.class, () -> load(BASE + "jar-include.xsd"));
        assertTrue(e.getMessage().contains("jar:http://schemas.invalid/x.jar!/a.xsd"), e.getMessage());
    }

    @Test
    void unchangedSchemaIsNotReparsed() throws SchemaLoadException {
        final CompiledSchema first = load(TRADE);
        final int parses = registry.parseCount();
        assertSame(first, load(TRADE));
        assertEquals(parses, registry.parseCount());
    }

    @Test
    void editedIncludeForcesReparse() throws Exception {
        load(TRADE);
        final int parses = registry.parseCount();
        storeContent("trade-common.xsd", fixture("trade-common.xsd").replace("</xs:schema>", "<!-- again --></xs:schema>"), false);
        load(TRADE);
        assertEquals(parses + 1, registry.parseCount());
    }
}
