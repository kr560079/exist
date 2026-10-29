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

import org.exist.collections.Collection;
import org.exist.collections.CollectionConfiguration;
import org.exist.collections.ConfigReloader;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.dom.persistent.NodeProxy;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.Txn;
import org.exist.test.ExistEmbeddedServer;
import org.exist.util.MimeType;
import org.exist.util.StringInputSource;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.XPathException;
import org.exist.xquery.schema.TypeAnnotation;
import org.exist.xquery.schema.TypeAnnotationProvider;
import org.exist.xquery.value.Sequence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Failure modes of the schema type index (spec section 8): unassignable nodes, unavailable schemas, stale
 * indexes after schema or configuration changes, and persistence across restarts.
 */
public class SchemaTypeFailureModesTest {

    private static final String T_NS = "urn:example:trade";
    private static final String CC = "http://exist-db.org/collection-config/1.0";
    private static final XmldbURI ST = XmldbURI.create("/db/st");
    private static final XmldbURI SCHEMAS = XmldbURI.create("/db/st-schema");
    private static final XmldbURI CONF = XmldbURI.create("/db/system/config/db/st");
    private static final XmldbURI PLAIN = XmldbURI.create("/db/st-plain");
    private static final XmldbURI CHILD = XmldbURI.create("/db/st/child");
    private static final XmldbURI CONF_CHILD = XmldbURI.create("/db/system/config/db/st/child");
    private static final XmldbURI LATE = XmldbURI.create("/db/late");
    private static final XmldbURI CONF_LATE = XmldbURI.create("/db/system/config/db/late");
    private static final String HREF = "xmldb:exist:///db/st-schema/trade.xsd";

    private static String xconf(final String attributes) {
        return "<collection xmlns='" + CC + "'><index xmlns:t='" + T_NS + "' xmlns:xs='http://www.w3.org/2001/XMLSchema'>"
                + "<schema-type href='" + HREF + "' " + attributes + "/>"
                + "<typed-values><type name='xs:decimal'/></typed-values>"
                + "</index></collection>";
    }

    private static final String XCONF_OK = xconf("");
    private static final String XCONF_REJECT = xconf("on-unassignable='reject'");
    private static final String XCONF_AUTO = xconf("reindex-on-schema-change='true'");

    @RegisterExtension
    public static final ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(true, true);

    private BrokerPool pool;
    private DBBroker broker;

    @BeforeEach
    void setUp() throws Exception {
        connect();
        store(SCHEMAS, "trade.xsd", fixture("trade.xsd"));
        store(SCHEMAS, "trade-common.xsd", fixture("trade-common.xsd"));
    }

