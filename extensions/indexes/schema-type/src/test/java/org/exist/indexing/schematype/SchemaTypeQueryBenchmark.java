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

import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.test.ExistEmbeddedServer;
import org.exist.xquery.XQuery;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Isolated;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Query time with the schema type index (optimizer on) and without it (optimizer off, {@code declare option
 * exist:optimize "enable=no"}, the plain evaluation of type tests and typed comparisons) on the corpus of
 * {@link BenchmarkCorpus}, stored in a collection configured with the {@code <schema-type>} defaults and
 * {@code <typed-values>}: {@code //element(*, T)} for the product type, a notional filtered by a nested amount and a
 * typed value step filtered by its own value. Each round runs a query {@value #BATCH} times (compiled each time);
 * the median of {@value #ROUNDS} measured rounds, after {@value #WARMUP} warm-up rounds, is reported. No gate; both
 * evaluations must return the same number of items, and the report says whether the schema-type index was used.
 *
 * <p>Not a {@code *Test}: run explicitly with:</p>
 * <pre>
 *   mvn test -pl extensions/indexes/schema-type -am -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dtest=SchemaTypeQueryBenchmark -Dexist.run.benchmarks=true -Ddependency-check.skip=true -Ddocker=false
 * </pre>
 */
@Isolated("timings: nothing else may run meanwhile")
public class SchemaTypeQueryBenchmark {

    private static final int WARMUP = 3;
    private static final int ROUNDS = 5;
    private static final int BATCH = 10;

    private static final String SCHEMA_COLLECTION = "/db/bench-query-schema";
    private static final String DATA_COLLECTION = "/db/bench-query";

    @RegisterExtension
    public static final ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(true, true);

    @Test
    void queryTimes() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("exist.run.benchmarks"),
                "Benchmark skipped by default.  Re-run with -Dexist.run.benchmarks=true");

        final BenchmarkCorpus corpus = BenchmarkCorpus.load("unpacked-query-benchmark",
                Integer.getInteger("schema-type.bench.trade-docs", 20));
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            corpus.storeSchemas(pool, broker, SCHEMA_COLLECTION);
            BenchmarkCorpus.configure(pool, broker, DATA_COLLECTION, corpus.xconf(SCHEMA_COLLECTION, "", true));
            corpus.storeDocuments(pool, broker, DATA_COLLECTION);
        }

        final String prolog = "import schema namespace " + corpus.prefix() + "='" + corpus.namespace() + "'; ";
        final String data = "collection('" + DATA_COLLECTION + "')";
        final Map<String, String> queries = new LinkedHashMap<>();
        queries.put("//element(*, " + corpus.productType() + ")", data + "//element(*, " + corpus.productType() + ")");
        queries.put(corpus.notionalQuery(), data + corpus.notionalQuery());
        queries.put(corpus.valueQuery(), data + corpus.valueQuery());

        System.out.println();
        System.out.println("SchemaTypeQueryBenchmark: " + corpus.description() + "; " + WARMUP + " warm-up + " + ROUNDS
                + " measured rounds of " + BATCH + " runs, median ms per run");
        System.out.printf("%-48s  %7s  %12s  %13s  %7s  %s%n", "query", "items", "index (ms)", "no index (ms)",
                "speedup", "index used");
        System.out.println("─".repeat(108));
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            final XQuery xquery = pool.getXQueryService();
            for (final Map.Entry<String, String> query : queries.entrySet()) {
                final String optimized = prolog + query.getValue();
                final String unoptimized = prolog + "declare option exist:optimize \"enable=no\"; " + query.getValue();
                final long items = xquery.execute(broker, optimized, null).getItemCount();
                assertEquals(items, xquery.execute(broker, unoptimized, null).getItemCount(),
                        "optimized and unoptimized results differ: " + query.getKey());
                final double withIndex = time(xquery, broker, optimized);
                final double withoutIndex = time(xquery, broker, unoptimized);
                System.out.printf("%-48s  %7d  %12.2f  %13.2f  %6.1fx  %s%n", query.getKey(), items, withIndex,
                        withoutIndex, withoutIndex / withIndex, usesIndex(xquery, broker, optimized) ? "yes" : "no");
            }
        }
        System.out.println();
    }

    /** the median over the measured rounds of the mean time of one run, in ms */
    private static double time(final XQuery xquery, final DBBroker broker, final String query) throws Exception {
        final double[] rounds = new double[ROUNDS];
        for (int round = 0; round < WARMUP + ROUNDS; round++) {
            final long start = System.nanoTime();
            for (int i = 0; i < BATCH; i++) {
                xquery.execute(broker, query, null).getItemCount();
            }
            if (round >= WARMUP) {
                rounds[round - WARMUP] = (System.nanoTime() - start) / 1_000_000.0 / BATCH;
            }
        }
        Arrays.sort(rounds);
        return rounds[ROUNDS / 2];
    }

    /** true if evaluating the query reports use of the schema-type index (system:trace) */
    private static boolean usesIndex(final XQuery xquery, final DBBroker broker, final String query) throws Exception {
        try {
            return xquery.execute(broker, "declare namespace prof='http://exist-db.org/xquery/profiling'; "
                    + "let $cleared := system:clear-trace() "
                    + "let $enabled := if (empty($cleared)) then system:enable-tracing(true(), false()) else () "
                    + "let $count := if (empty($enabled)) then count(util:eval(" + literal(query) + ")) else -1 "
                    + "let $trace := if ($count ge 0) then system:trace() else () "
                    + "return exists($trace//prof:index[@type = 'schema-type'])", null).effectiveBooleanValue();
        } finally {
            xquery.execute(broker, "system:enable-tracing(false())", null);
        }
    }

    private static String literal(final String s) {
        return '"' + s.replace("&", "&amp;").replace("\"", "\"\"") + '"';
    }
}
