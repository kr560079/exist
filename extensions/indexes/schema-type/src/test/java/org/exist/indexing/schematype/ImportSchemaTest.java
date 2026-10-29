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
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.Txn;
import org.exist.test.ExistEmbeddedServer;
import org.exist.util.MimeType;
import org.exist.util.StringInputSource;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.XPathException;
import org.exist.xquery.schema.SchemaRegistry;
import org.exist.xquery.value.Sequence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.exist.util.PropertiesBuilder.propertiesBuilder;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code import schema} without a location hint resolves the namespace from collection configurations, which survive
 * restarts and schema cache eviction; type identity checks (EXSA0001) do not depend on the schema cache either.
 */
public class ImportSchemaTest {

    static final String T_NS = "urn:example:trade";
    static final String O_NS = "urn:example:other";
    static final XmldbURI ST = XmldbURI.create("/db/ist");
    static final XmldbURI CONF = XmldbURI.create("/db/system/config/db/ist");
    static final XmldbURI OTHER = XmldbURI.create("/db/ist-other");
    static final XmldbURI CONF_OTHER = XmldbURI.create("/db/system/config/db/ist-other");
    static final XmldbURI SCHEMAS = XmldbURI.create("/db/ist-schema");
    static final XmldbURI QUERIES = XmldbURI.create("/db/ist-q");

    static final String OTHER_XSD = "<xs:schema xmlns:xs='http://www.w3.org/2001/XMLSchema' targetNamespace='" + O_NS + "'"
            + " elementFormDefault='qualified'><xs:element name='root'><xs:complexType><xs:sequence>"
            + "<xs:element name='v' type='xs:int' maxOccurs='unbounded'/></xs:sequence></xs:complexType></xs:element></xs:schema>";
    static final String OTHER_DOC = "<o:root xmlns:o='" + O_NS + "'><o:v>1</o:v><o:v>2</o:v></o:root>";

    static String xconf(final String href) {
        return "<collection xmlns='http://exist-db.org/collection-config/1.0'><index><schema-type href='" + href + "'/></index></collection>";
    }

    /** a server with the default schema cache */
    @Nested
    class Restart {
        @RegisterExtension
        final ExistEmbeddedServer server = new ExistEmbeddedServer(true, true);

        private Fixture fx;

        @BeforeEach
        void setUp() throws Exception {
            fx = new Fixture(server);
            fx.setUpTrade();
        }

        @AfterEach
        void tearDown() throws Exception {
            fx.tearDown();
        }

        @Test
        void undeclaredTypeIsReportedAtItsPosition() {
            final String prolog = "import schema namespace t='" + T_NS + "' at 'xmldb:exist:///db/ist-schema/trade.xsd';\n";
            for (final String expr : new String[] {"doc('/db/ist/t1.xml')//element(*, t:Nope)", "1 instance of element(*, t:Nope)",
                    "doc('/db/ist/t1.xml')//attribute(*, t:Nope)", "1 instance of t:Nope",
                    "doc('/db/ist/t1.xml')//schema-element(t:nope)", "1 instance of schema-element(t:nope)",
                    "doc('/db/ist/t1.xml')//schema-attribute(t:nope)", "1 instance of schema-attribute(t:nope)",
                    "doc('/db/ist/t1.xml')//@schema-attribute(t:nope)", "doc('/db/ist/t1.xml') instance of document-node(schema-element(t:nope))",
                    "doc('/db/ist/t1.xml')/self::document-node(schema-element(t:nope))"}) {
                final XPathException e = assertThrows(XPathException.class, () -> fx.xquery(prolog + expr), expr);
                assertEquals("XPST0008", e.getErrorCode().getErrorQName().getLocalPart(), expr + ": " + e.getMessage());
                assertEquals(2, e.getLine(), expr + ": " + e.getMessage());
                assertTrue(e.getColumn() > 0, expr + ": " + e.getMessage());
            }
        }

        @Test
        void importWithoutHintAfterRestart() throws Exception {
            fx.restart();
            assertEquals("2", fx.xquery("import schema namespace t='" + T_NS + "'; count(doc('/db/ist/t1.xml')//element(*, t:Swap))")
                    .itemAt(0).getStringValue());
        }
    }

    /** reuse of compiled queries from the query pool */
    @Nested
    class Pooled {
        @RegisterExtension
        final ExistEmbeddedServer server = new ExistEmbeddedServer(true, true);

        private Fixture fx;

        @BeforeEach
        void setUp() throws Exception {
            fx = new Fixture(server);
        }

        @AfterEach
        void tearDown() throws Exception {
            fx.tearDown();
        }

