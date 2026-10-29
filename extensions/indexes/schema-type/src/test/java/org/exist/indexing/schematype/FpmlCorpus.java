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

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The FpML confirmation view downloaded by the build (see {@code src/test/resources/fpml/README.md}), shared by the
 * tests and benchmarks that use it: the archive is verified (SHA-256) and its schemas and product examples unpacked
 * into an emptied directory of its own for each user, so that nothing of an earlier extraction is used.
 *
 * <p>{@link #locate} never fails because the corpus is missing or does not match: it then returns a corpus that is
 * not {@link #available()}, with the reason, and the caller skips (or falls back to another corpus).</p>
 */
final class FpmlCorpus {

    private static final Logger LOG = LogManager.getLogger(FpmlCorpus.class);

    /** the minimum number of product examples for the corpus to be usable */
    private static final int MIN_EXAMPLES = 20;

    private final @Nullable Path dir;
    private final String version;
    private final List<Path> examples;
    private final @Nullable String unavailableReason;

    private FpmlCorpus(@Nullable final Path dir, final String version, final List<Path> examples,
            @Nullable final String unavailableReason) {
        this.dir = dir;
        this.version = version;
        this.examples = examples;
        this.unavailableReason = unavailableReason;
    }

    /**
     * Verifies and unpacks the archive named by the system properties {@code fpml.dir}, {@code fpml.archive} and
     * {@code fpml.sha256} (set by the module's Surefire configuration).
     *
     * @param user a directory name for this user's extraction under {@code fpml.dir}
     */
    static FpmlCorpus locate(final String user) throws IOException {
        final String dir = System.getProperty("fpml.dir");
        final String archive = System.getProperty("fpml.archive");
        final String sha256 = System.getProperty("fpml.sha256");
        final String version = System.getProperty("fpml.version", "5-12");
        if (dir == null || archive == null || sha256 == null) {
            return unavailable(version, "system properties fpml.dir, fpml.archive and fpml.sha256 are not set (run through Maven)");
        }
        final Path zip = Paths.get(archive);
        if (!Files.isRegularFile(zip)) {
            return unavailable(version, "the FpML " + version + " archive " + zip + " is not there (downloaded by the build from "
                    + System.getProperty("fpml.url") + " unless -Dfpml.skip=true, -DskipTests or offline)");
        }
        final Path unpacked = Paths.get(dir).resolve(user);
        delete(unpacked);
        final String actual = sha256(zip);
        if (!actual.equalsIgnoreCase(sha256.trim())) {
            final String message = "FpML archive checksum mismatch for " + zip + ": expected SHA-256 " + sha256
                    + " but got " + actual + ". The archive was not used. If ISDA republished the file at "
                    + System.getProperty("fpml.url") + ", check it and update fpml.sha256 in "
                    + "extensions/indexes/schema-type/pom.xml (and delete the cached download).";
            LOG.warn(message);
            System.err.println("WARNING: " + message);
            return unavailable(version, message);
        }
        final Path confirmation = unpack(zip, unpacked).resolve("confirmation");
        if (!Files.isRegularFile(confirmation.resolve(mainSchemaName(version)))) {
            return unavailable(version, "no " + mainSchemaName(version) + " in the archive " + zip);
        }
        final List<Path> examples;
        try (final Stream<Path> files = Files.walk(confirmation.resolve("products"))) {
            examples = files.filter(p -> p.toString().endsWith(".xml")).sorted().toList();
        }
        if (examples.size() < MIN_EXAMPLES) {
            return unavailable(version, "fewer than " + MIN_EXAMPLES + " examples in " + confirmation);
        }
        return new FpmlCorpus(confirmation, version, examples, null);
    }

    private static FpmlCorpus unavailable(final String version, final String reason) {
        return new FpmlCorpus(null, version, List.of(), reason);
    }

    private static String mainSchemaName(final String version) {
        return "fpml-main-" + version + ".xsd";
    }

    boolean available() {
        return dir != null;
    }

    /** why the corpus is not available, null if it is */
    @Nullable String unavailableReason() {
        return unavailableReason;
    }

    /** the unpacked {@code confirmation} directory: the schemas, and the examples under {@code products/} */
    Path dir() {
        if (dir == null) {
            throw new IllegalStateException("FpML corpus not available: " + unavailableReason);
        }
        return dir;
    }

    /** the FpML version, e.g. {@code 5-12} */
    String version() {
        return version;
    }

    /** the file name of the root schema */
    String mainSchemaName() {
        return mainSchemaName(version);
    }

    /** the root schema and the documents it includes or imports, sorted */
    List<Path> schemas() throws IOException {
        try (final Stream<Path> files = Files.list(dir())) {
            return files.filter(p -> p.toString().endsWith(".xsd")).sorted().toList();
        }
    }

    /** the product examples, sorted */
    List<Path> examples() {
        return examples;
    }

    private static String sha256(final Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (final InputStream is = Files.newInputStream(file)) {
            final byte[] buffer = new byte[65536];
            for (int n; (n = is.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Unpacks the schemas and the product examples of the (verified) archive into the emptied target directory; an
     * entry whose path leaves the target is rejected.
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
}
