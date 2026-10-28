# Schema-Aware XPath/XQuery for Stored Documents — Design

- **Status:** Approved design, pending implementation plan
- **Date:** 2026-10-28
- **Branch:** `feature/schema-aware-xquery`
- **Primary use case:** FpML (large, multi-file, substitution-group-heavy XSD); design is generic for any XSD 1.0/1.1 schema.

## 1. Goals and scope

### Goals

1. **Typed values** — atomizing a stored node yields its schema-typed value (e.g. `//fpml:amount > 1e6` compares as `xs:decimal`, dates as `xs:date`) without explicit casts.
2. **Type-based navigation** — `element(N, T)`, `element(*, T)`, `attribute(N, T)`, `schema-element(N)`, `schema-attribute(N)` match on type annotation and substitution-group membership.
3. **Faster queries** — a schema-type index answers type-based steps and typed value comparisons without scanning.

### In scope (v1)

- Stored documents in collections whose `collection.xconf` configures a schema.
- `import schema` (static context for type names).
- User-defined atomic, list and union types in casts, constructor functions, sequence types and function signatures.
- `fn:node-type-annotation`, `fn:atomic-type-annotation` (QT4 F&O), a `schema:` diagnostics module.

### Out of scope (v1)

- `validate {}` expression (continues to raise `XQST0075`).
- Typing of query-constructed (in-memory) nodes; `construction preserve` type semantics.
- The Static Typing Feature (e.g. `XPST0005` for impossible paths).
- Replacing the existing validation facility — strict validation remains `<validation mode="yes">` in `conf.xml`.

### Non-goals / invariants

- Collections without `<schema-type>` behave **exactly** as today (nodes atomize to `xs:untypedAtomic`).
- No change to the on-disk DOM node format.

## 2. Current state (findings from code review)

| Area | Current behavior | Location |
|------|------------------|----------|
| `import schema` | Parsed, then rejected with `XQST0009` | `XQueryTree.g:967` |
| `validate {}` | Rejected at parse time with `XQST0075` | `XQuery.g:1585` |
| `schema-element()` | Parsed, silently ignored | `XQueryTree.g:1482, 2992, 3053` |
| `element(*, T)` | **Bug:** the type name replaces the node test (`test = new TypeTest(Type.getType(qname))`) | `XQueryTree.g:2983` (and siblings at 1473, 2876, 2911) |
| Type system | Fixed registry of ~69 built-in types (`superTypes = new int[69]`), no user-defined types | `org.exist.xquery.value.Type` |
| Node atomization | Always `xs:untypedAtomic` | `NodeProxy.java:777`, `memtree/NodeImpl.java:514` |
| `getSchemaTypeInfo()` | Throws unsupported | `persistent/ElementImpl.java:1962`, `persistent/AttrImpl.java:385` |
| Node storage | Element record has no type annotation field | `persistent/ElementImpl.serialize()` |

Building blocks already present:

- Xerces 2.12.2 (`xml-schema-1.1` classifier) — provides `XSModel`, `XSSimpleTypeDefinition.validate`, and the derivation API.
- `org.exist.validation.GrammarPool`, catalog resolver, `Xsd11ValidationHelper`.
- Index plugin framework: `IndexWorker`, `StreamListener` (`startElement`/`attribute`/`endElement` with `NodePath`), `IndexController.getQueryRewriters`, `QueryRewriter.rewriteLocationStep` (used by `RangeQueryRewriter`).

## 3. Key insight: type assignment without full validation

XSD's *Element Declarations Consistent* constraint guarantees that, within one content model, all element particles with the same expanded name have the same type. Therefore a node's type is a function of:

- the parent's type,
- the node's expanded QName,
- an `xsi:type` attribute on the node (if any),

with wildcards and substitution groups resolved against global declarations. Type assignment is a single top-down pass with a stack, not a validation run.

## 4. Approach

Three approaches were considered:

| | A. Inline annotations in node records | **B. Schema-type index module (chosen)** | C. Query-time derivation, nothing persisted |
|-|-|-|-|
| Storage format change | Yes (migration) | No | No |
| Store-time cost | Low | Low–moderate | None |
| Query-time cost | Lowest | Low (lookup + cache) | High (ancestor walk) |
| Enables index acceleration | Partially | Yes | No |
| Schema change | Rewrite documents | Reindex | Immediate |

**Chosen: B.** It matches collection-config binding, leaves core storage untouched, and is the only option delivering faster queries. Core consumes annotations through a narrow `TypeAnnotationProvider` interface so A could later replace B without changing query semantics.

## 5. Components

