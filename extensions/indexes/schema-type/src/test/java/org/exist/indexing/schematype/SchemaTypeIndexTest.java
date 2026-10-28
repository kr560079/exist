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
import org.exist.dom.QName;
import org.exist.dom.persistent.DefaultDocumentSet;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.dom.persistent.NodeProxy;
import org.exist.dom.persistent.NodeSet;
import org.exist.indexing.schematype.DocMeta.DocStatus;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.Txn;
import org.exist.test.ExistEmbeddedServer;
import org.exist.util.MimeType;
import org.exist.util.StringInputSource;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.schema.CompiledSchema;
import org.exist.xquery.schema.TypeAnnotation;
import org.exist.xquery.schema.TypeAnnotationProvider;
import org.exist.xquery.schema.TypedValues;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.Type;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Index tests: annotations persisted at store time and kept correct through removal, replacement, reindexing
 * and XQuery Update.
 */
public class SchemaTypeIndexTest {

    private static final String T_NS = "urn:example:trade";
    private static final String CC = "http://exist-db.org/collection-config/1.0";
    private static final XmldbURI ST = XmldbURI.create("/db/st");
    private static final XmldbURI SCHEMAS = XmldbURI.create("/db/st-schema");
    private static final XmldbURI PLAIN = XmldbURI.create("/db/plain");
    private static final XmldbURI CONF = XmldbURI.create("/db/system/config/db/st");

    private static final String XCONF = "<collection xmlns='" + CC + "'><index xmlns:t='" + T_NS
            + "' xmlns:xs='http://www.w3.org/2001/XMLSchema'>"
            + "<schema-type href='xmldb:exist:///db/st-schema/trade.xsd'/>"
            + "<typed-values><type name='t:NonNegativeDecimal'/><type name='xs:decimal'/></typed-values>"
            + "</index></collection>";

    private static final String T1 = "<t:trade xmlns:t='" + T_NS + "'>"
            + "<t:tradeId>T1</t:tradeId><t:swap/>"
            + "<t:notional><t:amount>100</t:amount><t:currency>USD</t:currency></t:notional>"
            + "<t:opaque><foo/></t:opaque>"
            + "</t:trade>";

    @RegisterExtension
    public static final ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(true, true);

    private BrokerPool pool;
    private DBBroker broker;

