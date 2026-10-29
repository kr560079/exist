# Schema type index

The schema type index makes XPath and XQuery schema-aware for **stored documents**. A collection names an XML
Schema in its `collection.xconf`. When a document is stored there, each element and attribute gets a type
annotation from that schema. Queries then see typed values: `//amount > 1e6` compares `xs:decimal` values and
dates compare as `xs:date`. Type tests also work: `element(*, T)`, `attribute(N, T)`, `schema-element(N)` and
`schema-attribute(N)`.

The index assigns types without validating the whole document. A node's type follows from its parent's type, its
name and any `xsi:type` attribute, so one top-down pass is enough. The design is in
[`docs/superpowers/specs/2026-10-28-schema-aware-xquery-design.md`](../../../docs/superpowers/specs/2026-10-28-schema-aware-xquery-design.md).

Collections without `<schema-type>` behave as before: their nodes atomize to `xs:untypedAtomic`.

- [Installation](#installation)
- [Configuration reference](#configuration-reference)
- [Using it in queries](#using-it-in-queries)
- [Error codes](#error-codes)
- [Reindexing](#reindexing)
- [Scope and limits](#scope-and-limits)
- [Compatibility and release notes](#compatibility-and-release-notes)
- [Benchmarks](#benchmarks)

## Installation

The index ships with the distribution and is enabled in `etc/conf.xml`:

```xml
<module id="schema-type-index" enabled="yes" file="schema-type.dbx"
        class="org.exist.indexing.schematype.SchemaTypeIndex"/>
```

Its data lives in `schema-type.dbx` in the data directory. The `schema:` diagnostics module
(`http://exist-db.org/xquery/schema-type`) is registered through the Java SPI and needs no `conf.xml` entry.

Compiled schemas are shared through the schema registry, configured in `conf.xml` under `<xquery>`:

```xml
<schema-registry cache-size="8" allow-remote="false"/>
```

| Attribute | Default | Meaning |
|-----------|---------|---------|
| `cache-size` | `8` | The maximum number of compiled schemas kept in memory. The least recently used one is evicted and compiled again when it is next needed. |
| `allow-remote` | `false` | The server-wide gate for fetching schema includes and imports over http(s). A collection's `allow-remote="true"` and remote `import schema` hints are refused unless this is `true`. |

## Configuration reference

```xml
<collection xmlns="http://exist-db.org/collection-config/1.0">
  <index xmlns:fpml="http://www.fpml.org/FpML-5/confirmation"
         xmlns:xs="http://www.w3.org/2001/XMLSchema">
    <schema-type href="xmldb:exist:///db/schemas/fpml-5-12/fpml-main-5-12.xsd"
                 on-unassignable="untyped"
                 check-values="true"
                 allow-remote="false"
                 reindex-on-schema-change="false"/>
    <typed-values>
      <type name="xs:decimal"/>
      <type name="xs:date"/>
      <type name="fpml:NonNegativeDecimal"/>
    </typed-values>
  </index>
</collection>
```

### `<schema-type>`

| Attribute | Default | Meaning |
|-----------|---------|---------|
| `href` | required | The root XSD. Includes and imports are resolved through database URIs and the catalog. |
| `on-unassignable` | `untyped` | `untyped` leaves a node that cannot be typed, and its subtree, unannotated, and flags the document *partially typed* (WARN with the node path). `reject` fails the operation with `EXSA0004` instead. See [Reject mode](#reject-mode). |
| `check-values` | `true` | Checks each simple-typed value against its type when the document is stored. An invalid value makes the node unassignable. See [`check-values="false"`](#check-valuesfalse). |
| `allow-remote` | `false` | Allows the schema's includes and imports to be fetched over the network. It has no effect unless the server allows it too (`<schema-registry allow-remote="true"/>`). |
| `reindex-on-schema-change` | `false` | Reindexes the collection automatically when its schema changes. See [Reindexing](#reindexing). |

Boolean attributes take `true`/`false` (or `1`/`0`). Any other value, or another `on-unassignable` value, rejects the
configuration.

Storing the `collection.xconf` compiles the schema. If the schema cannot be compiled, the configuration is rejected
with the Xerces location and message. While compiling, DTDs and external entities are disabled (XXE protection), and
network fetches are off unless both `allow-remote` settings say otherwise.

The schema is loaded as the **owner of the `collection.xconf` document**. That owner must be able to read the schema
documents. Automatic reindexing also runs as that owner.

### `<typed-values>`

`<typed-values>` is optional. Each `<type name="..."/>` lists a type whose nodes get typed-value index entries, so
that comparisons on them can use the index. The name is a built-in `xs:` type or a type the schema defines.
Prefixes are resolved from the namespace declarations in scope. An unknown type or an unbound prefix rejects the
configuration.

Typed values are opt-in because they add store time and index size. See [Benchmarks](#benchmarks). Without
`<typed-values>`, queries still see typed values: atomization parses the string value with the annotated type. Only
the index acceleration of value comparisons is missing.

### `check-values="false"`

With `check-values="true"` (the default), every simple-typed value is checked when the document is stored. An
invalid value makes its node unassignable: it is left untyped, or the store is rejected in reject mode. As a result,
atomizing a stored node never fails at query time because of a bad value.

With `check-values="false"`, values are not checked when stored. Use it for trusted bulk loads. The trade-off:

- An invalid value is still annotated with its type.
- Atomizing it raises `FORG0001` at query time, in every query that touches it.
- Storing is only slightly cheaper. On FpML the value checks cost about 3% of store time over
  `check-values="false"`; assigning the types costs much more. See [Benchmarks](#benchmarks).

### Where to store the schemas

Store the XSDs **outside the data collection**, for example in `/db/schemas/...`, and configure the collection before
storing documents. Then nothing needs reindexing.

Two situations leave documents stale. Their queries raise `EXSA0003` until the collection is reindexed:

- XSDs stored inside the configured collection before the `collection.xconf`, because they were indexed as
  documents of an unconfigured collection;
- documents stored before the configuration.

In both cases, reindex after configuring.

## Using it in queries

### Typed values

Typed atomization needs no `import schema`. It applies wherever a stored node is atomized: `fn:data`, general and
value comparisons, `order by`, `group by`, `distinct-values`, arithmetic and function-argument conversion.

- A simple type, or a complex type with simple content, gives the parsed value. A list type gives a sequence; a
  union type gives the value of the first member type that accepts it.
- A nilled element gives the empty sequence.
- Mixed content gives `xs:untypedAtomic`.
- Element-only content raises `FOTY0012`.
- A node without an annotation gives `xs:untypedAtomic`, as before.

`fn:string()` and string values do not change. Values of user-defined atomic types keep their type:
`instance of fpml:NonNegativeDecimal` holds. Arithmetic on them follows the primitive base type, so the result is an
`xs:decimal`.

### Naming schema types

To name a schema type or declaration in a query, import the schema:

```xquery
import schema namespace fpml = "http://www.fpml.org/FpML-5/confirmation";
collection('/db/fpml')//element(*, fpml:Product)
```

- **Without a location hint**, the namespace resolves only to schemas configured on collections that the caller can
  READ. The registry's cache is never used for this, so schemas that other users' queries loaded stay invisible.
  - If the readable configurations name one schema version, that one is used.
  - If they name several, the import fails with `XQST0059` ("ambiguous"), listing the collections. Use a hint.
  - If none does, the catalog is tried; failing that, `XQST0059`.
- **With a location hint** (`at "xmldb:exist:///db/schemas/..."`), the caller needs read permission on every
  database document the schema consists of, whether or not the schema is cached.
- **Remote hints** (`http:`, `https:`) require a dba caller and `<schema-registry allow-remote="true"/>`.
  - Remote fetches time out (10 s connect, 30 s read).
  - The `XQST0059` message names only the URI and a generic reason.
- A compiled query from the query pool re-checks these permissions for the user who borrows it. It is recompiled if
  a schema document changed.

A query that imports a different version of a schema than a collection's documents were annotated with fails with
`EXSA0001`. Two versions are never matched silently.

Imported type names can be used in casts, `castable as`, constructor functions (`fpml:NonNegativeDecimal("5")`),
sequence types and function signatures. A value that fails the facets raises `FORG0001`.

### Type tests

- `element(N, T)` and `element(*, T)` match when the annotation is `T` or a type derived from it. `T?` also matches
  nilled elements. `attribute(N, T)` works the same way.
- `schema-element(N)` matches `N` and the non-abstract members of its substitution group whose type derives from
  `N`'s. Substitution blocked with `block` is respected. `schema-attribute(N)` works the same way for attribute
  declarations.
- An undeclared name raises `XPST0008`.

### IDs

`fn:id`, `fn:idref` and `fn:element-with-id` honour schema-typed `xs:ID` / `xs:IDREF` / `xs:IDREFS`, on attributes and
elements, for example FpML's `@id` / `@href`.

### Diagnostics

| Function | Result |
|----------|--------|
| `fn:node-type-annotation($node)` | The node's type annotation (QT4 F&O). |
| `fn:atomic-type-annotation($value)` | The type annotation of an atomic value (QT4 F&O). |
| `schema:status($doc)` | A map. `status` is one of `typed`, `partial`, `stale`, `unavailable` or `untyped` (in memory, or no schema). `schema` is the checksum of the schema the document was indexed with, if it has index entries. It never raises for the condition it reports. |
| `schema:unassignable($doc)` | The paths of the roots of untyped subtrees, at most 100. |
| `schema:types($namespace)` | The named types of a schema, base types first, as `<type name base variety/>` elements. |

`schema:types` has visibility rules:

- It shows only schemas that the query imported, or that a hint-less import would find (configured on collections the
  caller can READ).
- For other namespaces it returns the empty sequence, so it reveals nothing about schemas the caller cannot see.
- It also returns the empty sequence for an unavailable or ambiguous schema; `schema:status` says why.

Declare the module with `import module namespace schema = "http://exist-db.org/xquery/schema-type";`.

## Error codes

| Code | When |
|------|------|
| `EXSA0001` | The query imports a different schema version than the documents were annotated with. |
| `EXSA0002` | The collection's schema is unavailable: it could not be loaded at startup or at first use. The collection stays configured. Stores into it fail, and queries that atomize its nodes fail, until the schema loads again (logged at ERROR). |
| `EXSA0003` | The document's type index is stale: it was indexed with another schema or configuration, or not at all. [Reindex](#reindexing) the collection. |
| `EXSA0004` | `on-unassignable="reject"`: a node cannot be assigned a type. The message gives the node's positional path (e.g. `/t:trades[1]/t:trade[1]/t:bogus[1]`) and the reason. |
| `FOTY0012` | Atomizing an element with element-only content. |
| `FORG0001` | A cast or constructor violates a facet, or a stored value is invalid under `check-values="false"`. |
| `XPTY0004` | A comparison that untyped data used to allow is now a type error (see [Migration](#migration-xpty0004-on-configured-collections)). Also raised for a node that does not match a required sequence type with a schema type. |
| `XQST0052` | A cast or constructor to a schema type that is not a simple type. |
| `XQST0057` | `import schema` binds a prefix to the empty namespace. |
| `XQST0058` | Two `import schema` declarations for the same namespace. |
| `XQST0059` | No schema can be loaded for the namespace: none found, ambiguous, not readable, or a remote hint not allowed. |
| `XPST0008` | An undeclared schema type, element or attribute name. |

`EXSA0002`, `EXSA0003` and `EXSA0004` messages name the schema location, the collection and the cause only for
callers who can READ the collection. Other callers get a generic message.

### Reject mode

With `on-unassignable="reject"`, every operation that writes a node the schema cannot type fails with `EXSA0004`, and
nothing of it is kept:

- storing a document;
- XQuery Update and the legacy `update` expressions (the update is rolled back);
- replacing a document, including its root;
- copying or moving a document into it;
- **restoring a backup** into it. A restore stores documents as a client does, so it is a store like any other.
  Backed-up documents that do not fit the schema fail to restore into a reject collection. Restore them with
  `on-unassignable="untyped"` first, or fix them. This refines the spec's note that "restore rebuilds the index via
  reindex".

**A reindex never rejects.** An explicit reindex, or the automatic one, leaves a node that does not fit untyped,
flags the document partially typed and logs a WARN. Otherwise one legacy document would make the collection
impossible to reindex.

Moving a whole **collection** into a reject collection is not vetoed. Documents that do not fit are found by the next
reindex: they are left untyped there, and are stale (`EXSA0003`) until then.

## Reindexing

The index stores, for each document, the checksum of the schema it was indexed with. Reindex a collection:

- after changing its schema (any XSD of the schema) or its `<schema-type>` / `<typed-values>` configuration;
- after storing documents or XSDs into it before configuring it (see [Where to store the schemas](#where-to-store-the-schemas));
- after upgrading from a build with an older index layout (see the [release notes](#compatibility-and-release-notes));
- whenever `schema:status` reports `stale`, or queries raise `EXSA0003`.

Run the reindex as a user who may reindex the collection:

```xquery
xmldb:reindex('/db/fpml')
```

With `reindex-on-schema-change="true"`, the reindex is automatic. It rebuilds only the schema type index of the
documents governed by that `<schema-type>`: the collection and the subcollections that inherit its configuration, not
subcollections with a `collection.xconf` of their own. It happens in two cases:

- **Storing a `collection.xconf` with a changed schema** reindexes the governed documents once the configuration is
  committed.
- **A query that meets a stale document** still raises `EXSA0003`, and also schedules an asynchronous reindex of the
  collection. The reindex is scheduled once per collection and schema version, and runs as the `collection.xconf`
  owner. It never runs inside the query, and never as the system user. Queries succeed once it has finished.

An automatic reindex skips documents the owner may not read, with a WARN.

The index is derived data and can always be rebuilt from the documents.

## Scope and limits

- **Stored documents only.** Nodes constructed by a query are not typed: they atomize to `xs:untypedAtomic`.
  `construction preserve` has no type semantics. `validate {}` still raises `XQST0075`.
- **Not a validator.** Type assignment is not full validation. Strict validation stays with
  `<validation mode="yes">` in `conf.xml`.
- **Static typing** is not implemented, so impossible paths do not raise `XPST0005`.
- **Index acceleration.** All features work without the optimizer; the index only makes some queries faster, and the
  results are the same either way. These forms are answered from the index:
  - descendant steps with `element(*, T)`, `attribute(*, T)`, `schema-element(N)` and `schema-attribute(N)`, from the
    type-to-nodes table, with derived types and substitution groups expanded;
  - a descendant step with a name and a **first predicate** that compares `.`, a child `M` or an attribute `@A`
    with literals, by a general comparison `=`, `<`, `<=`, `>` or `>=`. For example `//fpml:initialValue[. > 1e6]` or
    `//t:notional[t:amount > 5e5]`. The answer comes from the typed-value table, for documents in which every node
    with the compared name has a type listed in `<typed-values>`; the other documents are evaluated as usual.
- **Evaluated without the index:**
  - `element(N, T)` with a name. It is answered from the structural index by name and then filtered by type, which is
    cheaper for selective names.
  - Multi-step compared paths, such as `//trade[header/date > ...]`.
  - Child steps, and predicates other than the first.
  - `!=`; value comparisons (`eq`, `lt` …); comparisons of list-typed values with `=`.
  - Literals that do not compare with the annotated type without a cast. For example, a string literal against an
    `xs:decimal` node stays `XPTY0004`.
  - Durations, and compared names with types not listed in `<typed-values>`.
- **Backwards-compatible (XPath 1.0) mode** skips the dynamic type checks, as before.
- **Query-time cost:** annotation lookups are cached per query and document. The [query benchmark](#benchmarks)
  shows where the index helps and where it does not.

## Compatibility and release notes

### Migration: `XPTY0004` on configured collections

On configured collections, comparisons that relied on `xs:untypedAtomic` promotion may now raise `XPTY0004`. For
example, `amount = "1000"` fails where `amount` is an `xs:decimal`. Compare with a number or cast explicitly:
`amount = 1000`, `string(amount) = "1000"`. Collections without `<schema-type>` are not affected.

### Behaviour changes in the query engine (all collections)

These follow the XQuery 3.1 specification. Queries that relied on the old, lenient behaviour may now fail.

- `element(*, xs:string)`, `attribute(id, xs:string)` and similar tests now check the type annotation.
  - Unvalidated nodes (annotated `xs:anyType` / `xs:untypedAtomic`) no longer match.
  - In function parameters and return types, and in `let`/`declare variable` type declarations, such a node raises
    `XPTY0004`.
  - Previously the type name replaced the node test (`element(*, T)` was a bug).
- `element(N)` function parameters are name-checked in every case, including when only the cardinality used to be
  checked.
- `attribute(N, T?)` is a syntax error (`XPST0003`); `?` (nillable) is only allowed for `element()`.
- `document-node(element(N))` tests now filter on the document element. Before, the element test was ignored.
- `import schema` no longer raises `XQST0009`.
  - A hint-less import compiled before a `collection.xconf` for the namespace exists fails with `XQST0059`: use an
    `at` hint, or configure the collection first.
- Casts to schema types use the error codes of the XQuery 3.1 text: `XQST0052` for a target type that is not a simple
  type, and `XPTY0004` for a list-type cast from a source that is not `xs:string` or `xs:untypedAtomic`.
- Paths with a node test that can never match (`FailTest`, for example a type test no node can pass) are no longer
  rewritten by the range-index optimizer.

### Index layout

- **Index format change: reindex required.** The typed-value tables `0x04`/`0x05` of earlier builds of this branch
  were replaced by `0x07` (name summaries), `0x08` (typed values in value order, per document) and `0x09` (untyped
  nodes per name).
  - Documents indexed with the old layout keep working, but their comparisons are evaluated without the index.
  - Reindex to get the new layout; the old rows are removed when a document's entries are rewritten.
  - The layout version is part of each document's typed-values fingerprint (`TYPED_VALUES_FORMAT`).

### `fn:id` and related functions

- On a stale document, `fn:id` raises `EXSA0003`, even for an `xml:id` lookup.
- When several elements have the same ID, all of them are returned. This is eXist's existing behaviour; the
  specification says the first.

### Store-time cost for unconfigured collections

Earlier builds of this branch did per-node work in the index's stream listener even for documents of unconfigured
collections. That work is gone. See [Benchmarks](#benchmarks).

### Known eXist issues found along the way (not changed here)

These are pre-existing bugs, to report upstream:

- `xs:dayTimeDuration` fractional-second comparisons: `P1D eq P1DT0.5S`, `-PT0.5S gt PT0S`.
- `xs:double` uses `Double.compare`, so `-0e0 = 0e0` is false.
- `setAttribute` does not signal `REMOVE_SOME_NODES` to other indexes, so old attribute values are never dropped.

## Benchmarks

Two benchmarks live in `src/test/java/org/exist/indexing/schematype/`. They follow the pattern of
`NGramContainsBenchmark`: they are plain JUnit classes (not `*Test`), skipped unless `-Dexist.run.benchmarks=true`
is given. They also run in the weekly `ci-benchmarks` workflow.

```bash
mvn test -pl extensions/indexes/schema-type -am -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest='SchemaTypeStoreBenchmark,SchemaTypeQueryBenchmark' -Dexist.run.benchmarks=true \
    -Ddependency-check.skip=true -Ddocker=false
```

- **Corpus.** The benchmarks use the FpML 5.12 confirmation examples (445 documents) that the build downloads for
  `FpmlEndToEndTest` (see [`src/test/resources/fpml/README.md`](src/test/resources/fpml/README.md)).
  - When the examples are not there, the benchmarks use the schema tests' trade fixture instead: its first trade
    replicated into documents of about 10,000 nodes each. `-Dschema-type.bench.trade-docs=N` sets how many; the
    default is 20.
- **Method.** Each benchmark runs 3 warm-up rounds and 5 measured rounds, and reports the median.
  - Each store round stores the corpus once per configuration, one transaction per document, followed by a major
    sync. Both are timed. The order of the configurations rotates from round to round.
  - Each query round runs a query 10 times, compiling it each time.
- **Gate (spec §9.6).** `SchemaTypeStoreBenchmark` fails if storing into a collection with the `<schema-type>`
  defaults costs more than **25%** over no schema type index. Configuration (4), with `<typed-values>`, is reported
  against the same 25% target but does not fail the benchmark.

### Results

The results below are from an Apple M5 Pro (18 cores), JDK 25 (Temurin), on 2026-10-29.

**Store, FpML (445 docs):**

| Configuration | Median (ms) | ms/doc | Over (1) |
|---------------|------------:|-------:|---------:|
| (1) no `<schema-type>` | 278.1 | 0.625 | — |
| (2) `check-values="false"` | 412.4 | 0.927 | +48.3% |
| (3) defaults (`check-values="true"`) | 423.4 | 0.951 | **+52.2%** (gate: 25%) |
| (4) defaults + `<typed-values>` (`xs:decimal`, `xs:date`, `fpml:NonNegativeDecimal`, `fpml:IdentifiedDate`) | 468.1 | 1.052 | +68.3% (target: 25%) |

**Store, trade fixture (20 docs of about 10,000 nodes):** (1) 279.9 ms; (2) 382.6 ms (+36.7%); (3) 393.2 ms
(+40.5%); (4) 432.7 ms (+54.6%).

Between runs, configuration (3)'s overhead varies by a few points: another run of the same code measured +47.9%.

**The gate does not pass yet.** The remaining cost is mostly writing one annotation entry per typed node to the
index file (a B-tree key plus a data-page value each), plus the per-type node lists.

The optimizations made so far, measured on the FpML corpus with configuration (3):

| Optimization | Before (ms) | After (ms) |
|--------------|------------:|-----------:|
| (baseline) no per-node listener work for unconfigured collections; configuration (1) | 446 | 277 |
| Check a memoized schema's documents one collection at a time | 516 | 462 |
| Verify the schema once per store, not twice | 462 | 437 |
| Tee the validation pass's SAX events without reflection | 437 | 427 |
| Skip reads that a first write pass does not need | 427 | 410 |
| Skip name-summary reads after a clear; configuration (4) | 472 | 456 |

The first row lowered the baseline itself. Before it, the gate measured +18%, but only because unconfigured stores
paid for the index too.

**Query, FpML (optimizer on, using the index, vs `declare option exist:optimize "enable=no"`):**

| Query | Items | Index (ms) | No index (ms) | Index used |
|-------|------:|-----------:|--------------:|:----------:|
| `//element(*, fpml:Product)` | 365 | 2.77 | 74.90 | yes |
| `//fpml:notionalAmount[fpml:amount > 500000]` | 30 | 1.55 | 0.88 | no (`fpml:amount` has 13 types, some with element-only content) |
| `//fpml:initialValue[. > 500000]` | 199 | 2.34 | 1.59 | yes |

**Query, trade fixture:**

| Query | Items | Index (ms) | No index (ms) | Index used |
|-------|------:|-----------:|--------------:|:----------:|
| `//element(*, t:Product)` | 8340 | 17.87 | 118.76 | yes |
| `//t:notional[t:amount > 500000]` | 6000 | 14.89 | 13.96 | yes |
| `//t:amount[. > 500000]` | 6000 | 14.72 | 27.85 | yes |

Type tests (`element(*, T)`) gain the most: about 7–27× faster. Typed-value lookups win on large documents with
selective names (`//t:amount`: 1.9×). On many small documents they can be slower than the name scan they replace,
because they read the index per document and type (FpML `//fpml:initialValue`: 0.7×). The no-index column also
switches the other optimizations off.
