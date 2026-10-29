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
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.Txn;
import org.exist.test.ExistEmbeddedServer;
import org.exist.util.MimeType;
import org.exist.util.StringInputSource;
import org.exist.xmldb.XmldbURI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Not a gating test: the cost of storing documents in a collection configured with typed values, with annotations
 * only, and unconfigured (run with {@code -Dschema-type.measure=true}; prints the numbers).
 */
@EnabledIfSystemProperty(named = "schema-type.measure", matches = "true")
public class StoreCostMeasureTest {

    private static final String CC = "http://exist-db.org/collection-config/1.0";
    private static final int DOCS = Integer.getInteger("schema-type.measure.docs", 3000);

    @RegisterExtension
    public static final ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(true, true);

    @Test
    void measure() throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            store(pool, broker, XmldbURI.create("/db/m-schema"), "trade.xsd", fixture("trade.xsd"));
            store(pool, broker, XmldbURI.create("/db/m-schema"), "trade-common.xsd", fixture("trade-common.xsd"));
            final String index = "<schema-type href='xmldb:exist:///db/m-schema/trade.xsd'/>";
            final String typed = "<collection xmlns='" + CC + "'><index xmlns:t='urn:example:trade' "
                    + "xmlns:xs='http://www.w3.org/2001/XMLSchema'>" + index
                    + "<typed-values><type name='t:NonNegativeDecimal'/><type name='xs:decimal'/><type name='xs:string'/>"
                    + "<type name='xs:ID'/><type name='xs:date'/></typed-values></index></collection>";
            final String annotations = "<collection xmlns='" + CC + "'><index>" + index + "</index></collection>";
            final String doc = fixture("trade-1.xml");
            final SchemaTypeIndex sti = (SchemaTypeIndex) pool.getIndexManager().getIndexById(SchemaTypeIndex.ID);
            // warm up
            run(pool, broker, "warm", typed, doc, Math.min(200, DOCS), sti, false);
            run(pool, broker, "typed-values", typed, doc, DOCS, sti, true);
            run(pool, broker, "annotations-only", annotations, doc, DOCS, sti, true);
            run(pool, broker, "unconfigured", null, doc, DOCS, sti, true);
        }
    }

    private static void run(final BrokerPool pool, final DBBroker broker, final String label, final String xconf,
            final String doc, final int count, final SchemaTypeIndex sti, final boolean print) throws Exception {
        final XmldbURI collection = XmldbURI.create("/db/m-" + label);
        if (xconf != null) {
            store(pool, broker, XmldbURI.create("/db/system/config/db/m-" + label),
                    CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE, xconf);
        }
        final long sizeBefore = sti.store().fileSize();
        final long accessesBefore = sti.store().accessCount();
        final long start = System.nanoTime();
        try (final Txn txn = pool.getTransactionManager().beginTransaction();
             final Collection col = broker.getOrCreateCollection(txn, collection)) {
            for (int i = 0; i < count; i++) {
                broker.storeDocument(txn, XmldbURI.create("d" + i + ".xml"),
                        new StringInputSource(doc.getBytes(StandardCharsets.UTF_8)), MimeType.XML_TYPE, col);
            }
            broker.saveCollection(txn, col);
            txn.commit();
        }
        final long millis = (System.nanoTime() - start) / 1_000_000;
        broker.sync(org.exist.storage.sync.Sync.MAJOR);
        if (print) {
            System.out.printf("MEASURE %-17s docs=%d store=%dms (%.2f ms/doc) index-file-growth=%d bytes index-accesses=%d%n",
                    label, count, millis, millis / (double) count, sti.store().fileSize() - sizeBefore,
                    sti.store().accessCount() - accessesBefore);
        }
    }

    private static void store(final BrokerPool pool, final DBBroker broker, final XmldbURI collection, final String name,
            final String content) throws Exception {
        try (final Txn txn = pool.getTransactionManager().beginTransaction();
             final Collection col = broker.getOrCreateCollection(txn, collection)) {
            broker.storeDocument(txn, XmldbURI.create(name), new StringInputSource(content.getBytes(StandardCharsets.UTF_8)),
                    MimeType.XML_TYPE, col);
            broker.saveCollection(txn, col);
            txn.commit();
        }
    }

    private static String fixture(final String name) throws Exception {
        try (final InputStream is = StoreCostMeasureTest.class.getResourceAsStream("/org/exist/xquery/schema/fixtures/" + name)) {
            assertNotNull(is, "missing fixture " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
