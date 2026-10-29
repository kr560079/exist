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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.exist.collections.Collection;
import org.exist.collections.CollectionConfiguration;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.Txn;
import org.exist.test.ExistEmbeddedServer;
import org.exist.util.FileInputSource;
import org.exist.util.MimeType;
import org.exist.util.StringInputSource;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.value.Sequence;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Schema-aware queries end to end on the official FpML confirmation view: its schemas (substitution groups, deep
 * includes, xs:ID/xs:IDREF, simple-content types) and its product examples, stored in a collection configured with
 * the schema type index and typed values.
 *
 * <p>The FpML archive is downloaded by the build (see {@code src/test/resources/fpml/README.md}) and verified
 * (SHA-256) and unpacked here; the tests are skipped when it is not there ({@code -Dfpml.skip=true}, {@code
 * -DskipTests}, offline) or does not match the pinned checksum. Every expected value is computed from the
 * documents by a query that does not use the schema (names, string values and explicit casts only).</p>
 */
public class FpmlEndToEndTest {

    private static final Logger LOG = LogManager.getLogger(FpmlEndToEndTest.class);

    private static final String CC = "http://exist-db.org/collection-config/1.0";
    private static final String FPML_NS = "http://www.fpml.org/FpML-5/confirmation";

    private static final String SCHEMA_COLLECTION = "/db/fpml-schema";
    private static final String DATA_COLLECTION = "/db/fpml";

    /** a query using the schema: the configured schema found by an import without location hint */
    private static final String SCHEMA_PROLOG = "import schema namespace fpml='" + FPML_NS + "'; ";
    /** a query not using the schema */
    private static final String PLAIN_PROLOG = "declare namespace fpml='" + FPML_NS + "'; ";

    private static final String DATA = "collection('" + DATA_COLLECTION + "')";

    // the queries of the tests, each run optimized and unoptimized by optimizedAndUnoptimizedAgree
    private static final String NOTIONAL_QUERY = DATA + "//fpml:notionalAmount[fpml:amount > 1000000]";
    /** fpml:initialValue is xs:decimal or fpml:NonNegativeDecimal, both listed in the typed values */
    private static final String AMOUNT_QUERY = DATA + "//fpml:initialValue[. > 1000000]";
    /** fpml:amount is declared with 13 types, some of them with element-only content (no typed value) */
    private static final String ANY_AMOUNT_QUERY = DATA + "//fpml:amount[. > 1000000]";
    private static final String PRODUCT_QUERY = DATA + "//fpml:trade/schema-element(fpml:product)";
    private static final String ALL_PRODUCTS_QUERY = DATA + "//schema-element(fpml:product)";
    private static final String HREF_QUERY = "every $r in " + DATA + "//fpml:partyReference satisfies "
            + "exists(id($r/@href, $r))";
    private static final String TRADE_DATE_QUERY = DATA
            + "//fpml:trade[fpml:tradeHeader/fpml:tradeDate ge xs:date('2000-01-01')]";
    private static final String TRADE_DATE_STEP_QUERY = DATA + "//fpml:tradeDate[. ge xs:date('2000-01-01')]";

    private static final String XCONF = "<collection xmlns='" + CC + "'>"
            + "<index xmlns:fpml='" + FPML_NS + "' xmlns:xs='http://www.w3.org/2001/XMLSchema'>"
            + "<schema-type href='xmldb:exist://" + SCHEMA_COLLECTION + "/fpml-main-%s.xsd'/>"
            + "%s"
            + "</index></collection>";
    private static final String TYPED_VALUES = "<typed-values>"
            + "<type name='xs:decimal'/><type name='xs:date'/>"
            + "<type name='fpml:NonNegativeDecimal'/><type name='fpml:IdentifiedDate'/>"
            + "</typed-values>";

    @RegisterExtension
    public static final ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(true, true);

    private static Path fpmlDir;
    private static String version;
    private static final List<Path> examples = new ArrayList<>();