        @Test
        void copiedContextWithSchemaImportsIsReauthorizedOnBorrow() throws Exception {
            // a schema only its owner may read, imported by an outer query
            fx.store(SCHEMAS, "other.xsd", OTHER_XSD);
            fx.xquery("sm:chmod(xs:anyURI('/db/ist-schema/other.xsd'), 'rw-------')");
            fx.xquery("(xmldb:create-collection('/db', 'ist-q'), xmldb:store('/db/ist-q', 'q.xq', '1', 'application/xquery'),"
                    + " sm:chmod(xs:anyURI('/db/ist-q/q.xq'), 'rwxr-xr-x'))");
            final org.exist.xquery.XQuery xquery = fx.pool.getXQueryService();
            final org.exist.xquery.XQueryContext outer = new org.exist.xquery.XQueryContext(fx.pool);
            xquery.compile(outer, "import schema namespace o='" + O_NS + "' at 'xmldb:exist:///db/ist-schema/other.xsd'; 1");

            // as util:eval does: the stored query is compiled with a copy of the outer context, then pooled
            final org.exist.xquery.XQueryContext copy = outer.copyContext();
            assertTrue(copy.hasSchemaImports(), "a copy keeps the recorded schema imports");
            final org.exist.source.DBSource source;
            try (final org.exist.dom.persistent.LockedDocument locked = fx.broker.getXMLResource(
                    XmldbURI.create("/db/ist-q/q.xq"), org.exist.storage.lock.Lock.LockMode.READ_LOCK)) {
                source = new org.exist.source.DBSource(fx.pool, (org.exist.dom.persistent.BinaryDocument) locked.getDocument(), true);
            }
            final org.exist.storage.XQueryPool queryPool = fx.pool.getXQueryPool();
            queryPool.returnCompiledXQuery(source, xquery.compile(copy, source));

            final org.exist.security.Subject guest = fx.pool.getSecurityManager().getGuestSubject();
            try (final DBBroker guestBroker = fx.pool.get(Optional.of(guest))) {
                assertNull(queryPool.borrowCompiledXQuery(guestBroker, source),
                        "the guest may not read the imported schema: the pooled query must not be reused for it");
            }
            final org.exist.xquery.CompiledXQuery again = queryPool.borrowCompiledXQuery(fx.broker, source);
            assertNotNull(again, "the entry is kept for users who may use it");
            queryPool.returnCompiledXQuery(source, again);
        }
    }

    /** a server whose schema cache holds a single schema */
    @Nested
    class Eviction {
        @RegisterExtension
        final ExistEmbeddedServer server = new ExistEmbeddedServer(
                propertiesBuilder().put(SchemaRegistry.PROPERTY_CACHE_SIZE, 1).build(), true, true);

        private Fixture fx;

        @BeforeEach
        void setUp() throws Exception {
            fx = new Fixture(server);
            fx.setUpTrade();
            fx.setUpOther();
        }

        @AfterEach
        void tearDown() throws Exception {
            fx.tearDown();
        }

        @Test
        void importWithoutHintAfterEviction() throws Exception {
            // configuring the other collection compiled its schema, evicting the trade schema
            final SchemaRegistry registry = fx.pool.getSchemaRegistry();
            assertTrue(registry.loadedForNamespace(T_NS).isEmpty(), "the trade schema should have been evicted");
            assertEquals("2", fx.xquery("import schema namespace t='" + T_NS + "'; count(doc('/db/ist/t1.xml')//element(*, t:Swap))")
                    .itemAt(0).getStringValue());
        }

        @Test
        void noTypeIdentityErrorForUnrelatedEvictedSchema() throws Exception {
            final String query = "import schema namespace t='" + T_NS + "' at 'xmldb:exist:///db/ist-schema/trade.xsd'; "
                    + "count((collection('/db/ist-other'), collection('/db/ist'))//element(*, t:Swap))";
            assertEquals("2", fx.xquery(query).itemAt(0).getStringValue());
            // and again, with the other schema evicted by the import
            assertEquals("2", fx.xquery(query).itemAt(0).getStringValue());
        }
    }

    static final class Fixture {
        final ExistEmbeddedServer server;
        BrokerPool pool;
        DBBroker broker;

        Fixture(final ExistEmbeddedServer server) throws Exception {
            this.server = server;
            connect();
        }

        void connect() throws Exception {
            pool = server.getBrokerPool();
            broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()));
        }

        void restart() throws Exception {
            broker.close();
            server.restart(false);
            connect();
        }

        void setUpTrade() throws Exception {
            store(SCHEMAS, "trade-common.xsd", fixture("trade-common.xsd"));
            store(SCHEMAS, "trade.xsd", fixture("trade.xsd"));
            store(CONF, "collection.xconf", xconf("xmldb:exist:///db/ist-schema/trade.xsd"));
            store(ST, "t1.xml", fixture("trade-1.xml"));
        }

        void setUpOther() throws Exception {
            store(SCHEMAS, "other.xsd", OTHER_XSD);
            store(CONF_OTHER, "collection.xconf", xconf("xmldb:exist:///db/ist-schema/other.xsd"));
            store(OTHER, "o.xml", OTHER_DOC);
        }

        void tearDown() throws Exception {
            try (final Txn txn = pool.getTransactionManager().beginTransaction()) {
                for (final XmldbURI uri : new XmldbURI[] {CONF, CONF_OTHER, ST, OTHER, SCHEMAS, QUERIES}) {
                    final Collection col = broker.getCollection(uri);
                    if (col != null) {
                        broker.removeCollection(txn, col);
                    }
                }
                txn.commit();
            }
            broker.close();
        }

        Sequence xquery(final String query) throws XPathException, Exception {
            return pool.getXQueryService().execute(broker, query, null);
        }

        void store(final XmldbURI collection, final String name, final String content) throws Exception {
            try (final Txn txn = pool.getTransactionManager().beginTransaction();
                 final Collection col = broker.getOrCreateCollection(txn, collection)) {
                broker.storeDocument(txn, XmldbURI.create(name), new StringInputSource(content.getBytes(StandardCharsets.UTF_8)),
                        MimeType.XML_TYPE, col);
                broker.saveCollection(txn, col);
                txn.commit();
            }
        }

        static String fixture(final String name) throws Exception {
            try (final InputStream is = ImportSchemaTest.class.getResourceAsStream("/org/exist/xquery/schema/fixtures/" + name)) {
                assertNotNull(is, "missing fixture " + name);
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
