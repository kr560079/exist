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
import org.exist.util.FileInputSource;
import org.exist.util.MimeType;
import org.exist.util.StringInputSource;
import org.exist.xmldb.XmldbURI;
import org.xml.sax.InputSource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The documents of {@link SchemaTypeStoreBenchmark} and {@link SchemaTypeQueryBenchmark}: the FpML confirmation
 * examples ({@link FpmlCorpus}) when the build downloaded them, otherwise the trade fixture of the schema tests with
 * its first trade replicated into documents of about 10,000 nodes.
 */
final class BenchmarkCorpus {

    private static final String CC = "http://exist-db.org/collection-config/1.0";
    private static final String XS = "http://www.w3.org/2001/XMLSchema";
    private static final String FPML_NS = "http://www.fpml.org/FpML-5/confirmation";
    private static final String TRADE_NS = "urn:example:trade";

    /** the number of nodes of a generated trade document (approximately; at least) */
    static final int TRADE_DOC_NODES = 10_000;

    /** a document (or schema) to store: its subcollection (empty for none), name and content */
    record Doc(String subcollection, String name, Supplier<InputSource> content) {
    }

    private final String description;
    private final String prefix;
    private final String namespace;
    private final List<Doc> schemas;
    private final String mainSchema;
    private final String typedValues;
    private final List<Doc> documents;
    private final String productType;
    private final String notionalQuery;
    private final String valueQuery;

    private BenchmarkCorpus(final String description, final String prefix, final String namespace, final List<Doc> schemas,
            final String mainSchema, final String typedValues, final List<Doc> documents, final String productType,
            final String notionalQuery, final String valueQuery) {
        this.description = description;
        this.prefix = prefix;
        this.namespace = namespace;
        this.schemas = schemas;
        this.mainSchema = mainSchema;
        this.typedValues = typedValues;
        this.documents = documents;
        this.productType = productType;
        this.notionalQuery = notionalQuery;
        this.valueQuery = valueQuery;
    }

    /**
     * @param user a directory name for the FpML extraction (see {@link FpmlCorpus#locate})
     * @param tradeDocs the number of generated trade documents when FpML is not available
     */
    static BenchmarkCorpus load(final String user, final int tradeDocs) throws IOException {
        final FpmlCorpus fpml = FpmlCorpus.locate(user);
        if (fpml.available()) {
            return fpml(fpml);
        }
        System.out.println("BENCHMARK FpML corpus not available (" + fpml.unavailableReason()
                + "): using the trade fixture instead");
        return trades(tradeDocs);
    }

    private static BenchmarkCorpus fpml(final FpmlCorpus fpml) throws IOException {
        final List<Doc> schemas = new ArrayList<>();
        for (final Path xsd : fpml.schemas()) {
            schemas.add(file("", xsd));
        }
        final List<Doc> documents = new ArrayList<>();
        for (final Path example : fpml.examples()) {
            documents.add(file(example.getParent().getFileName().toString(), example));
        }
        return new BenchmarkCorpus("FpML " + fpml.version() + " confirmation examples (" + documents.size() + " docs)",
                "fpml", FPML_NS, schemas, fpml.mainSchemaName(),
                "<typed-values><type name='xs:decimal'/><type name='xs:date'/>"
                        + "<type name='fpml:NonNegativeDecimal'/><type name='fpml:IdentifiedDate'/></typed-values>",
                documents, "fpml:Product", "//fpml:notionalAmount[fpml:amount > 500000]",
                "//fpml:initialValue[. > 500000]");
    }

    private static BenchmarkCorpus trades(final int docs) throws IOException {
        final List<Doc> schemas = List.of(fixture("trade.xsd"), fixture("trade-common.xsd"));
        final List<Doc> documents = new ArrayList<>();
        for (int i = 0; i < docs; i++) {
            final byte[] xml = tradeDocument(i).getBytes(StandardCharsets.UTF_8);
            documents.add(new Doc("", "trades-" + i + ".xml", () -> new StringInputSource(xml)));
        }
        return new BenchmarkCorpus("trade fixture, " + docs + " docs of ~" + TRADE_DOC_NODES + " nodes", "t", TRADE_NS,
                schemas, "trade.xsd",
                "<typed-values><type name='xs:decimal'/><type name='xs:date'/>"
                        + "<type name='t:NonNegativeDecimal'/><type name='t:AdjustableDate'/></typed-values>",
                documents, "t:Product", "//t:notional[t:amount > 500000]", "//t:amount[. > 500000]");
    }