| # | Component | Module | Responsibility |
|---|-----------|--------|----------------|
| 1 | **Extensible type system** | `exist-core` (`org.exist.xquery.value`) | Register user-defined types alongside built-in `Type` constants; derivation checks via `Type.subTypeOf` for user types; user-defined atomic value wrapper. |
| 2 | **Schema registry** | `exist-core` (new `org.exist.xquery.schema`) | Load/compile/cache `XSModel` per schema location + checksum via `GrammarPool` and catalog; precompute per-complex-type child maps, substitution-group closures, derivation tables. Immutable, shared, LRU-bounded. |
| 3 | **`TypeAnnotationProvider`** | `exist-core` (interface) | `typeOf(NodeHandle) → TypeRef or none`; implemented by the index module; no-op default. |
| 4 | **Type assigner** | new `extensions/indexes/schema-type` | Store-time `StreamListener`: stack-based assignment (§6.2), lexical/facet checks (§8.3). |
| 5 | **Type index** | `extensions/indexes/schema-type` | BTree tables (§6.3); `IndexWorker` lifecycle; `QueryRewriter` (§7.5). |
| 6 | **Query engine changes** | `exist-core` (grammar + expressions) | `import schema`; typed atomization; correct `element/attribute/schema-element/schema-attribute` tests; casts/constructors for user types; fix `element(*, T)` bug. |
| 7 | **Diagnostics** | `exist-core` (`fn:`), new `schema:` module | `fn:node-type-annotation`, `fn:atomic-type-annotation`; `schema:status`, `schema:unassignable`, `schema:types`. |

## 6. Data flow and storage

### 6.1 Configuration (`collection.xconf`)

```xml
<index xmlns:fpml="http://www.fpml.org/FpML-5/confirmation">
  <schema-type href="xmldb:exist:///db/schemas/fpml-5-13/fpml-main-5-13.xsd"
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
```

| Attribute | Default | Meaning |
|-----------|---------|---------|
| `href` | required | Root XSD; includes/imports resolved via catalog and database URIs. |
| `on-unassignable` | `untyped` | `untyped`: leave node and subtree unannotated, flag document *partially typed*. `reject`: fail store with `EXSA0004`. |
| `check-values` | `true` | Check lexical values against simple types at store time (§8.3). |
| `allow-remote` | `false` | Permit fetching includes/imports over the network. |
| `reindex-on-schema-change` | `false` | Automatically reindex when the schema checksum changes. |

`<typed-values>` is opt-in per type; only listed types get typed-value index entries.

### 6.2 Store time

The `StreamListener` keeps a stack of *(element declaration, type)*. For each element:

1. If `xsi:type` is present and is a valid derivation of the declared type (respecting `block`), use it.
2. Else look up the child QName in the parent type's precomputed child map.
3. Else resolve via wildcard (`strict`/`lax`/`skip` per `processContents`) or substitution-group membership against global declarations.
4. Else the node is *unassignable* (§8.2).

Attributes are resolved from the element type's attribute uses, then attribute wildcards. `xsi:nil="true"` on a nillable element marks it nilled.

### 6.3 Index tables

| Table | Key → Value | Notes |
|-------|-------------|-------|
| Annotation | `(docId, nodeId) → typeId` (+ nilled flag) | Only non-default annotations stored; `xs:untypedAtomic` / `xs:anyType` implied. |
| Type → nodes | `(typeId, docId) → nodeId set` | Lookup for `element(*, T)`; abstract base types expand over the derivation table. |
| Typed values | `(typeId, typed key) → nodeIds` | Only for `<typed-values>` types; key encoding as in the range index (correct ordering). |
| Metadata | collection → schema checksum + type table; document → checksum indexed with, typing status | Type IDs persisted so they are stable across restarts. |

Document remove/reindex/replace clear and rebuild entries via the standard `IndexWorker` methods.

### 6.4 Type IDs

Built-in types keep their `Type` constants. User-defined types are allocated IDs above a reserved base, per schema, with name, base type and derivation method persisted in index metadata.

### 6.5 Query time

`NodeProxy.atomize()` consults the `TypeAnnotationProvider`:

- simple type, or complex type with simple content → parse string value into the type; list → sequence; union → first accepting member;
- nilled → empty sequence;
- mixed content → `xs:untypedAtomic`;
- element-only content → `FOTY0012`;
- no annotation → `xs:untypedAtomic` (unchanged).

Annotation lookups are cached per query per document. All features work without the optimizer; indexes only affect speed.

## 7. Query semantics

### 7.1 `import schema`