    private void connect() throws Exception {
        pool = existEmbeddedServer.getBrokerPool();
        broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()));
    }

    @AfterEach
    void tearDown() throws Exception {
        try (final Txn txn = pool.getTransactionManager().beginTransaction()) {
            for (final XmldbURI uri : new XmldbURI[] {CONF, CONF_LATE, ST, LATE, PLAIN, SCHEMAS}) {
                final Collection col = broker.getCollection(uri);
                if (col != null) {
                    broker.removeCollection(txn, col);
                }
            }
            txn.commit();
        }
        broker.close();
    }

    // --- tests ---

    @Test
    void rejectModeFailsStoreWithEXSA0004() throws Exception {
        configure(XCONF_REJECT);
        store(ST, "t1.xml", fixture("trade-1.xml"));

        final Exception e = assertThrows(Exception.class, () -> store(ST, "bad.xml", fixture("bad.xml")));
        assertTrue(messages(e).contains("EXSA0004"), messages(e));
        assertTrue(messages(e).contains("t:bogus[1]"), messages(e));
        assertNull(broker.getCollection(ST).getDocument(broker, XmldbURI.create("bad.xml")), "the rejected document must not be stored");
        // valid documents are still accepted
        store(ST, "t2.xml", fixture("trade-1.xml"));
        assertTyped("/db/st/t2.xml");
    }

    @Test
    void untypedModeStoresPartiallyTypedDocument() throws Exception {
        configure(XCONF_OK);
        store(ST, "bad.xml", fixture("bad.xml"));
        assertNotNull(broker.getCollection(ST).getDocument(broker, XmldbURI.create("bad.xml")));
    }

    @Test
    void unloadableSchemaRejectsStoreWithEXSA0002() throws Exception {
        configure(XCONF_OK);
        store(ST, "t1.xml", fixture("trade-1.xml"));
        assertTyped("/db/st/t1.xml");

        remove(SCHEMAS, "trade.xsd");

        final Exception e = assertThrows(Exception.class, () -> store(ST, "t2.xml", fixture("trade-1.xml")));
        assertTrue(messages(e).contains("EXSA0002"), messages(e));
        assertNull(broker.getCollection(ST).getDocument(broker, XmldbURI.create("t2.xml")));
        assertAnnotationError("EXSA0002", "/db/st/t1.xml");

        // the schema is back: stores and queries work again
        store(SCHEMAS, "trade.xsd", fixture("trade.xsd"));
        store(ST, "t2.xml", fixture("trade-1.xml"));
        assertTyped("/db/st/t1.xml");
    }

    @Test
    void schemaEditMakesQueriesStaleUntilReindex() throws Exception {
        configure(XCONF_OK);
        store(ST, "t1.xml", fixture("trade-1.xml"));
        assertTyped("/db/st/t1.xml");

        modifyXsd();
        store(CONF, CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE, XCONF_OK);   // recomputes the collection's schema key

        assertAnnotationError("EXSA0003", "/db/st/t1.xml");
        xquery("xmldb:reindex('/db/st')");
        assertTyped("/db/st/t1.xml");
    }

    @Test
    void documentsStoredBeforeConfigAreStale() throws Exception {
        store(LATE, "t1.xml", fixture("trade-1.xml"));
        store(CONF_LATE, CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE, XCONF_OK.replace("/db/st-schema", "/db/st-schema"));

        assertAnnotationError("EXSA0003", "/db/late/t1.xml");
        xquery("xmldb:reindex('/db/late')");
        assertTyped("/db/late/t1.xml");
        assertEquals("1000000", xquery("string(doc('/db/late/t1.xml')//t:notional/t:amount)").itemAt(0).getStringValue());
    }

    @Test
    void storingConfigurationWithChangedSchemaReindexesAfterTheStore() throws Exception {
        configure(XCONF_AUTO);
        store(ST, "t1.xml", fixture("trade-1.xml"));
        final DocMeta before = docMeta("t1.xml");
        final int scheduledBefore = index().scheduledReindexCount();

        modifyXsd();
        store(CONF, CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE, XCONF_AUTO);

        // scheduled by the store (as the storing user), done outside the store's locks: wait for it
        awaitTyped("/db/st/t1.xml");
        assertNotEquals(before.indexedWith(), docMeta("t1.xml").indexedWith());
        assertEquals(scheduledBefore + 1, index().scheduledReindexCount());
    }

    @Test
    void reindexOnSchemaChangeRebuildsAutomatically() throws Exception {
        configure(XCONF_AUTO);
        store(ST, "t1.xml", fixture("trade-1.xml"));
        assertTyped("/db/st/t1.xml");
        final DocMeta before = docMeta("t1.xml");
        final int scheduledBefore = index().scheduledReindexCount();

        modifyXsd();
        // the configuration is reloaded without being stored: the schema key changes, the documents are stale
        ConfigReloader.reloadFromStorage(broker, CONF);

        // the first query gets EXSA0003 (or succeeds if the asynchronous reindex was quick); then it works, with no manual reindex
        final long deadline = System.nanoTime() + 10_000_000_000L;
        boolean fresh = false;
        int staleQueries = 0;
        while (!fresh && System.nanoTime() < deadline) {
            try {
                assertTyped("/db/st/t1.xml");
                fresh = true;
            } catch (final XPathException e) {
                assertEquals("EXSA0003", e.getErrorCode().getErrorQName().getLocalPart(), e.getMessage());
                staleQueries++;
                Thread.sleep(20);
            }
        }
        assertTrue(fresh, "the collection was not reindexed in time");
        assertNotEquals(before.indexedWith(), docMeta("t1.xml").indexedWith());
        assertEquals(config().schema().key(), docMeta("t1.xml").indexedWith());
        // however many stale queries there were, exactly one reindex was scheduled for this schema key
        assertEquals(scheduledBefore + 1, index().scheduledReindexCount(), "stale queries: " + staleQueries);
    }

    @Test
    void repeatedStaleQueriesScheduleOneReindex() throws Exception {
        configure(XCONF_AUTO);
        store(ST, "t1.xml", fixture("trade-1.xml"));
        modifyXsd();
        ConfigReloader.reloadFromStorage(broker, CONF);
        final int scheduledBefore = index().scheduledReindexCount();
        for (int i = 0; i < 20; i++) {
            try {
                assertTyped("/db/st/t1.xml");
            } catch (final XPathException e) {
                assertEquals("EXSA0003", e.getErrorCode().getErrorQName().getLocalPart(), e.getMessage());
            }
        }
        assertEquals(scheduledBefore + 1, index().scheduledReindexCount());
    }

    @Test
    void schemaUnavailableAtStartupKeepsCollectionConfigured() throws Exception {
        configure(XCONF_OK);
        store(ST, "t1.xml", fixture("trade-1.xml"));
        remove(SCHEMAS, "trade.xsd");

        restartServer();

        // the configuration survives the unloadable schema: stores fail, queries raise EXSA0002
        final Exception e = assertThrows(Exception.class, () -> store(ST, "t2.xml", fixture("trade-1.xml")));
        assertTrue(messages(e).contains("EXSA0002"), messages(e));
        assertAnnotationError("EXSA0002", "/db/st/t1.xml");

        // the schema is loadable again: it recovers on first use
        store(SCHEMAS, "trade.xsd", fixture("trade.xsd"));
        store(ST, "t2.xml", fixture("trade-1.xml"));
        assertTyped("/db/st/t1.xml");
        assertTyped("/db/st/t2.xml");
    }

    @Test
    void unassignableNodesDoNotAbortReindexAndUpdatesAreRejected() throws Exception {
        configure(XCONF_OK);
        store(ST, "t1.xml", fixture("trade-1.xml"));
        store(ST, "bad.xml", fixture("bad.xml"));
        assertEquals(DocMeta.DocStatus.PARTIAL, docMeta("bad.xml").status());

        configure(XCONF_REJECT);
        // reindexing is tolerant: the node stays untyped and the document partially typed
        xquery("xmldb:reindex('/db/st')");
        assertEquals(DocMeta.DocStatus.PARTIAL, docMeta("bad.xml").status());
        assertTrue(docMeta("bad.xml").unassignablePaths().stream().anyMatch(p -> p.contains("t:bogus")));
        assertEquals(DocMeta.DocStatus.TYPED, docMeta("t1.xml").status());
        assertTyped("/db/st/t1.xml");

        // an update that introduces an unassignable node is rejected and rolled back
        final Exception e = assertThrows(Exception.class,
                () -> xquery("update insert <t:bogus xmlns:t='" + T_NS + "'/> into doc('/db/st/t1.xml')/t:trades/t:trade[1]"));
        assertTrue(messages(e).contains("EXSA0004"), messages(e));
        assertEquals("0", xquery("count(doc('/db/st/t1.xml')//t:bogus)").itemAt(0).getStringValue());
        assertEquals(DocMeta.DocStatus.TYPED, docMeta("t1.xml").status());
    }

    @Test
    void copyOrMoveIntoRejectCollectionIsRejected() throws Exception {
        configure(XCONF_REJECT);
        store(ST, "t1.xml", fixture("trade-1.xml"));
        store(PLAIN, "bad.xml", fixture("bad.xml"));

        final Exception copy = assertThrows(Exception.class, () -> xquery("xmldb:copy-resource('/db/st-plain', 'bad.xml', '/db/st', 'copied.xml')"));
        assertTrue(messages(copy).contains("EXSA0004"), messages(copy));
        assertNull(broker.getCollection(ST).getDocument(broker, XmldbURI.create("copied.xml")), "no document may be left behind by a rejected copy");

        final Exception move = assertThrows(Exception.class, () -> xquery("xmldb:move('/db/st-plain', '/db/st', 'bad.xml')"));
        assertTrue(messages(move).contains("EXSA0004"), messages(move));
        assertNull(broker.getCollection(ST).getDocument(broker, XmldbURI.create("bad.xml")));
        assertNotNull(broker.getCollection(PLAIN).getDocument(broker, XmldbURI.create("bad.xml")), "the source of a rejected move stays");

        // a valid document can be copied
        store(PLAIN, "ok.xml", fixture("trade-1.xml"));
        xquery("xmldb:copy-resource('/db/st-plain', 'ok.xml', '/db/st', 'ok.xml')");
        assertTyped("/db/st/ok.xml");
    }

    @Test
    void callerWithoutAccessGetsPermissionDeniedNotSchemaDetails() throws Exception {
        configure(XCONF_REJECT);
        store(ST, "t1.xml", fixture("trade-1.xml"));
        store(PLAIN, "bad.xml", fixture("bad.xml"));
        // the collections are open to everybody, the document is not readable by the guest
        xquery("sm:chmod(xs:anyURI('/db/st'), 'rwxrwxrwx')");
        xquery("sm:chmod(xs:anyURI('/db/st-plain'), 'rwxrwxrwx')");
        xquery("sm:chmod(xs:anyURI('/db/st-plain/bad.xml'), 'rwx------')");

        try (final DBBroker guest = pool.get(Optional.of(pool.getSecurityManager().getGuestSubject()))) {
            for (final String op : new String[] {
                    "xmldb:copy-resource('/db/st-plain', 'bad.xml', '/db/st', 'c.xml')",
                    "xmldb:move('/db/st-plain', '/db/st', 'bad.xml')"}) {
                final Exception e = assertThrows(Exception.class, () -> pool.getXQueryService().execute(guest, op, null));
                assertFalse(messages(e).contains("EXSA000"), messages(e));
                assertTrue(messages(e).toLowerCase().contains("privileges") || messages(e).toLowerCase().contains("permission"), messages(e));
            }
        }
        assertNull(broker.getCollection(ST).getDocument(broker, XmldbURI.create("c.xml")));
        assertNotNull(broker.getCollection(PLAIN).getDocument(broker, XmldbURI.create("bad.xml")));
    }

    @Test
    void storeTimeReindexIsDroppedWhenTheConfigurationStoreAborts() throws Exception {
        configure(XCONF_AUTO);
        store(ST, "t1.xml", fixture("trade-1.xml"));
        modifyXsd();
        final int scheduledBefore = index().scheduledReindexCount();

        try (final Txn txn = pool.getTransactionManager().beginTransaction();
             final Collection col = broker.getOrCreateCollection(txn, CONF)) {
            broker.storeDocument(txn, XmldbURI.create(CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE),
                    new StringInputSource(XCONF_AUTO.getBytes(StandardCharsets.UTF_8)), MimeType.XML_TYPE, col);
            // no commit: aborted on close
        }
        Thread.sleep(300);
        assertEquals(scheduledBefore, index().scheduledReindexCount(), "nothing is scheduled for an aborted store");
    }

    @Test
    void updateBelowTheRootElementIsRejected() throws Exception {
        // the reindex root of a change directly below the root element is the root: the pass starts at the document
        configure(XCONF_REJECT);
        store(ST, "t1.xml", fixture("trade-1.xml"));
        final String before = xquery("count(doc('/db/st/t1.xml')//t:trade)").itemAt(0).getStringValue();

        final Exception e = assertThrows(Exception.class,
                () -> xquery("update insert <t:bogus xmlns:t='" + T_NS + "'/> into doc('/db/st/t1.xml')/t:trades"));
        assertTrue(messages(e).contains("EXSA0004"), messages(e));
        assertEquals(before, xquery("count(doc('/db/st/t1.xml')//t:trade)").itemAt(0).getStringValue());
        assertEquals("0", xquery("count(doc('/db/st/t1.xml')//t:bogus)").itemAt(0).getStringValue());
        assertEquals(DocMeta.DocStatus.TYPED, docMeta("t1.xml").status());
        assertTyped("/db/st/t1.xml");
    }

    @Test
    void automaticReindexLeavesDescendantsWithTheirOwnConfigurationAlone() throws Exception {
        configure(XCONF_AUTO);
        store(CONF_CHILD, CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE, XCONF_OK);
        store(ST, "t1.xml", fixture("trade-1.xml"));
        store(CHILD, "c1.xml", fixture("trade-1.xml"));
        final DocMeta childBefore = index().store().getDocMeta(broker.getCollection(CHILD).getDocument(broker, XmldbURI.create("c1.xml")).getDocId());
        assertNotNull(childBefore);

        modifyXsd();
        ConfigReloader.reloadFromStorage(broker, CONF);
        awaitTyped("/db/st/t1.xml");
        await(() -> index().reindexIdle());

        final DocMeta childAfter = index().store().getDocMeta(broker.getCollection(CHILD).getDocument(broker, XmldbURI.create("c1.xml")).getDocId());
        assertEquals(childBefore, childAfter, "a descendant with its own configuration is not governed by the parent's");
        assertNotEquals(docMeta("t1.xml").indexedWith(), childAfter.indexedWith());
    }

    @Test
    void failedReindexIsScheduledAgainByALaterStaleQuery() throws Exception {
        configure(XCONF_AUTO);
        store(ST, "t1.xml", fixture("trade-1.xml"));
        modifyXsd();
        ConfigReloader.reloadFromStorage(broker, CONF);
        index().reindexRetryMillis = 0;
        index().failNextReindex = true;
        final int scheduledBefore = index().scheduledReindexCount();

        awaitTyped("/db/st/t1.xml");

        assertTrue(index().scheduledReindexCount() >= scheduledBefore + 2, "the failed reindex must have been scheduled again");
    }

    @Test
    void failedReindexIsNotPromisedNorRetriedBeforeTheBackOff() throws Exception {
        configure(XCONF_AUTO);
        store(ST, "t1.xml", fixture("trade-1.xml"));
        modifyXsd();
        ConfigReloader.reloadFromStorage(broker, CONF);
        index().reindexRetryMillis = 3_600_000;
        index().failNextReindex = true;
        final int scheduledBefore = index().scheduledReindexCount();

        final XPathException first = assertThrows(XPathException.class, () -> assertTyped("/db/st/t1.xml"));
        assertEquals("EXSA0003", first.getErrorCode().getErrorQName().getLocalPart());
        await(() -> index().reindexIdle());
        Thread.sleep(100);
        final XPathException later = assertThrows(XPathException.class, () -> assertTyped("/db/st/t1.xml"));
        assertFalse(later.getMessage().contains("pending"), later.getMessage());
        assertEquals(scheduledBefore + 1, index().scheduledReindexCount());
    }

    @Test
    void unavailableSchemaLookupsAreRateLimited() {
        assertTrue(index().mayRetryLoad(4711));
        assertFalse(index().mayRetryLoad(4711));
        index().markAvailable(4711);
        assertTrue(index().mayRetryLoad(4711));
        index().markAvailable(4711);
    }

    @Test
    void storingConfigurationsAndDocumentsConcurrentlyDoesNotHang() throws Exception {
        configure(XCONF_AUTO);
        store(ST, "t1.xml", fixture("trade-1.xml"));
        final String trade = fixture("trade-1.xml");
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            final Future<Void> configs = executor.submit(() -> {
                try (final DBBroker b = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
                    for (int i = 0; i < 10; i++) {
                        storeWith(b, SCHEMAS, "trade.xsd", fixture("trade.xsd").replaceFirst("(?s)(<xs:schema[^>]*>)", "$1<!-- change " + i + " -->"));
                        storeWith(b, CONF, CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE, XCONF_AUTO);
                    }
                }
                return null;
            });
            final Future<Void> docs = executor.submit(() -> {
                try (final DBBroker b = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
                    for (int i = 0; i < 20; i++) {
                        storeWith(b, ST, "d" + i + ".xml", trade);
                    }
                }
                return null;
            });
            configs.get(90, java.util.concurrent.TimeUnit.SECONDS);
            docs.get(90, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        await(() -> index().reindexIdle());
    }

    @Test
    void annotationsAndTypeIdsSurviveRestart() throws Exception {
        configure(XCONF_OK);
        store(ST, "t1.xml", fixture("trade-1.xml"));
        final String path = "/t:trades/t:trade[1]/t:notional/t:amount";
        final TypeAnnotation before = annotationOf(path);
        final String typeId = LocalTypeTable.typeIdOf(before.typeCode());
        assertNotNull(typeId);
        final DocMeta metaBefore = docMeta("t1.xml");

        restartServer();

        final TypeAnnotation after = annotationOf(path);
        assertEquals(typeId, LocalTypeTable.typeIdOf(after.typeCode()));
        assertEquals(before.nilled(), after.nilled());
        assertEquals(metaBefore, docMeta("t1.xml"));
        assertTyped("/db/st/t1.xml");
    }

    @Test
    void removalAfterRestartClearsAnnotations() throws Exception {
        configure(XCONF_OK);
        store(ST, "t1.xml", fixture("trade-1.xml"));
        final int docId = doc("t1.xml").getDocId();
        final NodeProxy amount = node("/db/st/t1.xml", "/t:trades/t:trade[1]/t:notional/t:amount");
        assertNotNull(index().store().getAnnotation(docId, amount.getNodeId()));
        final var nodeId = amount.getNodeId();

        restartServer();
        xquery("xmldb:remove('/db/st', 't1.xml')");

        assertNull(index().store().getDocMeta(docId));
        assertNull(index().store().getAnnotation(docId, nodeId));
    }

    @Test
    void concurrentStoresAndQueriesShareCompiledSchema() throws Exception {
        configure(XCONF_OK);
        final String trade = fixture("trade-1.xml");
        final int threads = 8;
        final int iterations = 20;
        final ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            final List<Future<Void>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int thread = t;
                final Callable<Void> task = () -> {
                    try (final DBBroker b = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
                        for (int i = 0; i < iterations; i++) {
                            final String name = "c" + thread + "-" + i + ".xml";
                            storeWith(b, ST, name, trade);
                            final Sequence result = pool.getXQueryService().execute(b, "declare namespace t='" + T_NS + "'; "
                                    + "(doc('/db/st/" + name + "')//t:notional/t:amount)[1]", null);
                            final TypeAnnotation annotation = TypeAnnotationProvider.of(b).annotationOf((NodeProxy) result.itemAt(0));
                            assertNotEquals(TypeAnnotation.ANY_TYPE, annotation);
                        }
                    }
                    return null;
                };
                futures.add(executor.submit(task));
            }
            for (final Future<Void> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
        assertSame(config().schema(), pool.getSchemaRegistry().load(broker, HREF, false), "one compiled schema is shared");
        assertTrue(pool.getSchemaRegistry().get(config().schema().key()).isPresent());
    }

    // --- helpers ---

    private void configure(final String xconf) throws Exception {
        store(CONF, CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE, xconf);
    }

    private void modifyXsd() throws Exception {
        Thread.sleep(5);    // a distinct last-modified time
        store(SCHEMAS, "trade.xsd", fixture("trade.xsd").replaceFirst("(?s)(<xs:schema[^>]*>)", "$1<!-- a compatible change -->"));
    }

    private void awaitTyped(final String docPath) throws Exception {
        final long deadline = System.nanoTime() + 10_000_000_000L;
        while (true) {
            try {
                assertTyped(docPath);
                return;
            } catch (final XPathException e) {
                assertEquals("EXSA0003", e.getErrorCode().getErrorQName().getLocalPart(), e.getMessage());
                assertTrue(System.nanoTime() < deadline, "not reindexed in time: " + e.getMessage());
                Thread.sleep(20);
            }
        }
    }

    private void await(final java.util.function.BooleanSupplier condition) throws Exception {
        final long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out");
            Thread.sleep(20);
        }
    }

    private void restartServer() throws Exception {
        broker.close();
        existEmbeddedServer.restart(false);
        connect();
    }

    /** the amount of the first notional is annotated with a type of the schema (not xs:anyType) */
    private void assertTyped(final String docPath) throws Exception {
        final TypeAnnotation annotation = TypeAnnotationProvider.of(broker).annotationOf(amount(docPath));
        assertNotEquals(TypeAnnotation.ANY_TYPE, annotation);
        assertNotNull(LocalTypeTable.typeIdOf(annotation.typeCode()));
    }

    /** what a query atomizing the node would hit: the provider's answer for it */
    private void assertAnnotationError(final String code, final String docPath) throws Exception {
        final NodeProxy amount = amount(docPath);
        final TypeAnnotationProvider provider = TypeAnnotationProvider.of(broker);
        final XPathException e = assertThrows(XPathException.class, () -> provider.annotationOf(amount));
        assertEquals(code, e.getErrorCode().getErrorQName().getLocalPart(), e.getMessage());
    }

    private NodeProxy amount(final String docPath) throws Exception {
        final Sequence result = xquery("(doc('" + docPath + "')//t:notional/t:amount)[1]");
        assertEquals(1, result.getItemCount());
        return (NodeProxy) result.itemAt(0);
    }

    private static String messages(final Throwable t) {
        final StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    private SchemaTypeIndex index() {
        return (SchemaTypeIndex) pool.getIndexManager().getIndexById(SchemaTypeIndex.ID);
    }

    private SchemaTypeConfig config() throws Exception {
        final Collection col = broker.getCollection(ST);
        return (SchemaTypeConfig) col.getIndexConfiguration(broker).getCustomIndexSpec(SchemaTypeIndex.ID);
    }

    private DocumentImpl doc(final String name) throws Exception {
        final DocumentImpl doc = broker.getCollection(ST).getDocument(broker, XmldbURI.create(name));
        assertNotNull(doc, "no document " + name);
        return doc;
    }

    private DocMeta docMeta(final String name) throws Exception {
        final DocMeta meta = index().store().getDocMeta(doc(name).getDocId());
        assertNotNull(meta, "no DocMeta for " + name);
        return meta;
    }

    private TypeAnnotation annotationOf(final String path) throws Exception {
        final TypeAnnotationProvider provider = TypeAnnotationProvider.of(broker);
        assertNotNull(provider);
        return provider.annotationOf(node("/db/st/t1.xml", path));
    }

    private NodeProxy node(final String docPath, final String path) throws Exception {
        final Sequence result = xquery("doc('" + docPath + "')" + path);
        assertEquals(1, result.getItemCount(), "expected one node at " + path);
        return (NodeProxy) result.itemAt(0);
    }

    private Sequence xquery(final String query) throws Exception {
        return pool.getXQueryService().execute(broker, "declare namespace t='" + T_NS + "'; " + query, null);
    }

    private void remove(final XmldbURI collection, final String name) throws Exception {
        try (final Txn txn = pool.getTransactionManager().beginTransaction();
             final Collection col = broker.openCollection(collection, org.exist.storage.lock.Lock.LockMode.WRITE_LOCK)) {
            final DocumentImpl doc = col.getDocument(broker, XmldbURI.create(name));
            col.removeResource(txn, broker, doc);
            txn.commit();
        }
    }

    private void store(final XmldbURI collection, final String name, final String content) throws Exception {
        storeWith(broker, collection, name, content);
    }

    private void storeWith(final DBBroker b, final XmldbURI collection, final String name, final String content) throws Exception {
        try (final Txn txn = pool.getTransactionManager().beginTransaction();
             final Collection col = b.getOrCreateCollection(txn, collection)) {
            b.storeDocument(txn, XmldbURI.create(name), new StringInputSource(content.getBytes(StandardCharsets.UTF_8)),
                    MimeType.XML_TYPE, col);
            b.saveCollection(txn, col);
            txn.commit();
        }
    }

    private static String fixture(final String name) throws Exception {
        try (final InputStream is = SchemaTypeFailureModesTest.class.getResourceAsStream("/org/exist/xquery/schema/fixtures/" + name)) {
            assertNotNull(is, "missing fixture " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
