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
import org.exist.storage.sync.Sync;
import org.exist.test.ExistEmbeddedServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Isolated;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Store-time cost of the schema type index (spec §9.6): the corpus ({@link BenchmarkCorpus}: the FpML examples, or
 * the replicated trade fixture) stored into collections configured
 * <ol>
 *     <li>without {@code <schema-type>},</li>
 *     <li>with {@code <schema-type check-values="false">},</li>
 *     <li>with {@code <schema-type>} defaults (values checked), and</li>
 *     <li>with the defaults and {@code <typed-values>}.</li>
 * </ol>
 * <p>Each round stores the corpus once per configuration (one transaction per document, then a major sync, both
 * timed), in an order rotated from round to round, then removes the collections. After {@value #WARMUP} warm-up
 * rounds, the median of {@value #ROUNDS} measured rounds is reported per configuration. The gate: (3) may cost at most
 * {@value #GATE_PERCENT}% more than (1); (4) is reported against the same target but does not fail the benchmark
 * (typed values are an opt-in value index).</p>
 *
 * <p>This class intentionally does <em>not</em> end in {@code *Test} so that Surefire will not discover it during
 * normal {@code mvn test} runs. Run explicitly with:</p>
 * <pre>
 *   mvn test -pl extensions/indexes/schema-type -am -Dsurefire.failIfNoSpecifiedTests=false \
 *       -Dtest=SchemaTypeStoreBenchmark -Dexist.run.benchmarks=true -Ddependency-check.skip=true -Ddocker=false
 * </pre>
 * <p>{@code -Dschema-type.bench.trade-docs=N} sets the number of trade documents when FpML is not available
 * (default 20).</p>
 */
@Isolated("timings: nothing else may run meanwhile")
public class SchemaTypeStoreBenchmark {

    private static final int WARMUP = 3;
    private static final int ROUNDS = 5;
    private static final double GATE_PERCENT = 25.0;

    private static final String SCHEMA_COLLECTION = "/db/bench-store-schema";
    private static final String DATA_COLLECTION = "/db/bench-store";

    private record Configuration(String label, @Nullable String xconf) {
    }

    @RegisterExtension
    public static final ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(true, true);

    @Test
    void storeOverhead() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("exist.run.benchmarks"),
                "Benchmark skipped by default.  Re-run with -Dexist.run.benchmarks=true");

        final BenchmarkCorpus corpus = BenchmarkCorpus.load("unpacked-store-benchmark",
                Integer.getInteger("schema-type.bench.trade-docs", 20));
        final List<Configuration> configurations = List.of(
                new Configuration("(1) no schema-type", null),
                new Configuration("(2) check-values=false", corpus.xconf(SCHEMA_COLLECTION, "check-values='false'", false)),
                new Configuration("(3) defaults", corpus.xconf(SCHEMA_COLLECTION, "", false)),
                new Configuration("(4) defaults + typed-values", corpus.xconf(SCHEMA_COLLECTION, "", true)));

        final int n = configurations.size();
        final long[][] times = new long[n][ROUNDS];
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            corpus.storeSchemas(pool, broker, SCHEMA_COLLECTION);
            for (int round = 0; round < WARMUP + ROUNDS; round++) {
                for (int k = 0; k < n; k++) {
                    final int c = (k + round) % n;
                    final String collection = DATA_COLLECTION + "/c" + c;
                    BenchmarkCorpus.configure(pool, broker, collection, configurations.get(c).xconf());
                    final long start = System.nanoTime();
                    corpus.storeDocuments(pool, broker, collection);
                    broker.sync(Sync.MAJOR);
                    final long elapsed = System.nanoTime() - start;
                    if (round >= WARMUP) {
                        times[c][round - WARMUP] = elapsed;
                    }
                }
                for (int c = 0; c < n; c++) {
                    BenchmarkCorpus.remove(pool, broker, DATA_COLLECTION + "/c" + c);
                }
                broker.sync(Sync.MAJOR);
            }
        }

        final double[] medians = new double[n];
        for (int c = 0; c < n; c++) {
            medians[c] = median(times[c]) / 1_000_000.0;
        }
        final int docs = corpus.documents().size();
        System.out.println();
        System.out.println("SchemaTypeStoreBenchmark: " + corpus.description() + "; " + WARMUP + " warm-up + " + ROUNDS
                + " measured rounds, median");
        System.out.printf("%-30s  %11s  %8s  %9s  %s%n", "configuration", "median(ms)", "ms/doc", "overhead", "rounds(ms)");
        System.out.println("─".repeat(100));
        for (int c = 0; c < n; c++) {
            System.out.printf("%-30s  %11.1f  %8.3f  %8.1f%%  %s%n", configurations.get(c).label(), medians[c],
                    medians[c] / docs, overhead(medians[c], medians[0]), Arrays.toString(
                            Arrays.stream(times[c]).map(t -> t / 1_000_000).toArray()));
        }
        final double gate = overhead(medians[2], medians[0]);
        final double typedValues = overhead(medians[3], medians[0]);
        System.out.printf("GATE (3) vs (1): %.1f%% (limit %.0f%%) %s%n", gate, GATE_PERCENT,
                gate <= GATE_PERCENT ? "PASS" : "FAIL");
        System.out.printf("TARGET (4) vs (1): %.1f%% (target %.0f%%, not gating) %s%n", typedValues, GATE_PERCENT,
                typedValues <= GATE_PERCENT ? "met" : "missed");
        System.out.println();

        assertTrue(gate <= GATE_PERCENT, "store overhead of <schema-type> defaults over no schema type index is "
                + "%.1f%%, above the %.0f%% gate (spec §9.6)".formatted(gate, GATE_PERCENT));
    }

    private static double overhead(final double value, final double baseline) {
        return (value - baseline) / baseline * 100.0;
    }

    private static double median(final long[] values) {
        final long[] sorted = values.clone();
        Arrays.sort(sorted);
        final int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0;
    }
}
