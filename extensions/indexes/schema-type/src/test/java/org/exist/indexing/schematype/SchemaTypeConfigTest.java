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
import org.exist.collections.CollectionConfigurationException;
import org.exist.collections.CollectionConfigurationManager;
import org.exist.dom.QName;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.Txn;
import org.exist.test.ExistEmbeddedServer;
import org.exist.util.DatabaseConfigurationException;
import org.exist.util.MimeType;
import org.exist.util.StringInputSource;
import org.exist.xmldb.XmldbURI;
import org.exist.collections.CollectionConfiguration;
import org.exist.EXistException;
import org.exist.TestUtils;
import org.exist.security.Account;
import org.exist.security.Group;
import org.exist.security.PermissionFactory;
import org.exist.security.SecurityManager;
import org.exist.security.Subject;
import org.exist.security.internal.aider.GroupAider;
import org.exist.security.internal.aider.UserAider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class SchemaTypeConfigTest {

    private static final String CC = "http://exist-db.org/collection-config/1.0";
    private static final String HREF = "xmldb:exist:///db/test-schema-type/trade.xsd";

    @RegisterExtension
    public static final ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(true, true);

    private DBBroker broker;

    @BeforeEach
    void setUp() throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()));
        store("trade.xsd");
        store("trade-common.xsd");
    }

    @BeforeAll
    static void createUser() throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        final SecurityManager sm = pool.getSecurityManager();
        try (final DBBroker b = pool.get(Optional.of(sm.getSystemSubject()))) {
            if (sm.getAccount(ALICE) == null) {
                final Group group = new GroupAider(ALICE);
                sm.addGroup(b, group);
                final Account alice = new UserAider(ALICE);
                alice.setPassword(ALICE);
                alice.setPrimaryGroup(group);
                sm.addAccount(alice);
            }
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final Txn txn = pool.getTransactionManager().beginTransaction()) {
            for (final String path : new String[] {"/db/system/config/db/test-schema-type", "/db/test-schema-type"}) {
                final Collection col = broker.getCollection(XmldbURI.create(path));
                if (col != null) {
                    broker.removeCollection(txn, col);
                }
            }
            txn.commit();
        }
        broker.close();
    }

    private static final String ALICE = "alice-schema-type";
    private static final XmldbURI CONF_COL = XmldbURI.create("/db/system/config/db/test-schema-type");

    private void storeXconf(final DBBroker as, final String xconf) throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final Txn txn = pool.getTransactionManager().beginTransaction()) {
            final Collection col = as.getOrCreateCollection(txn, CONF_COL);
            as.storeDocument(txn, CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE_URI, new StringInputSource(xconf), MimeType.XML_TYPE, col);
            txn.commit();
        }
    }

    private String currentXconfHref() throws Exception {
        final Collection col = broker.getCollection(XmldbURI.create("/db/test-schema-type"));
        final Object spec = col.getConfiguration(broker)
                .getIndexConfiguration().getCustomIndexSpec(SchemaTypeIndex.ID);
        return spec == null ? null : ((SchemaTypeConfig) spec).href();
    }

    private void store(final String name) throws Exception {
        final String content;
        try (final InputStream is = SchemaTypeConfigTest.class.getResourceAsStream("/org/exist/xquery/schema/fixtures/" + name)) {
            assertNotNull(is, "missing fixture " + name);
            content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final Txn txn = pool.getTransactionManager().beginTransaction();
             final Collection col = broker.getOrCreateCollection(txn, XmldbURI.create("/db/test-schema-type"))) {
            broker.storeDocument(txn, XmldbURI.create(name), new StringInputSource(content.getBytes(StandardCharsets.UTF_8)), MimeType.XML_TYPE, col);
            broker.saveCollection(txn, col);
            txn.commit();
        }
    }

    private static String xconf(final String schemaTypeAttrs, final String typedValues) {
        return "<collection xmlns='" + CC + "' xmlns:t='urn:example:trade'><index>"
                + "<schema-type " + schemaTypeAttrs + "/>"
                + (typedValues == null ? "" : "<typed-values>" + typedValues + "</typed-values>")
                + "</index></collection>";
    }

    private SchemaTypeConfig parse(final String xconf) throws Exception {
        final org.w3c.dom.Document doc;
        {
            final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xconf)));
        }
        final Element index = (Element) doc.getDocumentElement().getFirstChild();
        Element schemaType = null;
        Element typedValues = null;
        final NodeList kids = index.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            final Node n = kids.item(i);
            if (n instanceof Element e) {
                if ("schema-type".equals(e.getLocalName())) {
                    schemaType = e;
                } else {
                    typedValues = e;
                }
            }
        }
        final Map<String, String> namespaces = new HashMap<>();
        namespaces.put("t", "urn:example:trade");
        return SchemaTypeConfig.parse(broker, schemaType, typedValues, namespaces);
    }

    @Test
    void validConfigParsesWithDefaults() throws Exception {
        final SchemaTypeConfig c = parse(xconf("href='" + HREF + "'", null));
        assertEquals(HREF, c.href());
        assertEquals(OnUnassignable.UNTYPED, c.onUnassignable());
        assertTrue(c.checkValues());
        assertFalse(c.allowRemote());
        assertFalse(c.reindexOnSchemaChange());
        assertTrue(c.typedValueTypes().isEmpty());
        assertNotNull(c.schema());
        assertTrue(c.schema().targetNamespaces().contains("urn:example:trade"));
    }

    @Test
    void explicitAttributesAreHonoured() throws Exception {
        final SchemaTypeConfig c = parse(xconf("href='" + HREF + "' on-unassignable='reject' check-values='false' "
                + "allow-remote='true' reindex-on-schema-change='true'", null));
        assertEquals(OnUnassignable.REJECT, c.onUnassignable());
        assertFalse(c.checkValues());
        assertTrue(c.allowRemote());
        assertTrue(c.reindexOnSchemaChange());
    }

    @Test
    void invalidOnUnassignableRejected() {
        assertThrows(DatabaseConfigurationException.class,
                () -> parse(xconf("href='" + HREF + "' on-unassignable='explode'", null)));
    }

    @Test
    void missingHrefRejected() {
        assertThrows(DatabaseConfigurationException.class, () -> parse(xconf("", null)));
    }

    @Test
    void unresolvableSchemaRejectedWithSystemId() {
        final DatabaseConfigurationException e = assertThrows(DatabaseConfigurationException.class,
                () -> parse(xconf("href='xmldb:exist:///db/test-schema-type/absent.xsd'", null)));
        assertTrue(e.getMessage().contains("absent.xsd"), e.getMessage());
    }

    @Test
    void unknownTypedValueTypeRejected() {
        final DatabaseConfigurationException e = assertThrows(DatabaseConfigurationException.class,
                () -> parse(xconf("href='" + HREF + "'", "<type name='t:Nope'/>")));
        assertTrue(e.getMessage().contains("Nope"), e.getMessage());
    }

    @Test
    void typedValuesResolvePrefixesFromXconf() throws Exception {
        final SchemaTypeConfig c = parse(xconf("href='" + HREF + "'",
                "<type name='t:NonNegativeDecimal'/><type name='xs:decimal' xmlns:xs='http://www.w3.org/2001/XMLSchema'/>"));
        assertTrue(c.typedValueTypes().contains(new QName("NonNegativeDecimal", "urn:example:trade")));
        assertTrue(c.typedValueTypes().contains(new QName("decimal", "http://www.w3.org/2001/XMLSchema")));
        assertEquals(2, c.typedValueTypes().size());
    }

    @Test
    void checkingXconfWithMissingSchemaIsRejected() {
        final CollectionConfigurationManager mgr = existEmbeddedServer.getBrokerPool().getConfigurationManager();
        assertThrows(CollectionConfigurationException.class, () -> mgr.testConfiguration(broker, xconf("", null)));
        assertThrows(CollectionConfigurationException.class,
                () -> mgr.testConfiguration(broker, xconf("href='xmldb:exist:///db/test-schema-type/absent.xsd'", null)));
        assertThrows(CollectionConfigurationException.class,
                () -> mgr.testConfiguration(broker, xconf("href='" + HREF + "'", "<type name='t:Nope'/>")));
    }

    @Test
    void storingXconfWithBadSchemaIsRejectedAndOldConfigKept() throws Exception {
        storeXconf(broker, xconf("href='" + HREF + "'", null));
        assertEquals(HREF, currentXconfHref());

        final String missing = "xmldb:exist:///db/test-schema-type/absent.xsd";
        final EXistException e = assertThrows(EXistException.class,
                () -> storeXconf(broker, xconf("href='" + missing + "'", null)));
        assertTrue(e.getMessage().contains("absent.xsd"), e.getMessage());
        assertEquals(HREF, currentXconfHref(), "the rejected store must not change the effective configuration");
    }

    @Test
    void storingXconfWithUnknownTypedValueTypeIsRejected() {
        assertThrows(EXistException.class,
                () -> storeXconf(broker, xconf("href='" + HREF + "'", "<type name='t:Nope'/>")));
    }

    @Test
    void brokenNonSchemaTypeSectionStillStores() throws Exception {
        // a range index with neither qname nor path is invalid, but that has always been warn-only
        storeXconf(broker, "<collection xmlns='" + CC + "'><index><create type='xs:string' xmlns:xs='http://www.w3.org/2001/XMLSchema'/></index></collection>");
        try (final var locked = broker.getXMLResource(CONF_COL.append(CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE_URI),
                org.exist.storage.lock.Lock.LockMode.READ_LOCK)) {
            assertNotNull(locked);
        }
    }

    @Test
    void xconfOwnerWithoutReadAccessToSchemaIsRejected() throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final Txn txn = pool.getTransactionManager().beginTransaction()) {
            final Collection conf = broker.getOrCreateCollection(txn, CONF_COL);
            PermissionFactory.chmod(broker, conf, Optional.of(0777), Optional.empty());
            broker.saveCollection(txn, conf);
            final Collection schemas = broker.getOrCreateCollection(txn, XmldbURI.create("/db/test-schema-type"));
            PermissionFactory.chmod(broker, schemas, Optional.of(0755), Optional.empty());
            broker.saveCollection(txn, schemas);
            PermissionFactory.chmod(broker, txn, XmldbURI.create("/db/test-schema-type/trade.xsd"), Optional.of(0700), Optional.empty());
            PermissionFactory.chmod(broker, txn, XmldbURI.create("/db/test-schema-type/trade-common.xsd"), Optional.of(0700), Optional.empty());
            txn.commit();
        }

        final Subject alice = pool.getSecurityManager().authenticate(ALICE, ALICE);
        try (final DBBroker aliceBroker = pool.get(Optional.of(alice))) {
            final EXistException e = assertThrows(EXistException.class,
                    () -> storeXconf(aliceBroker, xconf("href='" + HREF + "'", null)));
            assertTrue(e.getMessage().contains("trade.xsd"), e.getMessage());
        }

        // control: once the schema is readable by others the same store succeeds
        try (final Txn txn = pool.getTransactionManager().beginTransaction()) {
            PermissionFactory.chmod(broker, txn, XmldbURI.create("/db/test-schema-type/trade.xsd"), Optional.of(0744), Optional.empty());
            PermissionFactory.chmod(broker, txn, XmldbURI.create("/db/test-schema-type/trade-common.xsd"), Optional.of(0744), Optional.empty());
            txn.commit();
        }
        try (final DBBroker aliceBroker = pool.get(Optional.of(alice))) {
            storeXconf(aliceBroker, xconf("href='" + HREF + "'", null));
        }
        assertEquals(HREF, currentXconfHref());
    }

    @Test
    void checkingValidXconfIsAccepted() throws Exception {
        final CollectionConfigurationManager mgr = existEmbeddedServer.getBrokerPool().getConfigurationManager();
        mgr.testConfiguration(broker, xconf("href='" + HREF + "'", "<type name='t:NonNegativeDecimal'/>"));
    }
}
