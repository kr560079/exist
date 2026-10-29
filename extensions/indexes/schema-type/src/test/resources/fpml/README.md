# FpML test corpus

`FpmlEndToEndTest` runs schema-aware queries on the official FpML confirmation view: its schemas and its product
examples.

## Source

- **What:** FpML 5.12 Recommendation (20 September 2021, Build 4), confirmation view.
  - This is the archive `confirmation-5-12_xml.zip`, which has the schemas and the examples.
- **URL:** <https://www.fpml.org/spec/fpml-5-12-4-rec-1/xml/confirmation-5-12_xml.zip>
  - The confirmation-view documentation on fpml.org links to it, from section 1.2 of
    `https://www.fpml.org/spec/fpml-5-12-4-rec-1/html/confirmation/fpml-5-12-intro-1.html`.
  - It is served without registration.
- **SHA-256:** `90c091f1137339cd5debfb396bc0b943d023a09f4395246ebb4bbd3d84b3b6c9`. The test verifies it before
  using the archive.
- **Licence:** FpML® Public License Version 3.0 (effective 1 February 2026), published by ISDA at <https://www.fpml.org/license/license.html>.
  - The schema headers refer to this licence.
  - FpML is a registered trademark of ISDA.

The URL, the checksum and the version are the `fpml.*` properties of this module's `pom.xml`.

## Why the files are downloaded and not committed

None of the FpML files are committed to this repository. The FpML Public License does allow redistribution, but the
project decided not to redistribute third-party standards material from a public repository. If a reading of the
licence turned out to be wrong, the redistribution could not be undone.

How the download works:

- The `fpml-confirmation-view` execution of `download-maven-plugin` runs in `generate-test-resources`.
  - It only downloads the pinned archive, to `target/fpml/confirmation-5-12_xml.zip`, through the plugin's cache in
    the local Maven repository, so the download happens once per machine.
  - It does not check the checksum or unpack. Plugin 1.13.0 would fail the build on a checksum mismatch even with
    `failOnError=false`.
  - `-Dfpml.skip=true`, `-DskipTests` and `-Dmaven.test.skip=true` skip the download.
  - A failed download (no network, offline, HTTP error) is only a warning.
- `FpmlEndToEndTest` then works in two steps.
  - It computes the archive's SHA-256 and compares it with `fpml.sha256`.
  - Only when they match does it empty `target/fpml/unpacked/` and unpack the schemas and `products/` examples into
    it. Entries whose path would leave that directory are rejected.
- **When the archive is missing:** the test skips itself (a JUnit assumption) and the build does not fail.
- **When the checksum does not match** (for example, if ISDA republishes the file at the same URL):
  - The test logs a warning with the expected and actual SHA-256 and skips itself.
  - The mismatched archive is never unpacked or used, and the build does not fail.
  - To move on, check the new archive, update `fpml.sha256` (and `fpml.url` / `fpml.version` if needed) in this
    module's `pom.xml`, and delete the cached copy: `target/fpml/` and
    `~/.m2/repository/.cache/download-maven-plugin/`.

## Corpus

- **Schema:** `fpml-main-5-12.xsd`. It brings in the whole view: about 37 schema documents, with deep includes, an
  xmldsig import, large substitution groups (`product` has about 50 members) and `xs:ID` / `xs:IDREF`.
- **Documents:** every example under `confirmation/products/`. That is about 445 documents across all product families:
  IRD, FX, credit, equity options and swaps, commodity, repo, loan, inflation, variance, volatility, correlation,
  dividend and bond options.
- The intentionally invalid examples are in `confirmation/validation/` and are not used.
