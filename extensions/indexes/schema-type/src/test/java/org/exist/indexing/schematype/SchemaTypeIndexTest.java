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
import org.exist.dom.persistent.ElementImpl;
import org.exist.dom.persistent.IStoredNode;
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
    private static final XmldbURI ST2 = XmldbURI.create("/db/st2");
    private static final XmldbURI CONF2 = XmldbURI.create("/db/system/config/db/st2");
    private static final XmldbURI PLAIN2 = XmldbURI.create("/db/plain2");
    private static final XmldbURI ST3 = XmldbURI.create("/db/st3");
    private static final XmldbURI CONF3 = XmldbURI.create("/db/system/config/db/st3");
    private static final XmldbURI CONF_PLAIN2 = XmldbURI.create("/db/system/config/db/plain2");

    private static final String XCONF = "<collection xmlns='" + CC + "'><index xmlns:t='" + T_NS
            + "' xmlns:xs='http://www.w3.org/2001/XMLSchema'>"
            + "<schema-type href='xmldb:exist:///db/st-schema/trade.xsd'/>"
            + "<typed-values><type name='t:NonNegativeDecimal'/><type name='xs:decimal'/><type name='xs:ID'/></typed-values>"
            + "</index></collection>";

    /** the configuration without typed values: annotations only */
    private static final String XCONF_ANNOTATIONS = "<collection xmlns='" + CC + "'><index>"
            + "<schema-type href='xmldb:exist:///db/st-schema/trade.xsd'/></index></collection>";

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
            for (final XmldbURI uri : new XmldbURI[] {CONF, CONF2, CONF3, CONF_PLAIN2, ST, ST2, ST3, SCHEMAS, PLAIN, PLAIN2}) {
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
        assertEquals(TypeAnnotation.assessed(Type.STRING, true), nilled);
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
        assertEquals(0, allTypedValues(docs, local).getLength());
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
        index().store().removeDocument(t1.getDocId());
        // a document of a configured collection without entries is stale (EXSA0003)
        final NodeProxy staleAmount = node("/db/st/t1.xml", "/t:trade/t:notional/t:amount");
        final org.exist.xquery.XPathException stale = assertThrows(org.exist.xquery.XPathException.class,
                () -> provider().annotationOf(staleAmount));
        assertTrue(stale.getMessage().contains("EXSA0003") || stale.getErrorCode().getErrorQName().getLocalPart().equals("EXSA0003"));

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
    void nodesOfTypeIsBoundedByTheDocuments() throws Exception {
        final int count = 20;
        try {
            for (int i = 0; i < count; i++) {
                store(ST, "many-" + i + ".xml", "<t:trade xmlns:t='" + T_NS + "'><t:swap/></t:trade>");
            }
            final short[] swap = new short[] {localTypeId(code("Swap"))};
            // few documents: one lookup per document and type
            final long before = index().store().accessCount();
            assertEquals(1, index().store().nodesOfType(docs("many-5.xml"), swap).getLength());
            assertEquals(swap.length, index().store().accessCount() - before);
            // more: a scan of the range of their ids, which leaves out the documents not asked for
            final String[] some = new String[count - 3];
            for (int i = 3; i < count; i++) {
                some[i - 3] = "many-" + i + ".xml";
            }
            assertEquals(count - 3, index().store().nodesOfType(docs(some), swap).getLength());
            final String[] all = java.util.Arrays.copyOf(some, some.length + 1);
            all[some.length] = "trades.xml";
            assertEquals(count - 3 + nodesOfType("trades.xml", code("Swap"), false).getLength(),
                    index().store().nodesOfType(docs(all), swap).getLength());
        } finally {
            for (int i = 0; i < count; i++) {
                query("xmldb:remove('/db/st', 'many-" + i + ".xml')");
            }
        }
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
        assertEquals(0, allTypedValues(docs("t1.xml"), currency).getLength());
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

    // --- fix round 1: partial reassignment, I/O-free removal, moves ---

    @Test
    void reindexRootIsParentElement() throws Exception {
        final SchemaTypeIndexWorker worker = worker();
        final IStoredNode amount = (IStoredNode) node("/db/st/t1.xml", "/t:trade/t:notional/t:amount").getNode();
        final IStoredNode notional = (IStoredNode) node("/db/st/t1.xml", "/t:trade/t:notional").getNode();
        assertEquals(notional.getNodeId(), worker.getReindexRoot(amount, amount.getPath(), false, false).getNodeId());
        assertEquals(notional.getNodeId(), worker.getReindexRoot(notional, notional.getPath(), true, true).getNodeId());
        final IStoredNode id = (IStoredNode) node("/db/st/trades.xml", "/t:trades/t:trade[1]/t:party/@id").getNode();
        final IStoredNode party = (IStoredNode) node("/db/st/trades.xml", "/t:trades/t:trade[1]/t:party").getNode();
        assertEquals(party.getNodeId(), worker.getReindexRoot(id, id.getPath(), false, false).getNodeId());
        final IStoredNode plain = (IStoredNode) node("/db/plain/r.xml", "/r/a").getNode();
        assertNull(worker.getReindexRoot(plain, plain.getPath(), false, false));
    }

    @Test
    void xqueryUpdateInsertIntoNestedElement() throws Exception {
        query("update insert <t:currency xmlns:t='" + T_NS + "'>GBP</t:currency> into doc('/db/st/t1.xml')/t:trade/t:notional");

        assertEquals(code("CurrencyCode"), annotationOf("/t:trade/t:notional/t:currency[2]").typeCode());
        assertEquals(2, nodesOfType("t1.xml", code("CurrencyCode"), false).getLength());
        assertEquals(1, nodesOfType("t1.xml", code("NonNegativeDecimal"), false).getLength());
        assertEquals(1, typedValues("t1.xml", code("NonNegativeDecimal"), "100").getLength());
        assertEquals(code("Swap"), annotationOf("/t:trade/t:swap").typeCode());
        assertEquals(DocStatus.TYPED, docMeta("t1.xml").status());
    }

    @Test
    void xqueryUpdateReplaceReassignsSubtree() throws Exception {
        query("update replace doc('/db/st/t1.xml')/t:trade/t:notional/t:amount with <t:amount xmlns:t='" + T_NS + "'>42</t:amount>");

        assertEquals(code("NonNegativeDecimal"), annotationOf("/t:trade/t:notional/t:amount").typeCode());
        assertEquals(1, nodesOfType("t1.xml", code("NonNegativeDecimal"), false).getLength());
        assertEquals(0, typedValues("t1.xml", code("NonNegativeDecimal"), "100").getLength());
        assertEquals(1, typedValues("t1.xml", code("NonNegativeDecimal"), "42").getLength());
        assertEquals(code("CurrencyCode"), annotationOf("/t:trade/t:notional/t:currency").typeCode());
    }

    @Test
    void seededUnassignableNodeUpdatesDocMeta() throws Exception {
        query("update insert <t:bogus xmlns:t='" + T_NS + "'/> into doc('/db/st/t1.xml')/t:trade");
        assertEquals(DocStatus.PARTIAL, docMeta("t1.xml").status());
        assertTrue(docMeta("t1.xml").unassignablePaths().contains("/t:trade[1]/t:bogus[1]"),
                docMeta("t1.xml").unassignablePaths().toString());
        assertEquals(TypeAnnotation.ANY_TYPE, annotationOf("/t:trade/t:bogus"));

        query("update delete doc('/db/st/t1.xml')/t:trade/t:bogus");
        assertEquals(DocStatus.TYPED, docMeta("t1.xml").status());
        assertTrue(docMeta("t1.xml").unassignablePaths().isEmpty());
    }

    @Test
    void updateValueOnTypedAttributeKeepsAnnotation() throws Exception {
        final String party = "/t:trades/t:trade[1]/t:party";
        assertEquals(Type.ID, annotationIn("/db/st/trades.xml", party + "/@id").typeCode());

        query("update value doc('/db/st/trades.xml')" + party + "/@id with 'party9'");
        assertEquals(Type.ID, annotationIn("/db/st/trades.xml", party + "/@id").typeCode());
        assertEquals(1, provider().nodesOfType(docs("trades.xml"), Type.ID, false, Type.ATTRIBUTE).getLength());

        // DOM API: an existing attribute is reindexed on its own, in STORE mode
        ((ElementImpl) node("/db/st/trades.xml", party).getNode()).setAttribute("id", "party8");
        assertEquals(Type.ID, annotationIn("/db/st/trades.xml", party + "/@id").typeCode());
        assertEquals(1, provider().nodesOfType(docs("trades.xml"), Type.ID, false, Type.ATTRIBUTE).getLength());
    }

    @Test
    void setAttributeUpdatesTypedValue() throws Exception {
        // R26: the DOM API reindexes an existing attribute in STORE mode; its typed value entry follows the new value
        final String party = "/t:trades/t:trade[1]/t:party";
        assertEquals(1, typedValues("trades.xml", Type.ID, "party1").getLength());
        ((ElementImpl) node("/db/st/trades.xml", party).getNode()).setAttribute("id", "party8");
        assertEquals(0, typedValues("trades.xml", Type.ID, "party1").getLength());
        assertEquals(1, typedValues("trades.xml", Type.ID, "party8").getLength());
        assertEquals(Type.ID, annotationIn("/db/st/trades.xml", party + "/@id").typeCode());
    }

    @Test
    void typedValuesFollowXQueryUpdates() throws Exception {
        assertEquals(1, typedValues("t1.xml", code("NonNegativeDecimal"), "100").getLength());
        query("update value doc('/db/st/t1.xml')/t:trade/t:notional/t:amount with '250'");
        assertEquals(0, typedValues("t1.xml", code("NonNegativeDecimal"), "100").getLength());
        assertEquals(1, typedValues("t1.xml", code("NonNegativeDecimal"), "250").getLength());
        query("update value doc('/db/st/trades.xml')/t:trades/t:trade[1]/t:party/@id with 'party9'");
        assertEquals(0, typedValues("trades.xml", Type.ID, "party1").getLength());
        assertEquals(1, typedValues("trades.xml", Type.ID, "party9").getLength());
    }

    @Test
    void typedValueLookupIsBoundedByTheContext() throws Exception {
        // R44: the completeness check reads one summary per context document, whatever the size of the collection
        final String query = "count(doc('/db/st/many-2.xml')//t:notional[t:amount > 50])";
        final int few = 4;
        final int many = 40;
        try {
            for (int i = 0; i < few; i++) {
                store(ST, "many-" + i + ".xml", T1);
            }
            assertTrue(usesSchemaTypeIndex(query));
            final long small = accesses(query);
            for (int i = few; i < many; i++) {
                store(ST, "many-" + i + ".xml", T1);
            }
            assertTrue(usesSchemaTypeIndex(query));
            final long large = accesses(query);
            assertTrue(small > 0);
            assertEquals(small, large, "index accesses grow with the collection");
            // and the answer is right over the whole collection, too
            assertEquals(unoptimized("collection('/db/st')//t:notional[t:amount > 50]"),
                    query("collection('/db/st')//t:notional[t:amount > 50]").getItemCount());
        } finally {
            for (int i = 0; i < many; i++) {
                query("xmldb:remove('/db/st', 'many-" + i + ".xml')");
            }
        }
    }

    @Test
    void summaryRecordsTypesAndFlags() throws Exception {
        final int docId = doc(ST, "t1.xml").getDocId();
        final TypeIndexStore.Summary amount = index().store().getSummary(docId,
                TypeIndexStore.NameKey.of(org.w3c.dom.Node.ELEMENT_NODE, new QName("amount", T_NS)));
        assertNotNull(amount);
        assertEquals(0, amount.flags());
        assertEquals(java.util.Set.of(localTypeId(code("NonNegativeDecimal"))), amount.localTypeIds());
        // an unannotated element (below a skip wildcard)
        final TypeIndexStore.Summary foo = index().store().getSummary(docId,
                TypeIndexStore.NameKey.of(org.w3c.dom.Node.ELEMENT_NODE, new QName("foo", "")));
        assertNotNull(foo);
        assertEquals(TypeIndexStore.SUMMARY_UNTYPED, foo.flags() & TypeIndexStore.SUMMARY_UNTYPED);
        assertNull(index().store().getSummary(docId,
                TypeIndexStore.NameKey.of(org.w3c.dom.Node.ELEMENT_NODE, new QName("absent", T_NS))));
        // a replaced node is reassigned with its parent: the transient pass leaves no untyped node behind
        query("update replace doc('/db/st/t1.xml')/t:trade/t:notional/t:amount with <t:amount>777</t:amount>");
        final TypeIndexStore.Summary replaced = index().store().getSummary(docId,
                TypeIndexStore.NameKey.of(org.w3c.dom.Node.ELEMENT_NODE, new QName("amount", T_NS)));
        assertNotNull(replaced);
        assertEquals(0, replaced.flags() & TypeIndexStore.SUMMARY_UNTYPED);
        assertTrue(usesSchemaTypeIndex("doc('/db/st/t1.xml')//t:notional[t:amount > 700]"));
        // a changed value of a non-repeated element: not repeated
        query("update value doc('/db/st/t1.xml')/t:trade/t:notional/t:amount with '778'");
        assertEquals(0, index().store().getSummary(docId,
                TypeIndexStore.NameKey.of(org.w3c.dom.Node.ELEMENT_NODE, new QName("amount", T_NS))).flags());
        assertTrue(usesSchemaTypeIndex("doc('/db/st/t1.xml')//t:notional[t:amount gt 700]"));
        // an inserted sibling of the same name: repeated
        query("update insert <t:amount>5</t:amount> following doc('/db/st/t1.xml')/t:trade/t:notional/t:amount");
        final TypeIndexStore.Summary repeated = index().store().getSummary(docId,
                TypeIndexStore.NameKey.of(org.w3c.dom.Node.ELEMENT_NODE, new QName("amount", T_NS)));
        assertNotNull(repeated);
        assertEquals(TypeIndexStore.SUMMARY_REPEATED, repeated.flags() & TypeIndexStore.SUMMARY_REPEATED);
        assertFalse(usesSchemaTypeIndex("doc('/db/st/t1.xml')//t:notional[t:amount gt 700]"));
    }

    @Test
    void untypedSubtreesAreListedByTheirRoot() throws Exception {
        // bad.xml: t:bogus is unassignable, its content untyped; list only the root of an untyped subtree per name
        final int docId = doc(ST, "bad.xml").getDocId();
        final NodeProxy bogus = node("/db/st/bad.xml", "/t:trades/t:trade[1]/t:bogus");
        query("update insert <t:deep><t:x><t:x/><t:x/></t:x></t:deep> into doc('/db/st/bad.xml')/t:trades/t:trade[1]/t:bogus");
        final java.util.List<TypeIndexStore.Member> xs = index().store().untypedNodes(docId,
                TypeIndexStore.NameKey.of(org.w3c.dom.Node.ELEMENT_NODE, new QName("x", T_NS)));
        assertEquals(1, xs.size(), "one root for the three t:x");
        // the insert reassigns the unassignable parent: the subtree is listed by it
        assertTrue(xs.getFirst().nodeId().isDescendantOrSelfOf(bogus.getNodeId()));
        // xsi:* attributes are never recorded
        final int trades = doc(ST, "trades.xml").getDocId();
        assertNull(index().store().getSummary(trades, TypeIndexStore.NameKey.of(org.w3c.dom.Node.ATTRIBUTE_NODE,
                new QName("nil", javax.xml.XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI))));
        // removing the subtree drops the root again
        query("update delete doc('/db/st/bad.xml')/t:trades/t:trade[1]/t:bogus/t:deep");
        assertTrue(index().store().untypedNodes(docId,
                TypeIndexStore.NameKey.of(org.w3c.dom.Node.ELEMENT_NODE, new QName("x", T_NS))).isEmpty());
    }

    @Test
    void annotationOnlyCollectionKeepsNoTypedValueRows() throws Exception {
        store(CONF_PLAIN2, CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE, XCONF_ANNOTATIONS);
        store(PLAIN2, "t1.xml", T1);
        store(PLAIN2, "bad.xml", fixture("bad.xml"));
        query("update value doc('/db/plain2/t1.xml')/t:trade/t:notional/t:amount with '5'");
        query("update insert <t:amount>6</t:amount> following doc('/db/plain2/t1.xml')/t:trade/t:notional/t:amount");
        for (final String name : new String[] {"t1.xml", "bad.xml"}) {
            final int docId = doc(PLAIN2, name).getDocId();
            assertEquals(0, index().store().typedValueRowCountByScan(docId), name);
        }
        assertNotNull(index().store().getAnnotation(doc(PLAIN2, "t1.xml").getDocId(),
                node("/db/plain2/t1.xml", "/t:trade/t:tradeId").getNodeId()));
    }

    @Test
    void legacyTypedValueRowsAreRemoved() throws Exception {
        final int docId = doc(ST, "t1.xml").getDocId();
        final NodeProxy amount = node("/db/st/t1.xml", "/t:trade/t:notional/t:amount");
        final short lt = localTypeId(code("NonNegativeDecimal"));
        final byte[] key = TypedKeys.decimalKey(java.math.BigDecimal.valueOf(100));
        final int baseline = index().store().legacyTypedValueRowCount();
        try (final Txn txn = pool.getTransactionManager().beginTransaction()) {
            index().store().putLegacyTypedValue(lt, key, docId, amount.getNodeId());
            txn.commit();
        }
        assertEquals(baseline + 1, index().store().legacyTypedValueRowCount());
        // a subtree update of the document removes them
        query("update value doc('/db/st/t1.xml')/t:trade/t:notional/t:amount with '101'");
        assertEquals(baseline, index().store().legacyTypedValueRowCount());
        // and so does the removal of the document
        try (final Txn txn = pool.getTransactionManager().beginTransaction()) {
            index().store().putLegacyTypedValue(lt, key, docId, amount.getNodeId());
            txn.commit();
        }
        assertEquals(baseline + 1, index().store().legacyTypedValueRowCount());
        query("xmldb:remove('/db/st', 't1.xml')");
        assertEquals(baseline, index().store().legacyTypedValueRowCount());
        assertEquals(0, index().store().rowCountByScan(docId));
    }

    @Test
    void collectionMoveKeepsTypedValues() throws Exception {
        // bad.xml has unannotated amounts: a query over it is not answered from the index
        final String path = "//t:notional[t:amount > 50]";
        final int expected = query("(doc('/db/st/t1.xml'), doc('/db/st/trades.xml'))" + path).getItemCount();
        assertTrue(expected > 0);
        final int[] docIds = {doc(ST, "t1.xml").getDocId(), doc(ST, "trades.xml").getDocId()};
        // the configuration does not move with the collection: configure the new path the same way
        store(CONF3, CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE, XCONF);
        query("xmldb:rename('/db/st', 'st3')");
        assertNull(broker.getCollection(ST));

        final String moved = "(doc('/db/st3/t1.xml'), doc('/db/st3/trades.xml'))" + path;
        assertTrue(usesSchemaTypeIndex(moved));
        assertEquals(expected, query(moved).getItemCount());
        assertEquals(unoptimized(moved), expected);
        assertEquals(1, query("count(doc('/db/st3/t1.xml')//t:notional[t:amount = 100])").itemAt(0).toJavaObject(Integer.class));

        // nothing is left behind once the moved collection is removed
        query("xmldb:remove('/db/st3')");
        for (final int docId : docIds) {
            assertEquals(0, index().store().rowCountByScan(docId), "rows left for document " + docId);
            assertNull(index().store().getDocMeta(docId));
        }
    }

    @Test
    void docMetaRecordsTypedValueTypes() throws Exception {
        assertEquals(config().typedValuesFingerprint(), docMeta("t1.xml").typedValues());
        assertTrue(docMeta("t1.xml").typedValues().contains("{urn:example:trade}NonNegativeDecimal"));
    }

    @Test
    void removalInUnconfiguredCollectionDoesNoIndexIo() throws Exception {
        store(PLAIN2, "p.xml", "<p><q/></p>");
        final long before = index().store().accessCount();
        query("xmldb:remove('/db/plain', 'r.xml')");
        query("xmldb:remove('/db/plain2')");
        assertEquals(before, index().store().accessCount());
    }

    @Test
    void atomizationInUnconfiguredCollectionDoesNoIndexIo() throws Exception {
        // warm up the configured collection, so the cached configuration must be looked up again for /db/plain
        assertEquals(1, query("data(doc('/db/st/t1.xml')//t:amount)").getItemCount());
        final long before = index().store().accessCount();
        final Sequence values = query("data(doc('/db/plain/r.xml')//(a | @x)), doc('/db/plain/r.xml')//@x = 1");
        assertEquals(Type.UNTYPED_ATOMIC, values.itemAt(0).getType());
        assertEquals(before, index().store().accessCount());
    }

    @Test
    void annotationSeesConfigurationAddedAfterLookup() throws Exception {
        store(PLAIN2, "t1.xml", T1);
        final NodeProxy amount = node("/db/plain2/t1.xml", "/t:trade/t:notional/t:amount");
        // caches "no schema" for the collection
        assertNull(provider().annotationOf(amount));

        store(CONF_PLAIN2, CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE, XCONF);

        // the document was stored before the collection was configured: stale, not unannotated
        final org.exist.xquery.XPathException stale = assertThrows(org.exist.xquery.XPathException.class,
                () -> provider().annotationOf(amount));
        assertTrue(stale.getMessage().contains("EXSA0003") || stale.getErrorCode().getErrorQName().getLocalPart().equals("EXSA0003"));
    }

    @Test
    void typeTestOnStaleDocumentRaisesStale() throws Exception {
        store(PLAIN2, "t1.xml", T1);
        store(CONF_PLAIN2, CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE, XCONF);
        // stored before the collection was configured: the type tests must report EXSA0003, not an internal error
        for (final String test : new String[] {
                "doc('/db/plain2/t1.xml')//t:amount instance of element(*, xs:decimal)",
                "doc('/db/plain2/t1.xml')//element(*, xs:decimal)",
                "doc('/db/plain2/t1.xml')//t:amount/self::element(*, xs:decimal)"}) {
            final org.exist.xquery.XPathException stale = assertThrows(org.exist.xquery.XPathException.class,
                    () -> query(test), test);
            assertEquals("EXSA0003", stale.getErrorCode().getErrorQName().getLocalPart(), test + ": " + stale);
        }
    }

    @Test
    void convertingNilledNodeIsCardinalityError() throws Exception {
        final NodeProxy tradeId = node("/db/st/trades.xml", "/t:trades/t:trade[1]/t:tradeId");
        final org.exist.xquery.XPathException e = assertThrows(org.exist.xquery.XPathException.class,
                () -> tradeId.convertTo(Type.STRING));
        assertEquals("XPTY0004", e.getErrorCode().getErrorQName().getLocalPart());
        // a list-typed node has more than one item
        final NodeProxy fixingDates = node("/db/st/trades.xml", "/t:trades/t:trade[1]/t:fixingDates");
        final org.exist.xquery.XPathException list = assertThrows(org.exist.xquery.XPathException.class,
                () -> fixingDates.convertTo(Type.DATE));
        assertEquals("XPTY0004", list.getErrorCode().getErrorQName().getLocalPart());
    }

    @Test
    void moveBetweenConfiguredCollectionsLeavesNoPhantomMembers() throws Exception {
        store(CONF2, CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE, XCONF);
        // a different first document makes the second collection allocate its local ids in another order
        store(ST2, "bad.xml", fixture("bad.xml"));
        final int docId = doc(ST, "t1.xml").getDocId();
        final java.util.Set<Short> sourceIds = index().store().memberTypeIdsByScan(docId);
        assertFalse(sourceIds.isEmpty());

        query("xmldb:move('/db/st', '/db/st2', 't1.xml')");

        final DocumentImpl moved = doc(ST2, "t1.xml");
        assertEquals(docId, moved.getDocId());
        final DefaultDocumentSet docs = new DefaultDocumentSet();
        docs.add(moved);
        // every type member entry of the document matches the node's current annotation
        for (final short localTypeId : index().store().memberTypeIdsByScan(docId)) {
            for (final NodeProxy member : index().store().nodesOfType(docs, new short[] {localTypeId})) {
                final TypeIndexStore.StoredAnnotation annotation = index().store().getAnnotation(docId, member.getNodeId());
                assertNotNull(annotation, "phantom member " + member.getNodeId() + " of local type " + localTypeId);
                assertEquals(localTypeId, annotation.localTypeId(), "phantom member " + member.getNodeId());
            }
        }
        assertEquals(code("NonNegativeDecimal"), provider().annotationOf(node("/db/st2/t1.xml", "/t:trade/t:notional/t:amount")).typeCode());
        assertEquals(1, provider().nodesOfType(docs, code("NonNegativeDecimal"), false, Type.ELEMENT).getLength());
        assertEquals(1, provider().nodesOfType(docs, code("Swap"), false, Type.ELEMENT).getLength());
    }

    // --- helpers ---

    private SchemaTypeIndexWorker worker() {
        return (SchemaTypeIndexWorker) broker.getIndexController().getWorkerByIndexId(SchemaTypeIndex.ID);
    }

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
        final byte[] key = TypedKeys.encode((org.exist.xquery.value.AtomicValue) TypedValues.fromLexical(lexical, code, null).itemAt(0));
        return index().store().typedRange(docs(name), new short[] {localTypeId(code)}, key, key);
    }

    /** every typed value entry of a type in the documents */
    private NodeSet allTypedValues(final DefaultDocumentSet docs, final short localTypeId) throws Exception {
        return index().store().typedRange(docs, new short[] {localTypeId}, new byte[0], new byte[0]);
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

    /** whether a query reports use of the schema type index */
    private boolean usesSchemaTypeIndex(final String expr) throws Exception {
        final Sequence used = query("declare namespace prof='http://exist-db.org/xquery/profiling'; "
                + "let $c := system:clear-trace() "
                + "let $e := if (empty($c)) then system:enable-tracing(true(), false()) else () "
                + "let $n := if (empty($e)) then count(" + expr + ") else -1 "
                + "let $t := if ($n ge 0) then system:trace() else () "
                + "let $d := if (exists($t)) then system:enable-tracing(false()) else () "
                + "return empty($d) and exists($t//prof:index[@type = 'schema-type'])");
        return used.effectiveBooleanValue();
    }

    /** the index accesses of a query (run once before, to warm the caches), which must count 1 */
    private long accesses(final String query) throws Exception {
        assertEquals(1, query(query).itemAt(0).toJavaObject(Integer.class));
        final long before = index().store().accessCount();
        assertEquals(1, query(query).itemAt(0).toJavaObject(Integer.class));
        return index().store().accessCount() - before;
    }

    /** the number of items of a query evaluated without optimization */
    private int unoptimized(final String query) throws Exception {
        return query("declare option exist:optimize 'enable=no'; " + query).getItemCount();
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