- `import schema namespace p = "ns" at "loc";` loads the schema through the registry and puts its types and global element/attribute declarations into the static context.
- Without `at`: resolve the namespace against schemas configured on collections, then the catalog; failure → `XQST0059`.
- Duplicate namespace import → `XQST0058`; prefix with empty namespace → `XQST0057`.
- `XQST0009` is removed.
- **Typed atomization does not require `import schema`.** The import is needed only to *name* schema types/declarations in a query; naming one without it → `XPST0008`.

### 7.2 Typed values

`fn:data`, general and value comparisons, `order by`, `group by`, `distinct-values`, arithmetic and function-argument conversion use typed values. `fn:string()` and string values are unchanged.

User-defined atomic values carry their type: a wrapper over the built-in base value, so `instance of fpml:NonNegativeDecimal` holds, while arithmetic follows the primitive base (result `xs:decimal`).

**Migration note (documented):** on configured collections, comparisons that previously relied on `xs:untypedAtomic` promotion may now raise `XPTY0004` (e.g. `amount = "1000"` where `amount` is `xs:decimal`).

`fn:id`, `fn:idref`, `fn:element-with-id` honor schema-typed `xs:ID`/`xs:IDREF` (e.g. FpML `@id`/`@href`).

### 7.3 Type tests

- `element(N, T)` / `element(*, T)` match when the annotation is `T` or derived from it; `T?` additionally matches nilled elements.
- `attribute(N, T)` likewise.
- `schema-element(N)` matches `N` or any non-abstract transitive member of its substitution group whose type derives from `N`'s declared type; `schema-attribute(N)` likewise for attribute declarations.
- Undeclared names → `XPST0008`.
- Fix: `element(*, T)` must construct a combined name+type test, not replace the test with `TypeTest(Type.getType(qname))`.

### 7.4 Casts and constructors

`cast as`, `castable as` and constructor functions (`fpml:NonNegativeDecimal("5")`) work for user-defined atomic and union types: cast to the primitive base, then validate facets via Xerces `XSSimpleTypeDefinition`; failure → `FORG0001`. List-type casts only as permitted by XQuery 3.1. Imported type names are usable in function signatures and variable declarations.

### 7.5 Optimization (`QueryRewriter`)

- `//element(*, T)` and `//schema-element(N)` → type→nodes lookup (with derivation/substitution expansion).
- Value comparisons and general comparisons on paths whose annotated type is in `<typed-values>` → typed-value range lookups.
- Fallback to normal evaluation when the index cannot answer; results must be identical.

### 7.6 Type identity and versioning

A stored annotation and a query type are identical iff they have the same expanded QName **and** come from the same schema (checksum of the resolved schema document set). If a query imports a different schema version for a namespace than a collection was indexed with → `EXSA0001` (hard error, never silently matched).

## 8. Error handling and operations

### 8.1 Configuration and schema loading

- Storing `collection.xconf` compiles the schema; failure rejects the configuration with the Xerces location and message.
- A schema unavailable at startup or first use is logged at ERROR; stores into the collection fail with `EXSA0002`; queries atomizing its nodes raise `EXSA0002`.
- Includes/imports resolve only through database URIs and the catalog; network fetch off unless `allow-remote="true"`; DTDs and external entities disabled during schema parsing (XXE protection).

### 8.2 Unassignable nodes

Unknown child, invalid `xsi:type` derivation, or a strict wildcard with no matching declaration:

- `on-unassignable="untyped"`: node and subtree unannotated, document flagged *partially typed*, WARN with node path.
- `on-unassignable="reject"`: store fails with `EXSA0004` (node path + reason).

### 8.3 Lexical value checks

With `check-values="true"`, each simple-typed value is checked at store time; an invalid value makes the node unassignable (§8.2). Consequently atomizing a stored node never fails at query time because of a bad value.

With `check-values="false"`, invalid values surface at query time as `FORG0001` (trusted bulk loads).

**Expected overhead (to be confirmed by benchmark):** ~1 µs or less per value for plain parses and min/max/length checks; enumerations are a hash lookup; `pattern` facets can take several µs. Estimated 1–30 ms per typical FpML document, i.e. ~5–20% of store throughput. Queries pay nothing extra.

Mitigations:

1. **Facet tiers** — types without facets beyond their primitive base get only the primitive parse; the full Xerces check runs only for types with patterns or enumerations.
2. **Shared parse** — for `<typed-values>` types, the parse that builds the index key also performs the check.
3. **Pattern cache** — per-document `(typeId, lexical) → valid` cache (FpML repeats currencies, scheme URIs, codes).

**Acceptance criterion:** full checks cost ≤ 25% store time over no type index (§9.6).

### 8.4 Schema changes and staleness