    @BeforeAll
    static void storeCorpus() throws Exception {
        final String dir = System.getProperty("fpml.dir");
        final String archive = System.getProperty("fpml.archive");
        final String sha256 = System.getProperty("fpml.sha256");
        version = System.getProperty("fpml.version", "5-12");
        assumeTrue(dir != null && archive != null && sha256 != null,
                "FpML tests skipped: system properties fpml.dir, fpml.archive and fpml.sha256 are not set (run through Maven)");
        final Path zip = Paths.get(archive);
        assumeTrue(Files.isRegularFile(zip), "FpML tests skipped: the FpML " + version + " archive " + zip
                + " is not there (downloaded by the build from " + System.getProperty("fpml.url")
                + " unless -Dfpml.skip=true, -DskipTests or offline)");
        // nothing of an earlier extraction is used, whatever the outcome
        final Path unpacked = Paths.get(dir).resolve("unpacked");
        delete(unpacked);
        final String actual = sha256(zip);
        if (!actual.equalsIgnoreCase(sha256.trim())) {
            final String message = "FpML tests skipped: FpML archive checksum mismatch for " + zip + ": expected SHA-256 "
                    + sha256 + " but got " + actual + ". The archive was not used. If ISDA republished the file at "
                    + System.getProperty("fpml.url") + ", check it and update fpml.sha256 in "
                    + "extensions/indexes/schema-type/pom.xml (and delete the cached download).";
            LOG.warn(message);
            System.err.println("WARNING: " + message);
            assumeTrue(false, message);
        }
        fpmlDir = unpack(zip, unpacked).resolve("confirmation");
        assumeTrue(Files.isRegularFile(fpmlDir.resolve("fpml-main-" + version + ".xsd")),
                "FpML tests skipped: no fpml-main-" + version + ".xsd in the archive " + zip);

        try (final Stream<Path> files = Files.walk(fpmlDir.resolve("products"))) {
            files.filter(p -> p.toString().endsWith(".xml")).sorted().forEach(examples::add);
        }
        assumeTrue(examples.size() >= 20, "FpML tests skipped: fewer than 20 examples in " + fpmlDir);

        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            storeSchemas(pool, broker, SCHEMA_COLLECTION);
            store(pool, broker, XmldbURI.create("/db/system/config" + DATA_COLLECTION),
                    CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE, xconf(SCHEMA_COLLECTION, TYPED_VALUES));
            storeExamples(pool, broker, DATA_COLLECTION);
        }
    }

    // --- the tests ---

    @Test
    void allExamplesFullyTyped() throws Exception {
        final String statuses = string(PLAIN_PROLOG
                + "import module namespace schema='http://exist-db.org/xquery/schema-type'; "
                + "string-join(for $d in " + DATA + " let $s := schema:status($d)?status where $s ne 'typed' "
                + "return document-uri($d) || ' ' || $s || ' ' || string-join(schema:unassignable($d), ' '), '&#10;')");
        assertEquals("", statuses, "examples not fully typed");
        assertEquals(examples.size(), (long) number(PLAIN_PROLOG + "count(" + DATA + ")"));
    }

    @Test
    void notionalAboveThreshold() throws Exception {
        final long expected = number(PLAIN_PROLOG + "count(" + DATA
                + "//fpml:notionalAmount[some $a in fpml:amount satisfies xs:decimal(string($a)) > 1000000])");
        assertTrue(expected > 0, "the corpus has notional amounts above one million");
        assertEquals(expected, count(SCHEMA_PROLOG + NOTIONAL_QUERY));

        final long expectedValues = number(PLAIN_PROLOG + "count(" + DATA
                + "//fpml:initialValue[xs:decimal(string(.)) > 1000000])");
        assertTrue(expectedValues > 0);
        assertEquals(expectedValues, count(SCHEMA_PROLOG + AMOUNT_QUERY));
        // untyped data would give the same counts above (the literal casts it); this assertion, the FOTY0012 one
        // below and the instance-of checks of adjustableDateRange are what tell typed from untyped data: keep them
        // typed values in arithmetic: the sum of the xs:decimal values, without casts
        assertEquals(string(PLAIN_PROLOG + "sum(" + DATA + "//fpml:initialValue ! xs:decimal(string(.)))"),
                string(SCHEMA_PROLOG + "let $s := sum(" + DATA + "//fpml:initialValue) "
                        + "return if ($s instance of xs:decimal) then $s else error()"));

        // atomizing an fpml:amount of a type with element-only content (fpml:CorrelationAmount) is a type error
        assertTrue(number(PLAIN_PROLOG + "count(" + DATA + "//fpml:amount[*])") > 0);
        assertEquals("FOTY0012", string(sameError(SCHEMA_PROLOG, ANY_AMOUNT_QUERY)));
    }

    @Test
    void tradesByProductSubstitution() throws Exception {
        final long expected = number(PLAIN_PROLOG + "declare namespace xs='http://www.w3.org/2001/XMLSchema'; "
                + productNames()
                + "count(" + DATA + "//fpml:trade/*[namespace-uri() eq '" + FPML_NS + "' and local-name() = $names])");
        // one product per trade (the loan trades, of another type, have none)
        final long trades = number(PLAIN_PROLOG + "count(" + DATA + "//fpml:trade[fpml:tradeHeader])");
        assertTrue(trades >= 20);
        assertEquals(trades, expected);
        assertEquals(expected, count(SCHEMA_PROLOG + PRODUCT_QUERY));

        // anywhere in the documents: an element with the name of a member is a product unless the name is declared
        // locally with another type (not derived from the member's type). Five member names are also declared
        // locally; in FpML 5.12 only commoditySwap in a commoditySwaption has another type
        // (fpml:CommoditySwaptionUnderlying, not derived from fpml:Product), so schema-element() must not match it.
        assertEquals("commoditySwap", string(PLAIN_PROLOG + "declare namespace xs='http://www.w3.org/2001/XMLSchema'; "
                + productNames() + "let $xsd := collection('" + SCHEMA_COLLECTION + "') "
                + "return string-join(distinct-values(for $local in $xsd//xs:element[not(parent::xs:schema)][@name = $names] "
                + "where string($local/@type) ne string($xsd/xs:schema/xs:element[@name = $local/@name]/@type) "
                + "return string($local/@name)), ' ')"));
        final long expectedAll = number(PLAIN_PROLOG + "declare namespace xs='http://www.w3.org/2001/XMLSchema'; "
                + productNames()
                + "count(" + DATA + "//*[namespace-uri() eq '" + FPML_NS + "' and local-name() = $names]"
                + "[not(self::fpml:commoditySwap and parent::fpml:commoditySwaption)])");
        assertTrue(number(PLAIN_PROLOG + "count(" + DATA + "//fpml:commoditySwaption/fpml:commoditySwap)") > 0);
        assertEquals(expectedAll, count(SCHEMA_PROLOG + ALL_PRODUCTS_QUERY));
    }

    @Test
    void partyHrefResolves() throws Exception {
        final long references = number(PLAIN_PROLOG + "count(" + DATA + "//fpml:partyReference)");
        assertTrue(references > 0);
        // without the schema: each href names the id attribute of exactly one element of its document
        assertTrue(bool(PLAIN_PROLOG + "every $r in " + DATA + "//fpml:partyReference satisfies "
                + "count(root($r)//*[@id = string($r/@href)]) eq 1"));
        assertTrue(bool(SCHEMA_PROLOG + HREF_QUERY));
        // fn:id finds that element, by the xs:ID type of the schema
        assertTrue(bool(SCHEMA_PROLOG + "every $r in " + DATA + "//fpml:partyReference satisfies "
                + "(let $t := id($r/@href, $r) return count($t) eq 1 and $t is root($r)//*[@id = string($r/@href)])"));
    }

    @Test
    void adjustableDateRange() throws Exception {
        final long expected = number(PLAIN_PROLOG + "count(" + DATA
                + "//fpml:trade[some $d in fpml:tradeHeader/fpml:tradeDate satisfies xs:date(string($d)) ge xs:date('2000-01-01')])");
        assertTrue(expected > 0);
        assertEquals(expected, count(SCHEMA_PROLOG + TRADE_DATE_QUERY));

        final long expectedDates = number(PLAIN_PROLOG + "count(" + DATA
                + "//fpml:tradeDate[xs:date(string(.)) ge xs:date('2000-01-01')])");
        assertEquals(expectedDates, count(SCHEMA_PROLOG + TRADE_DATE_STEP_QUERY));
        // the typed value of an fpml:IdentifiedDate (simple content) is its xs:date
        assertTrue(bool(SCHEMA_PROLOG + "every $d in " + DATA + "//fpml:tradeHeader/fpml:tradeDate satisfies "
                + "(data($d) instance of xs:date and $d instance of element(*, fpml:IdentifiedDate))"));
        // the earliest trade date, by typed order
        assertEquals(string(PLAIN_PROLOG + "min(" + DATA + "//fpml:tradeHeader/fpml:tradeDate ! xs:date(string(.)))"),
                string(SCHEMA_PROLOG + "(for $d in " + DATA + "//fpml:tradeHeader/fpml:tradeDate order by $d "
                        + "return $d)[1]"));
    }

    @Test
    void optimizedAndUnoptimizedAgree() throws Exception {
        for (final String query : List.of(NOTIONAL_QUERY, AMOUNT_QUERY, PRODUCT_QUERY, ALL_PRODUCTS_QUERY, HREF_QUERY,
                TRADE_DATE_QUERY, TRADE_DATE_STEP_QUERY)) {
            assertTrue(bool(same(SCHEMA_PROLOG, query)), "optimized and unoptimized differ: " + query);
        }
    }

    @Test
    void schemaTypeIndexAnswersTypeAndValueQueries() throws Exception {
        for (final String query : List.of(ALL_PRODUCTS_QUERY, AMOUNT_QUERY, TRADE_DATE_STEP_QUERY)) {
            assertTrue(usesIndex(SCHEMA_PROLOG + query), "schema-type index not used: " + query);
        }
        // the others (a child step, a two-step compared path, a compared name of unlisted types) fall back to the
        // usual evaluation: not asserted, the T15/T16 scope
    }

    @Test
    void updatesKeepTypesAndTypedValues() throws Exception {
        final String collection = "/db/fpml-updates";
        final String uri = collection + "/ird-ex01-vanilla-swap.xml";
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            store(pool, broker, XmldbURI.create("/db/system/config" + collection),
                    CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE, xconf(SCHEMA_COLLECTION, TYPED_VALUES));
            store(pool, broker, XmldbURI.create(collection),
                    fpmlDir.resolve("products/interest-rate-derivatives/ird-ex01-vanilla-swap.xml"));
        }
        final String doc = "doc('" + uri + "')";
        final String dates = doc + "//fpml:tradeDate[. ge xs:date('2000-01-01')]";
        final String values = doc + "//fpml:initialValue[. > 1000000000]";
        assertEquals(0, count(SCHEMA_PROLOG + dates));
        assertEquals(0, count(SCHEMA_PROLOG + values));

        query(PLAIN_PROLOG + "update value " + doc + "//fpml:tradeHeader/fpml:tradeDate with '2026-10-29'");
        query(PLAIN_PROLOG + "for $v in " + doc + "//fpml:notionalStepSchedule/fpml:initialValue "
                + "return update value $v with '2000000000.00'");
        // a new party reference, typed on insert, resolves by the schema's xs:ID
        query(PLAIN_PROLOG + "update insert <calculationAgent xmlns='" + FPML_NS + "'>"
                + "<calculationAgentPartyReference href='party1'/></calculationAgent> following " + doc + "//fpml:swap");

        assertEquals("typed", string("import module namespace schema='http://exist-db.org/xquery/schema-type'; "
                + "schema:status(" + doc + ")?status"));
        assertEquals(1, count(SCHEMA_PROLOG + dates));
        assertEquals(number(PLAIN_PROLOG + "count(" + doc + "//fpml:notionalStepSchedule/fpml:initialValue)"),
                count(SCHEMA_PROLOG + values));
        assertTrue(bool(SCHEMA_PROLOG + "id(" + doc + "//fpml:calculationAgentPartyReference/@href, " + doc + ") is "
                + doc + "//fpml:party[@id = 'party1']"));
        for (final String query : List.of(dates, values)) {
            assertTrue(bool(same(SCHEMA_PROLOG, query)), "optimized and unoptimized differ: " + query);
            assertTrue(usesIndex(SCHEMA_PROLOG + query), "schema-type index not used: " + query);
        }
    }

    /**
     * Not a gating test: the store time of the corpus unconfigured, with type annotations only and with typed values
     * (run with {@code -Dfpml.measure=true}; prints the numbers).
     */
    @Test
    @EnabledIfSystemProperty(named = "fpml.measure", matches = "true")
    void storeTimes() throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        final int rounds = Integer.getInteger("fpml.measure.rounds", 3);
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            for (int round = 0; round < rounds; round++) {
                for (final String label : List.of("unconfigured", "annotations", "typed-values")) {
                    final String collection = "/db/m" + round + "-" + label;
                    if (!label.equals("unconfigured")) {
                        store(pool, broker, XmldbURI.create("/db/system/config" + collection),
                                CollectionConfiguration.DEFAULT_COLLECTION_CONFIG_FILE,
                                xconf(SCHEMA_COLLECTION, label.equals("typed-values") ? TYPED_VALUES : ""));
                    }
                    final long start = System.nanoTime();
                    storeExamples(pool, broker, collection);
                    final long millis = (System.nanoTime() - start) / 1_000_000;
                    System.out.printf("FPML MEASURE round=%d %-13s docs=%d store=%d ms (%.2f ms/doc)%n", round, label,
                            examples.size(), millis, millis / (double) examples.size());
                }
            }
        }
    }

    // --- queries ---

    /** binds $names to the local names of the elements of the substitution group of fpml:product (transitively) */
    private static String productNames() {
        return "declare function local:group($heads as xs:string*) as xs:string* { "
                + "  let $members := collection('" + SCHEMA_COLLECTION + "')//xs:element[@substitutionGroup = $heads]/@name/string() "
                + "  let $new := $members[not(. = $heads)] "
                + "  return if (empty($new)) then $heads else local:group(distinct-values(($heads, $new))) "
                + "}; "
                + "let $names := local:group('product')[. ne 'product'] return ";
    }

    /** a query returning true if the query (prolog + expression) gives the same items optimized and unoptimized */
    private static String same(final String prolog, final String expr) {
        return "declare function local:identical($a as item()*, $b as item()*) as xs:boolean { "
                + "  count($a) eq count($b) and deep-equal($a, $b) and (every $i in 1 to count($a) satisfies "
                + "    (if ($a[$i] instance of node()) then $a[$i] is $b[$i] else true())) "
                + "}; "
                + "let $optimized := util:eval(" + literal(prolog + expr) + ") "
                + "let $unoptimized := util:eval(" + literal(prolog + " declare option exist:optimize \"enable=no\"; " + expr) + ") "
                + "return count($optimized) gt 0 and local:identical($optimized, $unoptimized)";
    }

    /** a query returning the code of the error the query raises both optimized and unoptimized, or "different" */
    private static String sameError(final String prolog, final String expr) {
        return "declare function local:run($q as xs:string) as xs:string { "
                + "  try { if (count(util:eval($q)) ge 0) then 'no error' else '' } catch * { local-name-from-QName($err:code) } "
                + "}; "
                + "let $optimized := local:run(" + literal(prolog + expr) + ") "
                + "let $unoptimized := local:run(" + literal(prolog + " declare option exist:optimize \"enable=no\"; " + expr) + ") "
                + "return if ($optimized = $unoptimized) then $optimized else 'different'";
    }

    /**
     * true if evaluating the query reports use of the schema-type index (system:trace); tracing, global state of the
     * shared server, is disabled again whatever happens
     */
    private static boolean usesIndex(final String query) throws Exception {
        try {
            return bool("declare namespace prof='http://exist-db.org/xquery/profiling'; "
                    + "let $cleared := system:clear-trace() "
                    + "let $enabled := if (empty($cleared)) then system:enable-tracing(true(), false()) else () "
                    + "let $count := if (empty($enabled)) then count(util:eval(" + literal(query) + ")) else -1 "
                    + "let $trace := if ($count ge 0) then system:trace() else () "
                    + "return exists($trace//prof:index[@type = 'schema-type'])");
        } finally {
            query("system:enable-tracing(false())");
        }
    }

    private static String literal(final String s) {
        return '"' + s.replace("&", "&amp;").replace("\"", "\"\"") + '"';
    }

    private static Sequence query(final String query) throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            return pool.getXQueryService().execute(broker, query, null);
        }
    }

    private static long count(final String query) throws Exception {
        return query(query).getItemCount();
    }

    private static long number(final String query) throws Exception {
        return Long.parseLong(string(query));
    }

    private static boolean bool(final String query) throws Exception {
        return query(query).effectiveBooleanValue();
    }

    private static String string(final String query) throws Exception {
        return query(query).getStringValue();
    }

    // --- the archive ---

    private static String sha256(final Path file) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (final InputStream is = Files.newInputStream(file)) {
            final byte[] buffer = new byte[65536];
            for (int n; (n = is.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Unpacks the schemas and the product examples of the (verified) archive into the emptied target directory, so
     * that nothing of an earlier extraction is used; an entry whose path leaves the target is rejected.
     */
    private static Path unpack(final Path zip, final Path target) throws IOException {
        delete(target);
        final Path root = Files.createDirectories(target).toAbsolutePath().normalize();
        try (final ZipFile zipFile = new ZipFile(zip.toFile())) {
            for (final ZipEntry entry : Collections.list(zipFile.entries())) {
                final String name = entry.getName();
                final Path out = root.resolve(name).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("FpML archive entry " + name + " leaves the target directory");
                }
                final boolean schema = name.startsWith("confirmation/") && name.endsWith(".xsd")
                        && name.indexOf('/', "confirmation/".length()) < 0;
                final boolean example = name.startsWith("confirmation/products/") && name.endsWith(".xml");
                if (entry.isDirectory() || !(schema || example)) {
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (final InputStream is = zipFile.getInputStream(entry)) {
                    Files.copy(is, out);
                }
            }
        }
        return root;
    }

    private static void delete(final Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (final Stream<Path> old = Files.walk(dir)) {
                for (final Path p : old.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
    }

    // --- storage ---

    private static String xconf(final String schemaCollection, final String typedValues) {
        return String.format(XCONF, version, typedValues).replace(SCHEMA_COLLECTION, schemaCollection);
    }

    private static void storeSchemas(final BrokerPool pool, final DBBroker broker, final String collection) throws Exception {
        try (final Stream<Path> files = Files.list(fpmlDir)) {
            for (final Path xsd : files.filter(p -> p.toString().endsWith(".xsd")).sorted().toList()) {
                store(pool, broker, XmldbURI.create(collection), xsd);
            }
        }
    }

    /** the examples, in a subcollection per product family (the collection's configuration applies to them) */
    private static void storeExamples(final BrokerPool pool, final DBBroker broker, final String collection)
            throws Exception {
        for (final Path example : examples) {
            store(pool, broker, XmldbURI.create(collection + "/" + example.getParent().getFileName()), example);
        }
    }

    private static void store(final BrokerPool pool, final DBBroker broker, final XmldbURI collection, final Path file)
            throws Exception {
        try (final Txn txn = pool.getTransactionManager().beginTransaction();
             final Collection col = broker.getOrCreateCollection(txn, collection)) {
            broker.storeDocument(txn, XmldbURI.create(file.getFileName().toString()), new FileInputSource(file),
                    MimeType.XML_TYPE, col);
            broker.saveCollection(txn, col);
            txn.commit();
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
}