    /**
     * The first trade of {@code trade-1.xml} (every kind of node of the fixture: nilled, simple content, a pattern
     * facet, a list, an ID and an IDREF) replicated until the document has {@link #TRADE_DOC_NODES} nodes, with
     * distinct IDs, amounts and dates and the products of the fixture in turn.
     */
    static String tradeDocument(final int seed) {
        final String[] products = {"swap", "fra", "vanillaSwap"};
        final StringBuilder sb = new StringBuilder("<t:trades xmlns:t='" + TRADE_NS
                + "' xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'>");
        int nodes = 1;
        for (int i = 0; nodes < TRADE_DOC_NODES; i++) {
            final String party = "p" + seed + "-" + i;
            sb.append("<t:trade><t:tradeId xsi:nil='true'/>")
                    .append("<t:tradeDate>2026-").append("%02d".formatted(1 + i % 12)).append('-')
                    .append("%02d".formatted(1 + i % 28)).append("</t:tradeDate>")
                    .append("<t:").append(products[i % products.length]).append("/>")
                    .append("<t:notional><t:amount>").append(5000 * (i % 400)).append(".50</t:amount>")
                    .append("<t:currency>").append(i % 2 == 0 ? "USD" : "EUR").append("</t:currency></t:notional>")
                    .append("<t:fee><t:amount>").append(-(i % 10)).append("</t:amount></t:fee>")
                    .append("<t:party id='").append(party).append("'/>")
                    .append("<t:partyReference href='").append(party).append("'/>")
                    .append("<t:description> x </t:description>")
                    .append("<t:fixingDates>2026-01-01 2026-02-01</t:fixingDates></t:trade>");
            // elements 13, attributes 4 (xsi:nil, id, href), text nodes 7
            nodes += 24;
        }
        return sb.append("</t:trades>").toString();
    }

    private static Doc file(final String subcollection, final Path path) {
        return new Doc(subcollection, path.getFileName().toString(), () -> new FileInputSource(path));
    }

    private static Doc fixture(final String name) throws IOException {
        try (final InputStream is = BenchmarkCorpus.class.getResourceAsStream("/org/exist/xquery/schema/fixtures/" + name)) {
            if (is == null) {
                throw new IOException("missing fixture " + name);
            }
            final byte[] content = is.readAllBytes();
            return new Doc("", name, () -> new StringInputSource(content));
        }
    }

    String description() {
        return description;
    }

    String prefix() {
        return prefix;
    }

    String namespace() {
        return namespace;
    }

    List<Doc> documents() {
        return documents;
    }

    /** the name of a product type (abstract, with derived types), for {@code element(*, T)} */
    String productType() {
        return productType;
    }

    /** a notional filtered by a nested amount, relative to a collection */
    String notionalQuery() {
        return notionalQuery;
    }

    /** a typed value step filtered by its own value, relative to a collection */
    String valueQuery() {
        return valueQuery;
    }

    /**
     * The collection configuration with a {@code <schema-type>} element for the schemas stored in the collection.
     *
     * @param attributes extra attributes of {@code <schema-type>}, e.g. {@code check-values='false'}
     * @param withTypedValues whether to add the {@code <typed-values>} of the corpus
     */
    String xconf(final String schemaCollection, final String attributes, final boolean withTypedValues) {
        return "<collection xmlns='" + CC + "'><index xmlns:" + prefix + "='" + namespace + "' xmlns:xs='" + XS + "'>"
                + "<schema-type href='xmldb:exist://" + schemaCollection + "/" + mainSchema + "' " + attributes + "/>"
                + (withTypedValues ? typedValues : "")
                + "</index></collection>";
    }

    void storeSchemas(final BrokerPool pool, final DBBroker broker, final String collection) throws Exception {
        for (final Doc schema : schemas) {
            store(pool, broker, collection, schema);
        }
    }

    /** stores the configuration of the collection (if not null) */
    static void configure(final BrokerPool pool, final DBBroker broker, final String collection,
            @Nullable final String xconf) throws Exception {
        if (xconf != null) {
            store(pool, broker, "/db/system/config" + collection, new Doc("",
                    CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE,
                    () -> new StringInputSource(xconf.getBytes(StandardCharsets.UTF_8))));
        }
    }

    /** stores every document, one transaction each, in its subcollection of the collection */
    void storeDocuments(final BrokerPool pool, final DBBroker broker, final String collection) throws Exception {
        for (final Doc doc : documents) {
            store(pool, broker, doc.subcollection().isEmpty() ? collection : collection + "/" + doc.subcollection(), doc);
        }
    }

    static void store(final BrokerPool pool, final DBBroker broker, final String collection, final Doc doc)
            throws Exception {
        try (final Txn txn = pool.getTransactionManager().beginTransaction();
             final Collection col = broker.getOrCreateCollection(txn, XmldbURI.create(collection))) {
            broker.storeDocument(txn, XmldbURI.create(doc.name()), doc.content().get(), MimeType.XML_TYPE, col);
            broker.saveCollection(txn, col);
            txn.commit();
        }
    }

    /** removes the collection and its configuration, if they exist */
    static void remove(final BrokerPool pool, final DBBroker broker, final String collection) throws Exception {
        for (final String uri : List.of(collection, "/db/system/config" + collection)) {
            try (final Txn txn = pool.getTransactionManager().beginTransaction()) {
                final Collection col = broker.getCollection(XmldbURI.create(uri));
                if (col != null) {
                    col.close();
                    broker.removeCollection(txn, col);
                }
                txn.commit();
            }
        }
    }
}