The index records the collection's schema checksum and each document's indexed-with checksum. After an XSD or configuration change, the next query touching an affected document raises `EXSA0003` until the collection is reindexed (or automatically if `reindex-on-schema-change="true"`). Restore rebuilds the index via reindex, as for other index modules.

### 8.5 Concurrency and memory

- Compiled schemas are immutable, keyed by checksum, shared across queries and threads.
- Index reads/writes use existing BTree locking; no new lock types.
- Registry cache is LRU-bounded via `conf.xml` (default 8 schemas); evicted schemas are recompiled when next needed. Expected FpML footprint: tens of MB.

### 8.6 Error codes

Added to `ErrorCodes.java` in a labeled block:

```java
// --- Schema Aware error codes ---
EXSA0001  // query imports a different schema than the collection was indexed with
EXSA0002  // schema unavailable for collection
EXSA0003  // type index stale; reindex collection
EXSA0004  // unassignable node in reject mode
// --- End Schema Aware error codes ---
```

### 8.7 Diagnostics

- `fn:node-type-annotation($node)`, `fn:atomic-type-annotation($value)` (QT4 F&O).
- `schema:status($doc)` — typed / partial / stale + checksum.
- `schema:unassignable($doc)` — paths of unannotated nodes.
- `schema:types($ns)` — types and derivation hierarchy.

## 9. Testing

Per CLAUDE.md: XQuery-level behavior in XQSuite; storage, index and configuration wiring in Java.

### 9.1 Unit tests (pure JUnit)

- Type assigner over fixture XSDs: substitution groups (chains, abstract heads), `xsi:type` valid/invalid, wildcards `strict`/`lax`/`skip`, `nillable`, same-named local elements under different parents, anonymous types, simple content by extension/restriction, list/union, attribute wildcards.
- Derivation table: extension/restriction chains, `block`/`final`.
- Value checks: facet tiers, pattern cache.
- Typed index keys: encode/decode round-trip; ordering for decimals, dates, durations.

### 9.2 Index tests (Java, real `BrokerPool`)

- Store / remove / replace / reindex keep all tables correct.
- Type IDs and checksums survive restart.
- Bad `collection.xconf` rejected; unloadable schema → `EXSA0002` on store; `on-unassignable` both modes; `check-values="false"`.
- Schema edit → `EXSA0003` until reindex.
- Concurrent stores and queries share compiled schemas safely.

### 9.3 XQSuite

`%test:setUp` stores a fixture XSD, `collection.xconf` and documents into a temporary collection; `%test:tearDown` removes them.

- Typed atomization; list/union values; `fn:nilled`.
- `XPTY0004` behavior change; `order by` / `distinct-values` on typed values.
- `element(N, T)` / `element(*, T)` (including bug regression), `attribute(N, T)`, `schema-element` / `schema-attribute` across substitution groups.
- `cast` / `castable` / constructors for user types; `FORG0001` on facet failure.
- `fn:id` / `fn:idref` with schema-typed IDs.
- `XQST0057` / `XQST0058` / `XQST0059`, `XPST0008`, `EXSA0001`.
- `fn:node-type-annotation`, `fn:atomic-type-annotation`, `schema:` module.
- Differential: each optimized query run with and without the type index must return identical results, and `IndexUseReporter` must show the index was used.

### 9.4 FpML end-to-end

- FpML 5.x confirmation schema and official example documents. **Check the FpML Public License before committing them; if redistribution is not permitted, download during the Maven test setup.**
- Every example document fully typed (zero unassignable nodes).
- Representative queries: notional above a threshold; trades by product via `schema-element(fpml:product)`; `@href` → `@id` party references; date-range filters on `adjustableDate`.

### 9.5 W3C conformance (XQTS)

- Separate PR to `eXist-db/exist-xqts-runner`: declare `schemaImport` and `typedData` features; store schema-bound test sources into a temporary collection configured with that schema.
- Tests requiring `validate {}` or typed constructed nodes remain excluded.
- PR includes an XQTS before/after table.
- Gate: XQ 3.1 (89.7%), QT4 (85.7%) and FTTS scores must not drop.

### 9.6 Regression and performance

- Full `exist-core` suite passes unchanged; collections without `<schema-type>` behave exactly as today.
- Store benchmark in `ci-benchmarks`: FpML examples with (1) no type index, (2) `check-values="false"`, (3) full checks. Gate: (3) ≤ 25% over (1).
- Query benchmark: `element(*, T)` and numeric range filter, type index vs scan.

## 10. Open items

- FpML Public License redistribution terms (affects §9.4 fixture packaging).
- Exact on-disk key layout for the typed-value table (reuse range-index key encoders; decided in the implementation plan).
