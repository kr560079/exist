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
- **SHA-256:** `90c091f1137339cd5debfb396bc0b943d023a09f4395246ebb4bbd3d84b3b6c9`. The build verifies it.
- **Licence:** FpML® Public License, published by ISDA at <https://www.fpml.org/license/license.html>.
  - The schema headers refer to this licence.
  - FpML is a registered trademark of ISDA.

The URL, the checksum and the version are the `fpml.*` properties of this module's `pom.xml`.

## Why the files are downloaded and not committed

None of the FpML files are committed to this repository. The FpML Public License does allow redistribution, but the
project decided not to redistribute third-party standards material from a public repository. If a reading of the
licence turned out to be wrong, the redistribution could not be undone.

How the download works:

- The `fpml-confirmation-view` execution of `download-maven-plugin` runs in `generate-test-resources`.
- It fetches the pinned archive once into the Maven cache, verifies the checksum, and unpacks the schemas and
  `products/` examples into `target/fpml/`.
- When the archive is not there, the test skips itself with a message (JUnit assumption) and does not fail. That
  happens when the download is disabled with `-Dfpml.skip=true`, when the build runs offline, or when the download
  fails.

## Corpus

- **Schema:** `fpml-main-5-12.xsd`. It brings in the whole view: about 37 schema documents, with deep includes, an
  xmldsig import, large substitution groups (`product` has about 50 members) and `xs:ID` / `xs:IDREF`.
- **Documents:** every example under `confirmation/products/`. That is about 445 documents across all product families:
  IRD, FX, credit, equity options and swaps, commodity, repo, loan, inflation, variance, volatility, correlation,
  dividend and bond options.
- The intentionally invalid examples are in `confirmation/validation/` and are not used.