    @BeforeEach
    void setUp() throws Exception {
        pool = existEmbeddedServer.getBrokerPool();
        broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()));
        store(SCHEMAS, "trade.xsd", fixture("trade.xsd"));
        store(SCHEMAS, "trade-common.xsd", fixture("trade-common.xsd"));
        store(CONF, CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE, XCONF);
        store(ST, "t1.xml", T1);
        store(ST, "trades.xml", fixture("trade-1.xml"));
        store(ST, "bad.xml", fixture("bad.xml"));
        store(PLAIN, "r.xml", "<r><a x='1'/></r>");
    }

    @AfterEach
    void tearDown() throws Exception {
        try (final Txn txn = pool.getTransactionManager().beginTransaction()) {
            for (final XmldbURI uri : new XmldbURI[] {CONF, ST, SCHEMAS, PLAIN}) {
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
    void storedDocumentNodesAnnotated() throws Exception {
        assertEquals(code("NonNegativeDecimal"), annotationOf("/t:trade/t:notional/t:amount").typeCode());
        assertEquals(code("CurrencyCode"), annotationOf("/t:trade/t:notional/t:currency").typeCode());
        assertEquals(code("Swap"), annotationOf("/t:trade/t:swap").typeCode());
        assertEquals(Type.STRING, annotationOf("/t:trade/t:tradeId").typeCode());

        final TypeAnnotation nilled = annotationIn("/db/st/trades.xml", "/t:trades/t:trade[1]/t:tradeId");
        assertEquals(new TypeAnnotation(Type.STRING, true), nilled);
        assertEquals(Type.ID, annotationIn("/db/st/trades.xml", "/t:trades/t:trade[1]/t:party/@id").typeCode());
        assertEquals(DocStatus.TYPED, docMeta("t1.xml").status());
    }

    @Test
    void unannotatedNodeInConfiguredCollectionIsAnyType() throws Exception {
        assertEquals(TypeAnnotation.ANY_TYPE, annotationOf("/t:trade/t:opaque/foo"));
        // the root of a skip wildcard's content is not annotated either
        assertEquals(TypeAnnotation.ANY_TYPE, annotationIn("/db/st/bad.xml", "/t:trades/t:trade[1]/t:bogus"));
    }

    @Test
    void unconfiguredCollectionReturnsNull() throws Exception {
        assertNull(annotationIn("/db/plain/r.xml", "/r"));
        assertNull(annotationIn("/db/plain/r.xml", "/r/a/@x"));
        // inert: nothing is stored for documents of unconfigured collections
        assertNull(index().store().getDocMeta(doc(PLAIN, "r.xml").getDocId()));
    }

    @Test
    void removeDocumentClearsEntries() throws Exception {
        final DocumentImpl t1 = doc(ST, "t1.xml");
        final int docId = t1.getDocId();
        final NodeProxy amount = node("/db/st/t1.xml", "/t:trade/t:notional/t:amount");
        assertNotNull(index().store().getAnnotation(docId, amount.getNodeId()));

        query("xmldb:remove('/db/st', 't1.xml')");

        assertNull(index().store().getDocMeta(docId));
        assertNull(index().store().getAnnotation(docId, amount.getNodeId()));
        final short local = localTypeId(code("NonNegativeDecimal"));
        final DefaultDocumentSet docs = new DefaultDocumentSet();
        docs.add(t1);
        assertEquals(0, index().store().nodesOfType(docs, new short[] {local}).getLength());
        assertEquals(0, index().store().typedRange(docs, new short[] {local}, null, true, null, true).getLength());
    }

    @Test
    void replaceDocumentReindexes() throws Exception {
        assertEquals(code("NonNegativeDecimal"), annotationOf("/t:trade/t:notional/t:amount").typeCode());
        store(ST, "t1.xml", "<t:trade xmlns:t='" + T_NS + "'><t:fra/><t:fee><t:amount>7</t:amount></t:fee></t:trade>");

        assertEquals(Type.DECIMAL, annotationOf("/t:trade/t:fee/t:amount").typeCode());
        assertEquals(code("Fra"), annotationOf("/t:trade/t:fra").typeCode());
        assertEquals(0, nodesOfType("t1.xml", code("NonNegativeDecimal"), false).getLength());
        assertEquals(0, nodesOfType("t1.xml", code("Swap"), false).getLength());
        assertEquals(1, typedValues("t1.xml", Type.DECIMAL, "7").getLength());
        assertEquals(0, typedValues("t1.xml", code("NonNegativeDecimal"), "100").getLength());
    }

    @Test
    void reindexCollectionRebuilds() throws Exception {
        final DocumentImpl t1 = doc(ST, "t1.xml");
        final CollectionMeta meta = index().store().getCollectionMeta(t1.getCollection().getId());
        index().store().removeDocument(t1.getDocId(), meta.localTypeIds().values());
        assertEquals(TypeAnnotation.ANY_TYPE, annotationOf("/t:trade/t:notional/t:amount"));

        query("xmldb:reindex('/db/st')");

        assertEquals(code("NonNegativeDecimal"), annotationOf("/t:trade/t:notional/t:amount").typeCode());
        assertEquals(DocStatus.TYPED, docMeta("t1.xml").status());
        assertEquals(1, nodesOfType("t1.xml", code("NonNegativeDecimal"), false).getLength());
        assertEquals(1, typedValues("t1.xml", code("NonNegativeDecimal"), "100").getLength());
    }

    @Test
    void nodesOfTypeIncludesDerived() throws Exception {
        assertEquals(3, nodesOfType("trades.xml", code("Product"), true).getLength());
        assertEquals(0, nodesOfType("trades.xml", code("Product"), false).getLength());
        assertEquals(2, nodesOfType("trades.xml", code("Swap"), true).getLength());
        assertEquals(1, nodesOfType("trades.xml", code("Swap"), false).getLength());
        // attributes are filtered by node kind
        final DefaultDocumentSet docs = docs("trades.xml");
        assertEquals(1, provider().nodesOfType(docs, Type.ID, false, Type.ATTRIBUTE).getLength());
        assertEquals(0, provider().nodesOfType(docs, Type.ID, false, Type.ELEMENT).getLength());
    }

    @Test
    void typedValuesWrittenOnlyForListedTypes() throws Exception {
        // listed: t:NonNegativeDecimal and xs:decimal
        assertEquals(1, typedValues("t1.xml", code("NonNegativeDecimal"), "100").getLength());
        assertEquals(0, typedValues("t1.xml", code("NonNegativeDecimal"), "101").getLength());
        assertEquals(1, typedValues("trades.xml", Type.DECIMAL, "-5").getLength());
        // not listed: annotated, but no typed value entries
        final short currency = localTypeId(code("CurrencyCode"));
        assertEquals(1, nodesOfType("t1.xml", code("CurrencyCode"), false).getLength());
        assertEquals(0, index().store().typedRange(docs("t1.xml"), new short[] {currency}, null, true, null, true).getLength());
    }

    @Test
    void partialDocumentRecorded() throws Exception {
        assertEquals(DocStatus.PARTIAL, docMeta("bad.xml").status());
        assertTrue(docMeta("bad.xml").unassignablePaths().contains("/t:trades[1]/t:trade[1]/t:bogus[1]"),
                docMeta("bad.xml").unassignablePaths().toString());
        // the rest of the document is still typed
        assertEquals(code("NonNegativeDecimal"),
                annotationIn("/db/st/bad.xml", "/t:trades/t:trade[1]/t:notional/t:amount").typeCode());
    }

    @Test
    void xqueryUpdateInsertAnnotatesNewNode() throws Exception {
        // populate the annotation cache first
        assertEquals(code("NonNegativeDecimal"), annotationOf("/t:trade/t:notional/t:amount").typeCode());

        query("update insert <t:fra xmlns:t='" + T_NS + "'/> into doc('/db/st/t1.xml')/t:trade");

        assertEquals(code("Fra"), annotationOf("/t:trade/t:fra").typeCode());
        assertEquals(code("NonNegativeDecimal"), annotationOf("/t:trade/t:notional/t:amount").typeCode());
        assertEquals(1, nodesOfType("t1.xml", code("Fra"), false).getLength());
        assertEquals(1, nodesOfType("t1.xml", code("NonNegativeDecimal"), false).getLength());
        assertEquals(1, typedValues("t1.xml", code("NonNegativeDecimal"), "100").getLength());
    }

    @Test
    void xqueryUpdateDeleteRemovesAnnotation() throws Exception {
        assertEquals(1, nodesOfType("t1.xml", code("NonNegativeDecimal"), false).getLength());
        final NodeProxy amount = node("/db/st/t1.xml", "/t:trade/t:notional/t:amount");

        query("update delete doc('/db/st/t1.xml')/t:trade/t:notional");

        assertNull(index().store().getAnnotation(doc(ST, "t1.xml").getDocId(), amount.getNodeId()));
        assertEquals(0, nodesOfType("t1.xml", code("NonNegativeDecimal"), false).getLength());
        assertEquals(0, nodesOfType("t1.xml", code("CurrencyCode"), false).getLength());
        assertEquals(0, typedValues("t1.xml", code("NonNegativeDecimal"), "100").getLength());
        assertEquals(Type.STRING, annotationOf("/t:trade/t:tradeId").typeCode());
        assertEquals(code("Swap"), annotationOf("/t:trade/t:swap").typeCode());
    }

    @Test
    void xqueryUpdateValueRefreshesTypedValue() throws Exception {
        query("update value doc('/db/st/t1.xml')/t:trade/t:notional/t:amount with '250'");

        assertEquals(code("NonNegativeDecimal"), annotationOf("/t:trade/t:notional/t:amount").typeCode());
        assertEquals(0, typedValues("t1.xml", code("NonNegativeDecimal"), "100").getLength());
        assertEquals(1, typedValues("t1.xml", code("NonNegativeDecimal"), "250").getLength());
    }

    // --- helpers ---

    private SchemaTypeIndex index() {
        return (SchemaTypeIndex) pool.getIndexManager().getIndexById(SchemaTypeIndex.ID);
    }

    private TypeAnnotationProvider provider() {
        final TypeAnnotationProvider provider = TypeAnnotationProvider.of(broker);
        assertNotNull(provider, "schema type index not configured");
        return provider;
    }

    private SchemaTypeConfig config() throws Exception {
        final Collection col = broker.getCollection(ST);
        return (SchemaTypeConfig) col.getIndexConfiguration(broker).getCustomIndexSpec(SchemaTypeIndex.ID);
    }

    private int code(final String localName) throws Exception {
        final CompiledSchema schema = config().schema();
        return schema.typeCode(schema.namedType(new QName(localName, T_NS)).orElseThrow());
    }

    private short localTypeId(final int code) throws Exception {
        final Short local = index().table(broker.getCollection(ST).getId(), config().schema()).localOf(code);
        assertNotNull(local, "no local type id for " + Type.getTypeName(code));
        return local;
    }

    private TypeAnnotation annotationOf(final String path) throws Exception {
        return annotationIn("/db/st/t1.xml", path);
    }

    private TypeAnnotation annotationIn(final String docPath, final String path) throws Exception {
        return provider().annotationOf(node(docPath, path));
    }

    private NodeProxy node(final String docPath, final String path) throws Exception {
        final Sequence result = query("doc('" + docPath + "')" + path);
        assertEquals(1, result.getItemCount(), "expected one node at " + path);
        return (NodeProxy) result.itemAt(0);
    }

    private DocMeta docMeta(final String name) throws Exception {
        final DocMeta meta = index().store().getDocMeta(doc(ST, name).getDocId());
        assertNotNull(meta, "no DocMeta for " + name);
        return meta;
    }

    private NodeSet nodesOfType(final String name, final int code, final boolean includeDerived) throws Exception {
        return provider().nodesOfType(docs(name), code, includeDerived, Type.ELEMENT);
    }

    private NodeSet typedValues(final String name, final int code, final String lexical) throws Exception {
        final byte[] key = ((org.exist.storage.Indexable) TypedValues.fromLexical(lexical, code, null).itemAt(0)).serializeValue(0);
        return index().store().typedRange(docs(name), new short[] {localTypeId(code)}, key, true, key, true);
    }

    private DefaultDocumentSet docs(final String... names) {
        final DefaultDocumentSet docs = new DefaultDocumentSet();
        for (final String name : names) {
            docs.add(doc(ST, name));
        }
        return docs;
    }

    private DocumentImpl doc(final XmldbURI collection, final String name) {
        try {
            final Collection col = broker.getCollection(collection);
            final DocumentImpl doc = col.getDocument(broker, XmldbURI.create(name));
            assertNotNull(doc, "no document " + name);
            return doc;
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }

    private Sequence query(final String query) throws Exception {
        return pool.getXQueryService().execute(broker, "declare namespace t='" + T_NS + "'; " + query, null);
    }

    private void store(final XmldbURI collection, final String name, final String content) throws Exception {
        try (final Txn txn = pool.getTransactionManager().beginTransaction();
             final Collection col = broker.getOrCreateCollection(txn, collection)) {
            broker.storeDocument(txn, XmldbURI.create(name), new StringInputSource(content.getBytes(StandardCharsets.UTF_8)),
                    MimeType.XML_TYPE, col);
            broker.saveCollection(txn, col);
            txn.commit();
        }
    }

    private static String fixture(final String name) throws Exception {
        try (final InputStream is = SchemaTypeIndexTest.class.getResourceAsStream("/org/exist/xquery/schema/fixtures/" + name)) {
            assertNotNull(is, "missing fixture " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
