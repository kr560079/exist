# Schema-Aware XPath/XQuery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stored documents in collections configured with an XSD get schema type annotations that XQuery uses for typed atomization, type-based node tests, user-defined casts, and index-accelerated queries.

**Architecture:** Core (`exist-core`) gains an extensible type registry, a schema registry service compiling Xerces `XSModel`s, and a `TypeAnnotationProvider` interface. A new index module (`extensions/indexes/schema-type`) assigns annotations at store time via `StreamListener`, persists them in a BTree, implements the provider, and contributes a `QueryRewriter`. Grammar and expression changes in core consume the provider.

**Tech Stack:** Java 21 (`maven.compiler.release=21`), Maven, ANTLR 2.7.7, Xerces 2.12.2 (`xml-schema-1.1` classifier, `org.apache.xerces.xs` API), eXist BTree/index framework, XQSuite, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-28-schema-aware-xquery-design.md` — read it before starting any task; section numbers (§) below refer to it.

## Global Constraints

- Branch: `feature/schema-aware-xquery`; push to remote `fork` (`kr560079/exist`), never to `origin`.
- Commit subject prefixes per CONTRIBUTING: `[feature]`, `[bugfix]`, `[test]`, `[doc]`, `[refactor]`; every commit ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- JDK: any JDK ≥ 21. Locally: `export JAVA_HOME=~/.sdkman/candidates/java/25.0.3-tem` (no JDK 21 installed).
- Test command shorthand used below — `mvnt <module> <TestClass>` means:
  `mvn test -pl <module> -am -Dtest="<TestClass>" -Dsurefire.failIfNoSpecifiedTests=false -Ddependency-check.skip=true -Ddocker=false`
  (never build `extensions/webdav` or `exist-xqts` — their dependencies need GitHub Packages auth).
- Collections without `<schema-type>` must behave exactly as today (§1 invariants). No change to the DOM node storage format.
- ANTLR 2 rules from CLAUDE.md apply: never use `"true"`/`"false"` as keyword strings; keep new grammar in a `// === Schema Aware Feature ===` section.
- New error codes in `ErrorCodes.java` inside `// --- Schema Aware error codes ---` / `// --- End Schema Aware error codes ---`:
  `EXSA0001` schema mismatch, `EXSA0002` schema unavailable, `EXSA0003` type index stale, `EXSA0004` unassignable node in reject mode.
- Schema-config edits: any change to `schema/conf.xsd` or `schema/collection.xconf.xsd` bumps that XSD's `xs:schema/@version` (minor bump for additive changes); verify with `mvn -N xml:transform@schema-governance`.
- Defaults (§6.1, §8.5): `on-unassignable="untyped"`, `check-values="true"`, `allow-remote="false"`, `reindex-on-schema-change="false"`, registry LRU `cache-size="8"`.
- Unannotated node semantics: in a collection **without** schema → element `xs:untyped`, attribute `xs:untypedAtomic`; in a configured collection with no stored annotation → element `xs:anyType`, attribute `xs:untypedAtomic`. Both atomize to `xs:untypedAtomic`.
- Typed-value index keys use core's `org.exist.storage.Indexable#serializeValue` encoding (resolves spec §10 open item 2).
- Performance gate (§9.6): full value checks ≤ 25% store-time overhead vs no type index.

## Review Focus

1. **Arithmetic and built-in functions on user-typed values** (`sum(//t:amount)`, `abs`, `round`, `$a * 2`, `format-number`) — expect `xs:decimal` results, not `ClassCastException` from code that casts to `NumericValue`. Test owned by Task 5 (unit) and Task 10 (XQSuite).
2. **XQuery Update / `update insert|replace|delete` on stored typed documents** — newly inserted nodes get annotations, deleted nodes lose them. Test owned by Task 8.
3. **Documents stored before `<schema-type>` was added to the collection** — queries raise `EXSA0003` until reindex; after reindex they are typed. Test owned by Task 9.
4. **Whitespace around typed values** (`<t:amount> 100.50 </t:amount>`) — whitespace facet `collapse` applies, value is valid and atomizes to `100.5`; `xs:string` content keeps whitespace. Test owned by Task 5.
5. **`xsi:type` prefixes declared on an ancestor** (`xsi:type="t:Swap"` where `xmlns:t` is on the root) — resolved against in-scope namespaces of the element. Test owned by Task 7.

---

## Phase 1 — Core groundwork (no behavior change for unconfigured collections)

### Task 1: Fix `element(N, T)` / `element(*, T)` node tests

The parser replaces the node test with `new TypeTest(Type.getType(qname))` when a type name is given (`XQueryTree.g` ~1473, ~2876, ~2911, ~2983). Introduce a proper combined test that works with unannotated nodes now and annotations later.

**Files:**
- Create: `exist-core/src/main/java/org/exist/xquery/TypedNodeTest.java`
- Modify: `exist-core/src/main/antlr/org/exist/xquery/parser/XQueryTree.g` (all `element`/`attribute` + type-name branches in path steps and `sequenceType`)
- Modify: `exist-core/src/main/java/org/exist/xquery/value/SequenceType.java` (add type-annotation fields)
- Create: `exist-core/src/main/java/org/exist/xquery/schema/TypeAnnotations.java`
- Test: `exist-core/src/test/xquery/schema-aware/typed-node-test.xqm`

**Interfaces:**
- Produces:
  - `public TypedNodeTest(XQueryContext context, int nodeKind /* Type.ELEMENT|Type.ATTRIBUTE */, @Nullable QName name /* null = wildcard */, int typeCode, boolean nillable)` implementing `NodeTest`.
  - `SequenceType#setTypeAnnotation(int typeCode, boolean nillable)`, `int getTypeAnnotation()` (default `-1` = none), `boolean isNillable()`.
  - `TypeAnnotations.annotationOf(XQueryContext ctx, NodeValue node) throws XPathException` → `TypeAnnotation` (Task 5 adds the record; in this task return the unannotated semantics from Global Constraints using a temporary package-private record, replaced in Task 5).
  - `TypeAnnotations.matches(XQueryContext ctx, NodeValue node, int typeCode, boolean nillable) throws XPathException` → `Type.subTypeOf(annotation, typeCode)` plus nilled rule.

- [ ] **Step 1: Write the failing XQSuite tests**

```xquery
declare %test:assertFalse function t:untyped-is-not-integer() { <a>1</a> instance of element(*, xs:integer) };
declare %test:assertTrue  function t:untyped-is-anyType()     { <a>1</a> instance of element(*, xs:anyType) };
declare %test:assertTrue  function t:untyped-is-untyped()     { <a>1</a> instance of element(a, xs:untyped) };
declare %test:assertEquals(2) function t:step-anyType()  { count(<r><a/><b/></r>/element(*, xs:anyType)) };
declare %test:assertEquals(0) function t:step-integer()  { count(<r><a/><b/></r>/element(*, xs:integer)) };
declare %test:assertEquals(1) function t:attr-untypedAtomic() { count(<r x="1"/>/attribute(x, xs:untypedAtomic)) };
```

Plus a persistent-node variant (store `<r><a/><b/></r>` in `%test:setUp`, same two step assertions).

- [ ] **Step 2: Run to verify failure**

Create the runner `exist-core/src/test/java/xquery/schemaaware/SchemaAwareTests.java` (`@XQSuite({"src/test/xquery/schema-aware"})`), then run: `mvnt exist-core xquery.schemaaware.SchemaAwareTests`.
Expected: FAIL on `t:step-anyType` / `t:untyped-is-not-integer`.

- [ ] **Step 3: Implement `TypedNodeTest`, `SequenceType` fields, `TypeAnnotations`, and replace the grammar branches**

`SequenceType.checkType(Item)` calls `TypeAnnotations.matches` when `getTypeAnnotation() != -1`.

- [ ] **Step 4: Run `mvnt exist-core xquery.schemaaware.SchemaAwareTests`, then `mvnt exist-core xquery.xquery3.XQuery3Tests`** — Expected: all PASS (XQuery3Tests baseline: 1119 run, 0 failures).

- [ ] **Step 5: Commit** — `[bugfix] Make element(N, T) and attribute(N, T) test the type annotation`

---

### Task 2: User-defined types in the type system

**Files:**
- Create: `exist-core/src/main/java/org/exist/xquery/value/UserTypeRegistry.java`, `UserDefinedType.java`, `Variety.java`
- Create: `exist-core/src/main/java/org/exist/xquery/schema/SchemaKey.java`
- Modify: `exist-core/src/main/java/org/exist/xquery/value/Type.java` (`subTypeOf`, `getSuperType`, `getTypeName`, `primitiveTypeOf`, `getCommonSuperType`, `isCastable`)
- Test: `exist-core/src/test/java/org/exist/xquery/value/UserTypeRegistryTest.java`

**Interfaces:**
- Produces:
  - `public record SchemaKey(String sha256)`.
  - `public enum Variety { ATOMIC, LIST, UNION, COMPLEX_SIMPLE_CONTENT, COMPLEX_ELEMENT_ONLY, COMPLEX_MIXED, COMPLEX_EMPTY }`
  - `public record UserDefinedType(int code, @Nullable QName name, String anonymousId, int baseCode, int primitiveCode, Variety variety, SchemaKey schema, XSTypeDefinition definition, int[] memberCodes)` — `memberCodes`: union members, or `{itemType}` for lists, or `{simpleContentType}` for `COMPLEX_SIMPLE_CONTENT`.
  - `UserTypeRegistry.USER_TYPE_BASE = 1024`; `static int register(SchemaKey schema, String typeId, Function<Integer, UserDefinedType> factory)` (idempotent per `(schema, typeId)`; `typeId` is `{ns}local` for named types, `#anon:<declaration path>` for anonymous); `static @Nullable UserDefinedType get(int code)`; `static boolean isUserType(int code)`. Thread-safe (`ConcurrentHashMap` + `AtomicInteger`).
  - `Type.subTypeOf(sub, sup)`: for user `sub`, walk `baseCode` until equal to `sup` or a built-in, then continue with the built-in algorithm; built-in `sub` is never a subtype of a user `sup`. `Type.getTypeName(code)` returns `prefix:local`-less `Q{ns}local` for user types (or `anonymousId`).

- [ ] **Step 1: Write failing tests**

```java
@Test public void userRestrictionOfDecimalIsDecimalAndNumeric() {
    int nnd = registerAtomic("NonNegativeDecimal", Type.DECIMAL);
    assertTrue(Type.subTypeOf(nnd, Type.DECIMAL));
    assertTrue(Type.subTypeOf(nnd, Type.NUMERIC));
    assertTrue(Type.subTypeOf(nnd, Type.ANY_ATOMIC_TYPE));
    assertFalse(Type.subTypeOf(Type.DECIMAL, nnd));
    assertEquals(Type.DECIMAL, Type.primitiveTypeOf(nnd));
    assertEquals(Type.DECIMAL, Type.getSuperType(nnd));
}
@Test public void chainedUserTypes() { /* PositiveDecimal → NonNegativeDecimal → xs:decimal */ }
@Test public void registerIsIdempotentPerSchemaAndName() { assertEquals(register(k1,"T"), register(k1,"T")); assertNotEquals(register(k1,"T"), register(k2,"T")); }
@Test public void builtInBehaviourUnchanged() { assertTrue(Type.subTypeOf(Type.INT, Type.DECIMAL)); assertFalse(Type.subTypeOf(Type.STRING, Type.DECIMAL)); }
```

- [ ] **Step 2: Run** `mvnt exist-core org.exist.xquery.value.UserTypeRegistryTest` — Expected: compile failure / FAIL.
- [ ] **Step 3: Implement** the registry and `Type` delegation; guard every `superTypes[...]` array access against codes ≥ `USER_TYPE_BASE`.
- [ ] **Step 4: Run** the test plus `mvnt exist-core xquery.xquery3.XQuery3Tests` — Expected: PASS.
- [ ] **Step 5: Commit** — `[feature] Support user-defined schema types in the type registry`

---

### Task 3: `CompiledSchema` — precomputed schema model

**Files:**
- Create: `exist-core/src/main/java/org/exist/xquery/schema/CompiledSchema.java`, `ChildResolution.java`
- Create fixtures: `exist-core/src/test/resources/org/exist/xquery/schema/fixtures/trade.xsd`, `trade-common.xsd` (included), `unqualified.xsd`, `trade-1.xml`
- Test: `exist-core/src/test/java/org/exist/xquery/schema/CompiledSchemaTest.java`

Fixture `trade.xsd` (targetNamespace `urn:example:trade`, prefix `t`, `elementFormDefault="qualified"`) must contain exactly these constructs, used by later tasks:

| Construct | Definition |
|-----------|-----------|
| `t:NonNegativeDecimal` | restriction of `xs:decimal`, `minInclusive 0` |
| `t:CurrencyCode` | restriction of `xs:token`, `pattern [A-Z]{3}` |
| `t:DateList` | list of `xs:date` |
| `t:DateOrDuration` | union of `xs:date xs:duration` |
| `t:Product` | abstract complex type, element-only, attribute `id` of `xs:ID` |
| `t:Swap`, `t:Fra` | extensions of `t:Product` |
| `t:VanillaSwap` | extension of `t:Swap` |
| global `t:product` | abstract head, type `t:Product` |
| global `t:swap`, `t:fra` | `substitutionGroup="t:product"` |
| global `t:vanillaSwap` | `substitutionGroup="t:swap"` (chain) |
| global `t:trade` | anonymous complex type: `tradeId` (`xs:string`, `nillable="true"`), `tradeDate` (`t:AdjustableDate`), `t:product`, `notional`, `fee`, `party` (0..n), `partyReference`, `description` (mixed), `extension` (`xs:any namespace="##other" processContents="lax"`), `opaque` (`xs:any processContents="skip"`), `fixingDates` (`t:DateList`) |
| `t:AdjustableDate` | complex type with simple content extending `xs:date`, attribute `id` |
| local `notional/amount` | `t:NonNegativeDecimal`; local `fee/amount` is `xs:decimal` (same local name, different parents) |
| `notional/currency` | `t:CurrencyCode` |
| `party/@id` | `xs:ID`; `partyReference/@href` `xs:IDREF` |
| `blockedSwap` | global element of type `t:Swap` with `block="substitution"` used as content of `t:Fra/legs` to test block |

| global `t:trades` | root element: sequence of 1..n `t:trade` |

All `t:trade` children except `t:product` are `minOccurs="0"`.

`trade-1.xml` (root `t:trades`, `xmlns:t` declared on the root only) contains three trades:
- trade 1: product `t:swap`; `tradeId xsi:nil="true"`; `tradeDate` `2026-03-02`; `notional/amount` `1000000`, `notional/currency` `USD`; `fee/amount` `-5`; `party id="party1"`; `partyReference href="party1"`; `description` ` x `; `fixingDates` `2026-01-01 2026-02-01`.
- trade 2: product `t:fra` only.
- trade 3: product `t:vanillaSwap` only.

`bad.xml`: identical to `trade-1.xml` plus `<t:bogus/>` as the last child of trade 1.

`unqualified.xsd`: `elementFormDefault="unqualified"` with a global `u:root` whose local child `item` (no namespace) is `xs:integer`.

Node paths everywhere in this plan (`Assignment.path`, `DocMeta.unassignablePaths`, test helpers) use prefixed names with a 1-based position on every step, e.g. `/t:trades[1]/t:trade[1]/t:bogus[1]`. Test-helper arguments written below as `/t:trade/…` are shorthand for `/t:trades[1]/t:trade[1]/…`.

**Interfaces:**
- Consumes: `UserTypeRegistry`, `SchemaKey` (Task 2).
- Produces:
  - `public static CompiledSchema compile(SchemaKey key, XSModel model)`
  - `SchemaKey key()`, `Set<String> targetNamespaces()`
  - `Optional<XSElementDeclaration> globalElement(QName)`, `Optional<XSAttributeDeclaration> globalAttribute(QName)`, `Optional<XSTypeDefinition> namedType(QName)`
  - `ChildResolution resolveChild(XSComplexTypeDefinition parent, QName child)` — includes substitution-group members of declared particles (respecting `block="substitution"` on the head and `final`), transitively.
  - `Optional<XSAttributeDeclaration> resolveAttribute(XSComplexTypeDefinition owner, QName attr)` — attribute uses, then attribute wildcards (`strict`/`lax` look up global attributes).
  - `Set<XSElementDeclaration> substitutionMembers(XSElementDeclaration head)` — transitive, includes head when non-abstract, excludes abstract.
  - `int typeCode(XSTypeDefinition def)` — XML Schema built-ins map to `Type` constants (`xs:anyType`→`Type.ANY_TYPE`, `xs:untyped`→`Type.UNTYPED`…); others registered with `UserTypeRegistry`.
  - `public record ChildResolution(Kind kind, @Nullable XSElementDeclaration decl, short processContents)` with `enum Kind { DECLARED, WILDCARD, NOT_ALLOWED }`; `processContents` uses `XSWildcard.PC_STRICT|PC_LAX|PC_SKIP`.
- Precompute per complex type, lazily on first `resolveChild` and cached in a `ConcurrentHashMap<XSComplexTypeDefinition, Map<QName, ChildResolution>>`; the object is otherwise immutable.

- [ ] **Step 1: Write failing tests** (load fixtures with Xerces `XSLoader` directly; key = `new SchemaKey("test")`)

```java
@Test public void sameLocalNameDifferentParentsHaveDifferentTypes() {
    assertEquals("NonNegativeDecimal", typeOfChild("notional", "amount").getName());
    assertEquals(Type.DECIMAL, schema.typeCode(typeOfChild("fee", "amount")));
}
@Test public void substitutionChainResolvesUnderAbstractHead() {
    var r = schema.resolveChild(tradeType, new QName("vanillaSwap", NS));
    assertEquals(ChildResolution.Kind.DECLARED, r.kind());
    assertEquals("VanillaSwap", r.decl().getTypeDefinition().getName());
}
@Test public void abstractHeadItselfNotAllowedAsChild() { assertEquals(NOT_ALLOWED, schema.resolveChild(tradeType, new QName("product", NS)).kind()); }
@Test public void blockedSubstitutionNotAllowed() { /* legs/t:vanillaSwap where head blockedSwap blocks substitution */ }
@Test public void laxAndSkipWildcards() { assertEquals(PC_LAX, resolve("extension", foreign).processContents()); assertEquals(PC_SKIP, resolve("opaque", foreign).processContents()); }
@Test public void unqualifiedLocalElement() { /* child QName ("item", "") resolves to xs:integer */ }
@Test public void derivedTypeCodesAreSubtypes() { assertTrue(Type.subTypeOf(code("VanillaSwap"), code("Product"))); }
@Test public void substitutionMembersTransitive() { assertEquals(Set.of("swap","fra","vanillaSwap"), names(schema.substitutionMembers(head("product")))); }
```

- [ ] **Step 2: Run** `mvnt exist-core org.exist.xquery.schema.CompiledSchemaTest` — Expected: FAIL.
- [ ] **Step 3: Implement** using `XSComplexTypeDefinition.getParticle()` traversal (model groups, nested groups, element particles, wildcards) and `XSModel.getSubstitutionGroup(XSElementDeclaration)`.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** — `[feature] Add CompiledSchema with precomputed content-model lookups`

---

### Task 4: `SchemaRegistry` broker-pool service

**Files:**
- Create: `exist-core/src/main/java/org/exist/xquery/schema/SchemaRegistry.java`, `SchemaLoadException.java`, `ChecksummingResourceResolver.java`
- Modify: `exist-core/src/main/java/org/exist/storage/BrokerPool.java` (register service, add `public SchemaRegistry getSchemaRegistry()`)
- Modify: `exist-core/src/main/java/org/exist/util/Configuration.java` (read `<schema-registry cache-size="8"/>` under `<xquery>`)
- Modify: `exist-distribution/src/main/config/conf.xml` (add `<schema-registry cache-size="8"/>` with a comment), `schema/conf.xsd` (+ version bump)
- Test: `exist-core/src/test/java/org/exist/xquery/schema/SchemaRegistryTest.java`

**Interfaces:**
- Consumes: `CompiledSchema.compile` (Task 3).
- Produces:
  - `public CompiledSchema load(DBBroker broker, String href, boolean allowRemote) throws SchemaLoadException` — resolves `href` and all includes/imports via database URIs (`xmldb:exist://`) and the existing catalog resolver; computes `SchemaKey` as SHA-256 over the bytes of every resolved document, ordered by system ID; returns the cached instance when the key is cached.
  - `public Optional<CompiledSchema> get(SchemaKey key)`
  - `public Optional<CompiledSchema> loadedForNamespace(String namespaceUri)` — most recently loaded schema whose `targetNamespaces()` contains it.
  - `SchemaLoadException(String message, @Nullable String systemId, int line, int column, Throwable cause)`.
- Security: Xerces features `http://apache.org/xml/features/disallow-doctype-decl=true`, external general/parameter entities `false`; when `allowRemote=false` any `http(s)`/`ftp` system ID fails with `SchemaLoadException("remote schema access disabled: <uri>")`.
- LRU eviction by access order, bounded by `cache-size`.

- [ ] **Step 1: Write failing tests** (use `ExistEmbeddedServer` rule; store fixtures from Task 3 into `/db/test-schemas`)

```java
@Test public void loadsSchemaWithIncludeFromDatabase() { var s = registry.load(broker, "xmldb:exist:///db/test-schemas/trade.xsd", false); assertTrue(s.targetNamespaces().contains("urn:example:trade")); }
@Test public void sameBytesSameKeyAndCachedInstance() { assertSame(load(), load()); }
@Test public void editingIncludedFileChangesKey() { var k1 = load().key(); storeModifiedInclude(); assertNotEquals(k1, load().key()); }
@Test(expected = SchemaLoadException.class) public void remoteImportRejectedByDefault() { load("…/remote-import.xsd"); }
@Test public void doctypeInSchemaRejected() { /* SchemaLoadException message mentions DOCTYPE */ }
@Test public void lruEvictsBeyondCacheSize() { /* cache-size=2 via test conf; load 3 distinct; first get(key) is empty */ }
@Test public void parseErrorReportsLocation() { var e = assertThrows(...); assertEquals(12, e.line()); }
```

- [ ] **Step 2: Run** `mvnt exist-core org.exist.xquery.schema.SchemaRegistryTest` — Expected: FAIL.
- [ ] **Step 3: Implement** service, config, `conf.xml`/`conf.xsd` changes.
- [ ] **Step 4: Run** the test and `mvn -N xml:transform@schema-governance -Ddependency-check.skip=true` — Expected: PASS / no governance violation.
- [ ] **Step 5: Commit** — `[feature] Add SchemaRegistry service for compiled XSD models`

---

### Task 5: Typed values, value checking and the provider interface

**Files:**
- Create: `exist-core/src/main/java/org/exist/xquery/schema/TypeAnnotation.java`, `TypeAnnotationProvider.java`, `TypedValues.java`, `ValueChecker.java`
- Create: `exist-core/src/main/java/org/exist/xquery/value/UserDefinedAtomicValue.java`
- Modify: `exist-core/src/main/java/org/exist/xquery/value/AtomicValue.java` (add `public AtomicValue unwrapUserType() { return this; }`)
- Modify: numeric/comparison call sites that cast atomized items to concrete value classes — at minimum `OpNumeric`, `ValueComparison`, `GeneralComparison`, `FunSum`, `FunAvg`, `FunAbs`, `FunRound`, `FunRoundHalfToEven`, `FunCeiling`, `FunFloor`, `FunFormatNumbers`, `FunMin`/`FunMax` — call `unwrapUserType()` before casting. Find the rest with `grep -rn "(NumericValue)\|(DecimalValue)\|(AbstractDateTimeValue)" exist-core/src/main/java`.
- Modify: `exist-core/src/main/java/org/exist/xquery/schema/TypeAnnotations.java` (replace Task 1's temporary record)
- Modify: `exist-core/src/main/java/org/exist/xquery/ErrorCodes.java` (EXSA0001–EXSA0004 block)
- Test: `exist-core/src/test/java/org/exist/xquery/schema/TypedValuesTest.java`, `ValueCheckerTest.java`

**Interfaces:**
- Consumes: `UserTypeRegistry`, `CompiledSchema` (Tasks 2–3).
- Produces:
  - `public record TypeAnnotation(int typeCode, boolean nilled)` with constants `UNTYPED` (`Type.UNTYPED`), `ANY_TYPE` (`Type.ANY_TYPE`), `UNTYPED_ATOMIC` (`Type.UNTYPED_ATOMIC`).
  - `public interface TypeAnnotationProvider`:
    - `String INDEX_ID = "org.exist.indexing.schematype.SchemaTypeIndex";`
    - `@Nullable TypeAnnotation annotationOf(NodeProxy node) throws XPathException` — `null` when the node's collection has no schema; `ANY_TYPE`/`UNTYPED_ATOMIC` when configured but not annotated; throws `EXSA0002`/`EXSA0003`.
    - `NodeSet nodesOfType(DocumentSet docs, int typeCode, boolean includeDerived, int nodeKind) throws XPathException`
    - `@Nullable SchemaKey schemaOf(DocumentImpl doc) throws XPathException`
    - `static @Nullable TypeAnnotationProvider of(DBBroker broker)` — `getIndexController().getWorkerByIndexId(INDEX_ID)` + `instanceof`.
  - `TypedValues.fromLexical(String lexical, int typeCode, @Nullable Expression ctx) throws XPathException` → `Sequence`: built-in atomic → cast from `xs:untypedAtomic`; user `ATOMIC` → `UserDefinedAtomicValue` over the base value; `LIST` → whitespace-split item values; `UNION` → first member that casts; `COMPLEX_SIMPLE_CONTENT` → its simple content type; `COMPLEX_MIXED`/`ANY_TYPE`/`UNTYPED` → `UntypedAtomicValue`; `COMPLEX_ELEMENT_ONLY` → `FOTY0012`; `COMPLEX_EMPTY` → empty sequence.
  - `public ValueChecker(CompiledSchema schema)`; `boolean isValid(int typeCode, String lexical)` — tier 1 (no facets beyond primitive): primitive parse only; tier 2 (pattern/enumeration/other facets): `XSSimpleTypeDefinition` validation via Xerces `ValidatedInfo`; LRU cache of 4096 `(typeCode, lexical)` entries per instance.
  - `public UserDefinedAtomicValue(@Nullable Expression ctx, int userTypeCode, AtomicValue baseValue)`; `getType()` returns `userTypeCode`; `getStringValue`, `compareTo`, `convertTo`, `effectiveBooleanValue` delegate; `unwrapUserType()` returns `baseValue`; `convertTo(userTypeCode)` returns `this`.

- [ ] **Step 1: Write failing tests**

```java
@Test public void userAtomicKeepsTypeAndUnwraps() { var v = (AtomicValue) TypedValues.fromLexical("5.0", nnd, null).itemAt(0); assertEquals(nnd, v.getType()); assertEquals(Type.DECIMAL, v.unwrapUserType().getType()); }
@Test public void listSplitsOnWhitespace() { assertEquals(2, TypedValues.fromLexical(" 2026-01-01  2026-02-01 ", dateList, null).getItemCount()); }
@Test public void unionPicksFirstAcceptingMember() { assertEquals(Type.DURATION, typeOf(TypedValues.fromLexical("P1D", dateOrDuration, null))); }
@Test public void elementOnlyRaisesFOTY0012() { assertErrorCode(ErrorCodes.FOTY0012, () -> TypedValues.fromLexical("x", productCode, null)); }
@Test public void collapseWhitespaceForDecimal() { assertEquals("100.5", TypedValues.fromLexical(" 100.50 ", nnd, null).getStringValue()); assertTrue(checker.isValid(nnd, " 100.50 ")); }   // Review Focus 4
@Test public void stringPreservesWhitespace() { assertEquals(" a ", TypedValues.fromLexical(" a ", Type.STRING, null).getStringValue()); }
@Test public void facetsChecked() { assertFalse(checker.isValid(nnd, "-1")); assertFalse(checker.isValid(currency, "usd")); assertTrue(checker.isValid(currency, "USD")); }
@Test public void arithmeticOnUserTypeYieldsDecimal() { /* evaluate `$v * 2`, `abs($v)`, `round($v)`, `sum(($v,$v))` via XQuery with $v bound to a UserDefinedAtomicValue; each result getType()==Type.DECIMAL */ }   // Review Focus 1
```

- [ ] **Step 2: Run** `mvnt exist-core "org.exist.xquery.schema.*Test"` — Expected: FAIL.
- [ ] **Step 3: Implement** the classes, `unwrapUserType()` call sites, and error codes.
- [ ] **Step 4: Run** the tests plus `mvnt exist-core xquery.xquery3.XQuery3Tests` — Expected: PASS.
- [ ] **Step 5: Commit** — `[feature] Add typed-value construction, value checking and TypeAnnotationProvider`

---

## Phase 2 — Schema-type index module

### Task 6: Module scaffold and `collection.xconf` configuration

**Files:**
- Create: `extensions/indexes/schema-type/pom.xml` (model on `extensions/indexes/ngram/pom.xml`: `exist-core` compile dep + `test-jar` test dep, `src/test/resources-filtered` with `conf-fixture.xsl`)
- Modify: `extensions/indexes/pom.xml` (add `<module>schema-type</module>`), `exist-distribution/pom.xml` (include module jar)
- Create: `extensions/indexes/schema-type/src/main/java/org/exist/indexing/schematype/SchemaTypeIndex.java`, `SchemaTypeIndexWorker.java` (lifecycle no-ops for now), `SchemaTypeConfig.java`, `OnUnassignable.java`
- Modify: `exist-distribution/src/main/config/conf.xml` — `<module id="schema-type-index" enabled="yes" file="schema-type.dbx" class="org.exist.indexing.schematype.SchemaTypeIndex"/>`
- Modify: `schema/collection.xconf.xsd` (`schema-type`, `typed-values`, `type` elements + version bump)
- Test: `extensions/indexes/schema-type/src/test/java/org/exist/indexing/schematype/SchemaTypeConfigTest.java`

**Interfaces:**
- Consumes: `SchemaRegistry.load` (Task 4), `TypeAnnotationProvider.INDEX_ID` (Task 5).
- Produces:
  - `SchemaTypeIndex extends AbstractIndex implements RawBackupSupport`, `ID = TypeAnnotationProvider.INDEX_ID`, nested `Factory implements IndexFactory`.
  - `public record SchemaTypeConfig(String href, OnUnassignable onUnassignable, boolean checkValues, boolean allowRemote, boolean reindexOnSchemaChange, Set<QName> typedValueTypes, CompiledSchema schema)`
  - `static SchemaTypeConfig parse(DBBroker broker, Element schemaTypeEl, @Nullable Element typedValuesEl, Map<String, String> namespaces) throws DatabaseConfigurationException` — loads the schema eagerly; `SchemaLoadException` → `DatabaseConfigurationException` carrying system ID/line; unknown `typed-values/type/@name` → `DatabaseConfigurationException`.
  - `SchemaTypeIndexWorker.configure(...)` returns a `SchemaTypeConfig`.

- [ ] **Step 1: Write failing tests**

```java
@Test public void validConfigParsesWithDefaults() { var c = parse(XCONF_OK); assertEquals(OnUnassignable.UNTYPED, c.onUnassignable()); assertTrue(c.checkValues()); assertFalse(c.allowRemote()); assertFalse(c.reindexOnSchemaChange()); }
@Test public void storingXconfWithMissingSchemaIsRejected() { assertThrows(CollectionConfigurationException.class, () -> storeXconf("/db/c", XCONF_MISSING_HREF)); }
@Test public void unknownTypedValueTypeRejected() { /* <type name="t:Nope"/> */ }
@Test public void typedValuesResolvePrefixesFromXconf() { assertTrue(c.typedValueTypes().contains(new QName("NonNegativeDecimal", "urn:example:trade"))); }
```

- [ ] **Step 2: Run** `mvnt extensions/indexes/schema-type org.exist.indexing.schematype.SchemaTypeConfigTest` — Expected: FAIL.
- [ ] **Step 3: Implement** scaffold, config parsing, registration, XSD update.
- [ ] **Step 4: Run** the test + `mvn -N xml:transform@schema-governance -Ddependency-check.skip=true` — Expected: PASS.
- [ ] **Step 5: Commit** — `[feature] Add schema-type index module and collection configuration`

---

### Task 7: `TypeAssigner` — store-time type assignment

**Files:**
- Create: `extensions/indexes/schema-type/src/main/java/org/exist/indexing/schematype/TypeAssigner.java`
- Test: `extensions/indexes/schema-type/src/test/java/org/exist/indexing/schematype/TypeAssignerTest.java` (pure; drive it from a SAX parse of fixture documents through a tiny adapter in the test)

**Interfaces:**
- Consumes: `CompiledSchema`, `ValueChecker` (Tasks 3, 5); fixtures via `exist-core` test-jar (`/org/exist/xquery/schema/fixtures/`).
- Produces:
  - `public TypeAssigner(CompiledSchema schema, @Nullable ValueChecker checker)` (`null` when `check-values="false"`)
  - `void startElement(NodeId id, QName name, NamespaceResolver inScope)` — `NamespaceResolver` is `Function<String /*prefix*/, String /*uri*/>` built from the element's in-scope namespaces (needed for `xsi:type` QName values)
  - `void attribute(NodeId id, QName name, String value)`
  - `void characters(CharSequence text)`
  - `void endElement()`
  - `List<Assignment> drain()` — completed assignments in document order since last call
  - `public record Assignment(NodeId nodeId, int nodeKind, int typeCode, boolean nilled, Status status, @Nullable String reason, String path)`; `enum Status { ANNOTATED, UNASSIGNABLE }`
- Algorithm (spec §6.2): an element's resolution is deferred until the first event after its attributes (next `startElement`, `characters`, or `endElement`), because `xsi:type`/`xsi:nil` arrive as attributes. Stack frames hold `(decl, type, unassignable, skip, textBuffer)`. Inside an unassignable or `skip`-wildcard subtree, emit nothing (nodes default to `xs:anyType`). `lax` wildcard: global declaration if present, else nothing for that subtree. Simple-content values are checked at `endElement` with the accumulated text; an invalid value marks the element `UNASSIGNABLE` with `reason="invalid value for <type>"`. `xsi:nil="true"` on a non-nillable element → `UNASSIGNABLE`. `xsi:type` must name a type derived from the declared type and not excluded by `block` → otherwise `UNASSIGNABLE`. `path` is the `/`-joined prefixed names with 1-based sibling positions (`/t:trade/t:notional[1]/t:amount[1]`).

- [ ] **Step 1: Write failing tests** (one per row of the Task 3 fixture table that affects assignment)

```java
@Test public void localSameNameElementsTypedByParent() { assertType("/t:trade/t:notional/t:amount", nnd); assertType("/t:trade/t:fee/t:amount", Type.DECIMAL); }
@Test public void substitutionMemberGetsMemberType() { assertType("/t:trade/t:vanillaSwap", code("VanillaSwap")); }
@Test public void xsiTypeWithAncestorPrefix() { /* xmlns:t on root, <t:product xsi:type="t:Swap"> is invalid (abstract head) but <t:swap xsi:type="t:VanillaSwap"> → VanillaSwap */ }  // Review Focus 5
@Test public void invalidXsiTypeDerivationIsUnassignable() { assertStatus("/t:trade/t:swap", UNASSIGNABLE); }
@Test public void nilledNillableElement() { var a = at("/t:trade/t:tradeId"); assertTrue(a.nilled()); assertEquals(Type.STRING, a.typeCode()); }
@Test public void nilOnNonNillableIsUnassignable() { }
@Test public void laxWildcardWithKnownGlobalIsTyped() { } @Test public void laxWildcardUnknownEmitsNothing() { } @Test public void skipWildcardSubtreeEmitsNothing() { }
@Test public void unknownChildMakesSubtreeUnassignable() { assertStatus("/t:trade/t:bogus", UNASSIGNABLE); assertNoAssignmentsBelow("/t:trade/t:bogus"); }
@Test public void attributesTyped() { assertType("/t:trade/t:party[1]/@id", Type.ID); assertType("/t:trade/t:partyReference/@href", Type.IDREF); }
@Test public void invalidValueUnassignable() { /* <t:amount>-5</t:amount> under notional */ }
@Test public void checkerNullSkipsValueChecks() { /* same doc, checker=null → ANNOTATED */ }
@Test public void simpleContentWithAttribute() { assertType("/t:trade/t:tradeDate", code("AdjustableDate")); }
```

- [ ] **Step 2: Run** `mvnt extensions/indexes/schema-type org.exist.indexing.schematype.TypeAssignerTest` — Expected: FAIL.
- [ ] **Step 3: Implement** `TypeAssigner`.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** — `[feature] Assign schema types to nodes during document indexing`

---

### Task 8: Index storage, worker wiring and document lifecycle

**Files:**
- Create: `extensions/indexes/schema-type/src/main/java/org/exist/indexing/schematype/TypeIndexStore.java`, `CollectionMeta.java`, `DocMeta.java`, `SchemaTypeStreamListener.java`
- Modify: `SchemaTypeIndexWorker.java` (implement `IndexWorker` lifecycle and `TypeAnnotationProvider`)
- Test: `extensions/indexes/schema-type/src/test/java/org/exist/indexing/schematype/SchemaTypeIndexTest.java`

**Interfaces:**
- Consumes: `TypeAssigner` (Task 7), `SchemaTypeConfig` (Task 6), `TypeAnnotationProvider` (Task 5).
- Produces:
  - BTree key layout (first byte = table):

    | Prefix | Key | Value |
    |-------|-----|-------|
    | `0x00` | collectionId | `CollectionMeta` |
    | `0x01` | docId | `DocMeta` |
    | `0x02` | docId, nodeId | localTypeId (short), flags (bit0 = nilled) |
    | `0x03` | localTypeId, docId | nodeId list |
    | `0x04` | localTypeId, `Indexable#serializeValue` key, docId | nodeId list |

  - `public record CollectionMeta(SchemaKey schema, Map<String /*typeId*/, Short> localTypeIds)` — allocate new local IDs append-only.
  - `public record DocMeta(SchemaKey indexedWith, DocStatus status, List<String> unassignablePaths /* capped at 100 */)`; `enum DocStatus { TYPED, PARTIAL }`.
  - `TypeIndexStore` methods: `putAnnotation(int docId, NodeId id, short localTypeId, boolean nilled)`, `@Nullable StoredAnnotation getAnnotation(int docId, NodeId id)`, `addTypeMember(short localTypeId, int docId, NodeId id)`, `putTypedValue(short localTypeId, byte[] key, int docId, NodeId id)`, `NodeSet nodesOfType(DocumentSet docs, short[] localTypeIds)`, `NodeSet typedRange(DocumentSet docs, short[] localTypeIds, @Nullable byte[] low, boolean lowIncl, @Nullable byte[] high, boolean highIncl)`, `removeDocument(int docId)`, `removeNodes(int docId, NodeId subtreeRoot)`, meta get/put.
  - Worker: `getListener()` returns `SchemaTypeStreamListener` when the document's collection has a `SchemaTypeConfig`; `flush()` writes buffered assignments; `removeCollection` clears entries; `getReindexRoot` returns the nearest ancestor-or-self element whose subtree must be reassigned (the element's parent, so the assigner has the parent type — reassign from the document root if simpler, it is correct and acceptable for v1).
  - `annotationOf(NodeProxy)`: lookup with a per-worker LRU (10,000 entries) invalidated by any write for that docId; returns per Task 5 contract. `nodesOfType(...)` expands `includeDerived` via `UserTypeRegistry`/`Type.subTypeOf` over the collection's local type table.

- [ ] **Step 1: Write failing tests** (`ExistEmbeddedServer` with the module enabled via `conf-fixture.xsl`; collection `/db/st` with xconf + trade.xsd)

```java
@Test public void storedDocumentNodesAnnotated() { assertEquals(nnd, annotationOf("/t:trade/t:notional/t:amount").typeCode()); }
@Test public void unannotatedNodeInConfiguredCollectionIsAnyType() { assertEquals(TypeAnnotation.ANY_TYPE, annotationOf("/t:trade/t:opaque/foo")); }
@Test public void unconfiguredCollectionReturnsNull() { assertNull(annotationOfIn("/db/plain", "/r")); }
@Test public void removeDocumentClearsEntries() { } @Test public void replaceDocumentReindexes() { } @Test public void reindexCollectionRebuilds() { }
@Test public void nodesOfTypeIncludesDerived() { assertEquals(3, nodesOfType(code("Product"), true).getLength()); }
@Test public void typedValuesWrittenOnlyForListedTypes() { }
@Test public void partialDocumentRecorded() { assertEquals(DocStatus.PARTIAL, docMeta("bad.xml").status()); assertTrue(docMeta("bad.xml").unassignablePaths().contains("/t:trades[1]/t:trade[1]/t:bogus[1]")); }
@Test public void xqueryUpdateInsertAnnotatesNewNode() { exec("update insert <t:fra .../> into doc('/db/st/t1.xml')/t:trade"); assertEquals(code("Fra"), annotationOf("/t:trade/t:fra").typeCode()); }   // Review Focus 2
@Test public void xqueryUpdateDeleteRemovesAnnotation() { }
```

- [ ] **Step 2: Run** `mvnt extensions/indexes/schema-type org.exist.indexing.schematype.SchemaTypeIndexTest` — Expected: FAIL.
- [ ] **Step 3: Implement** store, listener, worker.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** — `[feature] Persist schema type annotations in the schema-type index`

---

### Task 9: Failure modes, staleness and persistence

**Files:**
- Modify: `SchemaTypeIndexWorker.java`, `SchemaTypeStreamListener.java`
- Test: `extensions/indexes/schema-type/src/test/java/org/exist/indexing/schematype/SchemaTypeFailureModesTest.java`

**Interfaces:**
- Consumes: Task 8 store and metadata.
- Produces (spec §8):
  - `on-unassignable="reject"` → store fails with `EXSA0004` (message contains node path and reason).
  - Schema unloadable at store time → store fails with `EXSA0002`; `annotationOf` raises `EXSA0002`.
  - `DocMeta.indexedWith != CollectionMeta.schema`, or **no `DocMeta` for a document in a configured collection** → `annotationOf` raises `EXSA0003`; with `reindex-on-schema-change="true"` the worker triggers a collection reindex when the collection schema key changes instead.
  - Collection schema key is recomputed when `collection.xconf` is (re)stored and on first use after startup.

- [ ] **Step 1: Write failing tests**

```java
@Test public void rejectModeFailsStoreWithEXSA0004() { var e = assertThrows(EXistException.class, () -> store("bad.xml")); assertTrue(e.getMessage().contains("EXSA0004")); assertTrue(e.getMessage().contains("/t:trade/t:bogus")); }
@Test public void unloadableSchemaRejectsStoreWithEXSA0002() { deleteSchemaFile(); assertThrowsCode("EXSA0002", () -> store("t2.xml")); }
@Test public void schemaEditMakesQueriesStaleUntilReindex() { modifyXsd(); assertXQueryError("EXSA0003", "data(doc('/db/st/t1.xml')//t:notional/t:amount)"); reindex("/db/st"); assertXQueryOk(...); }
@Test public void documentsStoredBeforeConfigAreStale() { storeIn("/db/late", "t1.xml"); storeXconf("/db/late", XCONF_OK); assertXQueryError("EXSA0003", ...); reindex("/db/late"); assertEquals("1000000", xquery("string(data(doc('/db/late/t1.xml')//t:notional/t:amount))")); }   // Review Focus 3
@Test public void reindexOnSchemaChangeRebuildsAutomatically() { }
@Test public void annotationsAndTypeIdsSurviveRestart() { var before = annotationOf(path); restartServer(); assertEquals(before, annotationOf(path)); }
@Test public void concurrentStoresAndQueriesShareCompiledSchema() { /* 8 threads × 20 store+query iterations, no exceptions, one registry entry */ }
```

- [ ] **Step 2: Run** `mvnt extensions/indexes/schema-type org.exist.indexing.schematype.SchemaTypeFailureModesTest` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** — `[feature] Handle unassignable nodes, unavailable schemas and stale type indexes`

---

## Phase 3 — Query semantics

XQSuite for Phases 3–4 lives in the index module (it needs the index): runner `extensions/indexes/schema-type/src/test/java/xquery/schematype/SchemaTypeTests.java` with `@XQSuite({"src/test/xquery/schema-type"})`; a shared library module `src/test/xquery/schema-type/fixtures.xqm` provides `%test:setUp`/`%test:tearDown` helpers that store `trade.xsd`, `trade-common.xsd`, a `collection.xconf` (with `<typed-values>` listing `xs:decimal`, `xs:date`, `t:NonNegativeDecimal`) and `trade-1.xml`, `bad.xml` into `/db/schema-type-test`, and stores `trade-1.xml` alone (no xconf) as `/db/plain/t1.xml`. Each module binds `$x:doc := doc('/db/schema-type-test/trade-1.xml')`. Run all with `mvnt extensions/indexes/schema-type xquery.schematype.SchemaTypeTests`.

### Task 10: Typed atomization

**Files:**
- Modify: `exist-core/src/main/java/org/exist/dom/persistent/NodeProxy.java` (`atomize()`, `convertTo()`), `exist-core/src/main/java/org/exist/xquery/functions/fn/FunNilled.java` (or the class implementing `fn:nilled`)
- Modify: `exist-core/src/main/java/org/exist/xquery/schema/TypeAnnotations.java`
- Test: `extensions/indexes/schema-type/src/test/xquery/schema-type/atomization.xqm`

**Interfaces:**
- Consumes: `TypeAnnotationProvider.of(broker)`, `TypedValues.fromLexical` (Task 5). `NodeProxy.atomize()` obtains the broker via `getOwnerDocument().getBrokerPool().getActiveBroker()`; when the provider is absent or returns `null`, behavior is exactly today's.
- Produces: `NodeProxy.atomize()` returns `TypedValues.fromLexical(getNodeValue(), annotation.typeCode(), null)` — for list types, `Atomize`/`fn:data` must receive the whole sequence: add `public Sequence typedValue() throws XPathException` to `NodeProxy` and use it in `Atomize` and `fn:data` (keep `atomize()` returning the first item for single-valued callers).

- [ ] **Step 1: Write failing XQSuite tests**

```xquery
declare %test:assertTrue function a:amount-typed() { data($a:doc//t:notional/t:amount) instance of xs:decimal };
declare %test:assertEquals("-5", "1000000") function a:order-by-numeric() { for $v in $a:doc//t:amount order by $v return string($v) };
declare %test:assertEquals(2) function a:distinct-typed() { count(distinct-values(($a:doc//t:notional/t:amount, $a:doc//t:fee/t:amount))) };
declare %test:assertTrue function a:numeric-compare-no-cast() { $a:doc//t:notional/t:amount > 999999 };
declare %test:assertError("XPTY0004") function a:string-compare-now-type-error() { $a:doc//t:notional/t:amount = "1000000" };
declare %test:assertEquals(2) function a:list-atomizes-to-sequence() { count(data($a:doc//t:fixingDates)) };
declare %test:assertTrue function a:date-typed() { data($a:doc//t:tradeDate) instance of xs:date };
declare %test:assertError("FOTY0012") function a:element-only() { data($a:doc//t:swap) };
declare %test:assertTrue function a:nilled() { fn:nilled($a:doc//t:tradeId) };
declare %test:assertEmpty function a:nilled-value() { data($a:doc//t:tradeId) };
declare %test:assertEquals(2000000) function a:arithmetic() { $a:doc//t:notional/t:amount * 2 };          (: Review Focus 1 :)
declare %test:assertEquals(1000000) function a:sum() { sum($a:doc//t:notional/t:amount) };
declare %test:assertEquals("1,000,000.00") function a:format() { format-number($a:doc//t:notional/t:amount, "#,##0.00") };
declare %test:assertTrue function a:unconfigured-unchanged() { data(doc('/db/plain/t1.xml')//*:amount[1]) instance of xs:untypedAtomic };
declare %test:assertEquals(" x ") function a:string-unchanged() { string($a:doc//t:description) };
```

- [ ] **Step 2: Run** `mvnt extensions/indexes/schema-type xquery.schematype.SchemaTypeTests` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the suite plus `mvnt exist-core xquery.xquery3.XQuery3Tests` and `mvnt exist-core org.exist.xquery.XPathQueryTest` — Expected: PASS.
- [ ] **Step 5: Commit** — `[feature] Atomize stored nodes to their schema-typed values`

---

### Task 11: `import schema` and in-scope schema definitions

**Files:**
- Modify: `exist-core/src/main/antlr/org/exist/xquery/parser/XQueryTree.g` (`SCHEMA_IMPORT` rule: replace the `XQST0009` throw with `staticContext.importSchema(...)`)
- Modify: `exist-core/src/main/java/org/exist/xquery/XQueryContext.java` (+ `ModuleContext` delegation for library modules)
- Create: `exist-core/src/main/java/org/exist/xquery/schema/InScopeSchemas.java`
- Modify: `exist-core/src/main/java/org/exist/xquery/schema/TypeAnnotations.java` (EXSA0001 check)
- Test: `extensions/indexes/schema-type/src/test/xquery/schema-type/import-schema.xqm`

**Interfaces:**
- Consumes: `SchemaRegistry` (Task 4), `TypeAnnotationProvider.schemaOf` (Task 5).
- Produces:
  - `XQueryContext.importSchema(@Nullable String prefix, String namespaceUri, @Nullable String[] locationHints, boolean defaultElementNamespace) throws XPathException` — load via first resolvable hint; no hint → `SchemaRegistry.loadedForNamespace`, then catalog; none → `XQST0059`; duplicate namespace → `XQST0058`; prefix with empty namespace → `XQST0057`.
  - `XQueryContext.getInScopeSchemas()` → `InScopeSchemas`:
    - `int resolveTypeCode(QName name) throws XPathException` — built-ins first; then imported schema; name in an imported namespace but undefined → `XPST0008`; namespace not imported → existing `XPST0051` behavior.
    - `XSElementDeclaration resolveElementDeclaration(QName)`, `XSAttributeDeclaration resolveAttributeDeclaration(QName)` → `XPST0008` when undeclared.
    - `Optional<CompiledSchema> schemaFor(String namespaceUri)`.
  - `TypeAnnotations.matches(...)`: when the requested `typeCode` is a user type and the node's `schemaOf(doc)` differs from `UserTypeRegistry.get(typeCode).schema()` for the same namespace → `EXSA0001`.
  - All grammar sites that resolve type names (`Type.getType(qname)` in `XQueryTree.g` ~1267, ~1382, ~1411 and Task 1's sites) call `staticContext.getInScopeSchemas().resolveTypeCode(qname)`.

- [ ] **Step 1: Write failing XQSuite tests** (each test is a `util:eval` of a small query string so static errors are catchable)

```xquery
declare %test:assertTrue function i:import-and-name-type() { util:eval('import schema namespace t="urn:example:trade" at "xmldb:exist:///db/schema-type-test/trade.xsd"; doc("/db/schema-type-test/t1.xml")//t:notional instance of element(*)') };
declare %test:assertTrue function i:import-without-hint-uses-configured() { util:eval('import schema namespace t="urn:example:trade"; true()') };
declare %test:assertError("XQST0059") function i:unknown-namespace() { util:eval('import schema namespace x="urn:nope"; 1') };
declare %test:assertError("XQST0058") function i:duplicate() { util:eval('import schema namespace t="urn:example:trade"; import schema namespace u="urn:example:trade"; 1') };
declare %test:assertError("XQST0057") function i:prefix-empty-ns() { util:eval('import schema namespace e=""; 1') };
declare %test:assertError("XPST0008") function i:undeclared-type() { util:eval('import schema namespace t="urn:example:trade"; 1 instance of t:Nope') };
declare %test:assertError("EXSA0001") function i:schema-version-mismatch() { (: import trade-v2.xsd (same namespace, different bytes) and test element(*, t:Swap) on stored nodes :) };
```

- [ ] **Step 2: Run** the suite — Expected: FAIL (currently `XQST0009`).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the suite plus `mvnt exist-core xquery.xquery3.XQuery3Tests` — Expected: PASS.
- [ ] **Step 5: Commit** — `[feature] Support import schema for in-scope schema type definitions`

---

### Task 12: Type tests on annotations — `element/attribute(N, T)`, `schema-element`, `schema-attribute`

**Files:**
- Create: `exist-core/src/main/java/org/exist/xquery/SchemaNodeTest.java`
- Modify: `exist-core/src/main/java/org/exist/xquery/TypedNodeTest.java`, `SequenceType.java`, `XQueryTree.g` (all `"schema-element"`/`"schema-attribute"` branches, currently ignored at ~1482, ~2992, ~3053), `XQuery.g` (add `schemaAttributeTest` if absent)
- Test: `extensions/indexes/schema-type/src/test/xquery/schema-type/type-tests.xqm`

**Interfaces:**
- Consumes: `TypeAnnotations.matches` (Tasks 1, 11), `InScopeSchemas.resolveElementDeclaration` (Task 11), `CompiledSchema.substitutionMembers` (Task 3).
- Produces:
  - `public SchemaNodeTest(XQueryContext ctx, int nodeKind, Set<QName> acceptedNames, int declaredTypeCode, boolean nillable)` — `acceptedNames` = names of `substitutionMembers(decl)`; matches when name ∈ set and annotation derives from `declaredTypeCode`, plus the nilled rule.
  - `SequenceType#setSchemaDeclaration(Set<QName> acceptedNames, int declaredTypeCode, boolean nillable)`; `checkType(Item)` uses it.

- [ ] **Step 1: Write failing XQSuite tests**

```xquery
declare %test:assertEquals(3) function y:element-star-abstract-base() { count($y:doc//element(*, t:Product)) };
declare %test:assertEquals(1) function y:element-name-type() { count($y:doc//element(t:vanillaSwap, t:Swap)) };
declare %test:assertEquals(0) function y:element-wrong-type() { count($y:doc//element(t:fra, t:Swap)) };
declare %test:assertEquals(3) function y:schema-element-substitution() { count($y:doc//schema-element(t:product)) };
declare %test:assertEquals(0) function y:nilled-excluded-without-q() { count($y:doc//element(t:tradeId, xs:string)) };
declare %test:assertEquals(1) function y:nilled-included-with-q() { count($y:doc//element(t:tradeId, xs:string?)) };
declare %test:assertTrue function y:schema-attribute() { exists($y:doc//t:party/@id[. instance of attribute(*, xs:ID)]) };
declare %test:assertTrue function y:function-param-type() { local:f($y:doc//t:swap[1]) }; (: declare function local:f($p as schema-element(t:product)) { true() } :)
declare %test:assertError("XPST0008") function y:undeclared-schema-element() { util:eval('import schema namespace t="urn:example:trade"; //schema-element(t:nope)') };
declare %test:assertEquals(0) function y:unassignable-not-matched() { count(doc('/db/schema-type-test/bad.xml')//element(t:bogus, t:Product)) };
```

- [ ] **Step 2: Run** the suite — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the suite plus `mvnt exist-core xquery.schemaaware.SchemaAwareTests` — Expected: PASS.
- [ ] **Step 5: Commit** — `[feature] Match element, attribute and schema-element tests on type annotations`

---

### Task 13: Casts and constructor functions for user-defined types

**Files:**
- Create: `exist-core/src/main/java/org/exist/xquery/schema/UserTypeCasts.java`
- Modify: `exist-core/src/main/java/org/exist/xquery/CastExpression.java`, `CastableExpression.java`, `exist-core/src/main/java/org/exist/xquery/FunctionFactory.java` (constructor functions for imported schema namespaces next to the existing `Namespaces.SCHEMA_NS` branch at ~209)
- Test: `extensions/indexes/schema-type/src/test/xquery/schema-type/casts.xqm`

**Interfaces:**
- Consumes: `InScopeSchemas.resolveTypeCode`, `ValueChecker`, `UserDefinedAtomicValue`.
- Produces: `UserTypeCasts.cast(AtomicValue value, int userTypeCode, @Nullable Expression ctx) throws XPathException` — cast to the primitive/base built-in, then facet check via `ValueChecker.isValid` on the canonical lexical form; failure → `FORG0001`; union target → first member that casts; list target only from `xs:string`/`xs:untypedAtomic` (XQuery 3.1 §3.14.2), otherwise `XPST0080`. Abstract/complex type target → `XPST0051`.

- [ ] **Step 1: Write failing XQSuite tests**

```xquery
declare %test:assertTrue function c:constructor() { t:NonNegativeDecimal("5") instance of t:NonNegativeDecimal };
declare %test:assertError("FORG0001") function c:facet-violation() { t:NonNegativeDecimal("-1") };
declare %test:assertError("FORG0001") function c:pattern-violation() { "usd" cast as t:CurrencyCode };
declare %test:assertTrue function c:castable() { "USD" castable as t:CurrencyCode and not("usd" castable as t:CurrencyCode) };
declare %test:assertEquals(2) function c:list-cast() { count("2026-01-01 2026-02-01" cast as t:DateList) };
declare %test:assertTrue function c:union-cast() { ("P1D" cast as t:DateOrDuration) instance of xs:duration };
declare %test:assertTrue function c:arith-result-decimal() { (t:NonNegativeDecimal("5") + 1) instance of xs:decimal };
declare %test:assertError("XPST0051") function c:cast-to-complex() { util:eval('import schema namespace t="urn:example:trade"; "x" cast as t:Product') };
```

- [ ] **Step 2: Run** the suite — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** — `[feature] Support casts and constructor functions for user-defined atomic types`

---

### Task 14: `fn:id`, `fn:idref`, `fn:element-with-id` with schema-typed IDs

**Files:**
- Modify: `exist-core/src/main/java/org/exist/xquery/functions/fn/FunId.java`, `FunIdRef.java` (and the `element-with-id` implementation)
- Test: `extensions/indexes/schema-type/src/test/xquery/schema-type/ids.xqm`

**Interfaces:**
- Consumes: `TypeAnnotationProvider.nodesOfType(docs, Type.ID | Type.IDREF | Type.IDREFS, true, Type.ATTRIBUTE/ELEMENT)` (Task 8).
- Produces: when the provider is present and the document set's collection is configured, union today's results with nodes whose annotation derives from `xs:ID` (or `xs:IDREF`/`xs:IDREFS`) and whose value matches.

- [ ] **Step 1: Write failing XQSuite tests**

```xquery
declare %test:assertEquals("party1") function d:id-from-schema() { string(id("party1", $d:doc)/@id) };
declare %test:assertEquals(1) function d:href-to-party() { count(id($d:doc//t:partyReference/@href, $d:doc)) };
declare %test:assertEquals(1) function d:idref() { count(idref("party1", $d:doc)) };
declare %test:assertEquals("t:party") function d:element-with-id() { name(element-with-id("party1", $d:doc)) };
```

- [ ] **Step 2–4:** run suite (FAIL) → implement → run (PASS).
- [ ] **Step 5: Commit** — `[feature] Resolve fn:id and fn:idref through schema-typed ID attributes`

---

## Phase 4 — Optimization and diagnostics

### Task 15: Type→nodes query rewrite

**Files:**
- Create: `extensions/indexes/schema-type/src/main/java/org/exist/indexing/schematype/SchemaTypeQueryRewriter.java`, `TypeLookupStep.java`
- Modify: `SchemaTypeIndexWorker.getQueryRewriter`
- Test: `extensions/indexes/schema-type/src/test/xquery/schema-type/optimizer-types.xqm`

**Interfaces:**
- Consumes: `QueryRewriter#rewriteLocationStep(LocationStep)` (core), `TypeAnnotationProvider.nodesOfType` (Task 8), `TypedNodeTest`/`SchemaNodeTest` (Tasks 1, 12).
- Produces: descendant/descendant-or-self steps whose node test is a `TypedNodeTest` or `SchemaNodeTest` are rewritten to fetch candidates with `nodesOfType(contextDocs, typeCode, true, kind)`, then filtered by ancestor relationship to the context set (`NodeSet.selectAncestorDescendant`) and by name for `SchemaNodeTest`. Report use through `IndexUseReporter` with index name `schema-type`. If the provider cannot answer (unconfigured docs in the context set), evaluate the original step.

- [ ] **Step 1: Write failing differential tests** — each runs the query with `(# exist:optimize-off #)`-equivalent (`util:eval` with optimizer disabled, or `declare option exist:optimize "enable=no"`) and with optimization, asserting `deep-equal`, and asserts `util:index-keys`/`IndexUseReporter` evidence shows `schema-type` was used:

```xquery
declare %test:assertTrue function o:element-star-same-results() { o:same('//element(*, t:Product)') };
declare %test:assertTrue function o:schema-element-same-results() { o:same('//schema-element(t:product)') };
declare %test:assertTrue function o:index-used() { o:uses-index('//element(*, t:Product)', 'schema-type') };
declare %test:assertTrue function o:mixed-collections-fallback() { o:same('collection(("/db/schema-type-test","/db/plain"))//element(*, xs:anyType)') };
```

- [ ] **Step 2–4:** run suite (FAIL) → implement → run (PASS).
- [ ] **Step 5: Commit** — `[optimize] Answer type-based steps from the schema-type index`

---

### Task 16: Typed-value index and comparison rewrite

**Files:**
- Modify: `SchemaTypeStreamListener.java` (write `0x04` entries for `typedValueTypes`), `SchemaTypeQueryRewriter.java`
- Create: `extensions/indexes/schema-type/src/main/java/org/exist/indexing/schematype/TypedRangeLookup.java`
- Test: `extensions/indexes/schema-type/src/test/xquery/schema-type/optimizer-values.xqm`, `extensions/indexes/schema-type/src/test/java/org/exist/indexing/schematype/TypedKeyOrderTest.java`

**Interfaces:**
- Consumes: `TypeIndexStore.typedRange` (Task 8), `Indexable#serializeValue`.
- Produces: predicates of the form `step[path op literal]` / `step[. op literal]` with `op ∈ {=, eq, <, lt, <=, le, >, gt, >=, ge}`, where the path's leaf node test resolves to elements/attributes whose annotation types are in `typedValueTypes` (determine via `nodesOfType` local type IDs present in the context docs), are rewritten to `TypedRangeLookup`. The literal is cast to the annotation type first; cast failure → leave the expression un-rewritten (normal evaluation raises the proper error).

- [ ] **Step 1: Write failing tests**

```java
@Test public void decimalKeysSortNumerically() { assertTrue(compare(key("9.5"), key("10")) < 0); assertTrue(compare(key("-1"), key("0")) < 0); }
@Test public void dateKeysSortChronologically() { }
```

```xquery
declare %test:assertTrue function v:gt-same-results() { o:same('//t:notional[t:amount > 500000]') };
declare %test:assertTrue function v:date-range-same-results() { o:same('//t:trade[t:tradeDate ge xs:date("2026-01-01")]') };
declare %test:assertTrue function v:index-used() { o:uses-index('//t:notional[t:amount > 500000]', 'schema-type') };
declare %test:assertTrue function v:unlisted-type-not-rewritten() { not(o:uses-index('//t:notional[t:currency = "USD"]', 'schema-type')) };  (: t:CurrencyCode is not in <typed-values> :)
```

- [ ] **Step 2–4:** run (FAIL) → implement → run (PASS).
- [ ] **Step 5: Commit** — `[optimize] Use the typed-value index for comparisons on schema-typed paths`

---

### Task 17: Diagnostics — `fn:node-type-annotation`, `fn:atomic-type-annotation`, `schema:` module

**Files:**
- Create: `exist-core/src/main/java/org/exist/xquery/functions/fn/FunTypeAnnotation.java`
- Modify: `exist-core/src/main/java/org/exist/xquery/functions/fn/FnModule.java` (labeled block `// --- Schema Aware ---`), and register in **all** `conf.xml` files per CLAUDE.md if `fn` functions are listed there (`grep -rl "FnModule\|xpath-functions" --include=conf.xml .`)
- Create: `extensions/indexes/schema-type/src/main/java/org/exist/xquery/modules/schematype/SchemaTypeModule.java`, `SchemaStatusFunctions.java`
- Modify: `exist-distribution/src/main/config/conf.xml` (register module `http://exist-db.org/xquery/schema-type`, prefix `schema`; check the namespace is unused with `grep -rn "exist-db.org/xquery/schema-type" .`)
- Test: `extensions/indexes/schema-type/src/test/xquery/schema-type/diagnostics.xqm`

**Interfaces:**
- Produces:
  - `fn:node-type-annotation($node as (element()|attribute())) as map(*)` and `fn:atomic-type-annotation($value as xs:anyAtomicType) as map(*)` returning the QT4 schema-type record subset: `name` (`xs:QName?`), `is-simple` (`xs:boolean`), `variety` (`"atomic"|"list"|"union"|"empty"|"simple"|"element-only"|"mixed"`), `base-type` (`fn() as map(*)?`), `primitive-type` (`fn() as map(*)`, simple types only).
  - `schema:status($doc as document-node()) as map(*)` — keys `status` (`"typed"|"partial"|"stale"|"untyped"`), `schema` (checksum string).
  - `schema:unassignable($doc as document-node()) as xs:string*` — `DocMeta.unassignablePaths`.
  - `schema:types($namespace as xs:string) as element(type)*` — `<type name="…" base="…" variety="…"/>` in derivation order.

- [ ] **Step 1: Write failing XQSuite tests**

```xquery
declare %test:assertEquals("NonNegativeDecimal") function g:node-anno() { local-name-from-QName(fn:node-type-annotation($g:doc//t:notional/t:amount)?name) };
declare %test:assertEquals("decimal") function g:primitive() { local-name-from-QName(fn:atomic-type-annotation(data($g:doc//t:notional/t:amount))?primitive-type()?name) };
declare %test:assertEquals("untyped") function g:unconfigured() { local-name-from-QName(fn:node-type-annotation(<a/>)?name) };
declare %test:assertEquals("partial") function g:status-partial() { schema:status(doc('/db/schema-type-test/bad.xml'))?status };
declare %test:assertEquals("/t:trades[1]/t:trade[1]/t:bogus[1]") function g:unassignable() { schema:unassignable(doc('/db/schema-type-test/bad.xml'))[1] };
declare %test:assertTrue function g:types() { exists(schema:types("urn:example:trade")[@name = "VanillaSwap"][@base = "Swap"]) };
```

- [ ] **Step 2–4:** run (FAIL) → implement → run (PASS).
- [ ] **Step 5: Commit** — `[feature] Add type-annotation introspection functions and schema status module`

---

## Phase 5 — End-to-end, performance, documentation

### Task 18: FpML end-to-end tests

**Files:**
- Create: `extensions/indexes/schema-type/src/test/java/org/exist/indexing/schematype/FpmlEndToEndTest.java`
- Modify: `extensions/indexes/schema-type/pom.xml` (fixture download, see Step 1)
- Create: `extensions/indexes/schema-type/src/test/resources/fpml/README.md` (source, version, license note)

**Interfaces:**
- Consumes: the full stack (Tasks 1–17).

- [ ] **Step 1: Resolve fixture licensing (spec §10).** Read the FpML Public License shipped with the FpML 5.x confirmation-view schema distribution. If it permits redistribution, commit the schema set and ≥ 20 example documents under `src/test/resources/fpml/`. Otherwise add a `download-maven-plugin` (`com.googlecode.maven-download-plugin:download-maven-plugin`, pinned version) execution in `generate-test-resources` that fetches and unpacks the archive into `target/fpml/`, with the URL and SHA-256 in pom properties, and mark the test `@Category(NetworkTest.class)`-style skip when the archive is absent. Record the decision in the README.
- [ ] **Step 2: Write tests**

```java
@Test public void allExamplesFullyTyped() { for (var doc : exampleDocs) assertEquals(doc, "typed", status(doc)); }
@Test public void notionalAboveThreshold() { assertFalse(xquery("//fpml:notionalAmount[fpml:amount > 1000000]").isEmpty()); }
@Test public void tradesByProductSubstitution() { assertEquals(expectedProductCount, count("//fpml:trade/schema-element(fpml:product)")); }
@Test public void partyHrefResolves() { assertTrue(xqueryBool("every $r in //fpml:partyReference satisfies exists(id($r/@href))")); }
@Test public void adjustableDateRange() { assertFalse(xquery("//fpml:trade[fpml:tradeHeader/fpml:tradeDate ge xs:date('2000-01-01')]").isEmpty()); }
@Test public void optimizedAndUnoptimizedAgree() { /* run the four queries above both ways, deep-equal */ }
```

- [ ] **Step 3: Run** `mvnt extensions/indexes/schema-type org.exist.indexing.schematype.FpmlEndToEndTest` — Expected: PASS (any failure is a real bug: fix it in the owning task's code with a regression test there).
- [ ] **Step 4: Commit** — `[test] Add FpML end-to-end schema-aware query tests`

---

### Task 19: Benchmarks, documentation and regression gate

**Files:**
- Create: `extensions/indexes/schema-type/src/test/java/org/exist/indexing/schematype/SchemaTypeStoreBenchmark.java`, `SchemaTypeQueryBenchmark.java` (follow the pattern of `NGramContainsBenchmark`)
- Modify: `.github/workflows/ci-benchmarks.yml` (add the two benchmarks)
- Create: `extensions/indexes/schema-type/README.md` — configuration reference (all §6.1 attributes and defaults), error codes, the §7.2 migration note (XPTY0004 on configured collections), reindex procedure, `check-values="false"` trade-off.

**Interfaces:**
- Consumes: FpML fixtures (Task 18) or, when absent, the Task 3 fixture replicated to 10,000 nodes.

- [ ] **Step 1: Implement `SchemaTypeStoreBenchmark`** — stores the example set into three collections: (1) no `<schema-type>`, (2) `check-values="false"`, (3) defaults; 3 warm-up + 5 measured rounds; prints median ms per configuration and `overhead% = (3 − 1) / 1 × 100`; fails if `overhead% > 25`.
- [ ] **Step 2: Implement `SchemaTypeQueryBenchmark`** — `//element(*, t:Product)` and `//t:notional[t:amount > 500000]`, optimizer on vs off; prints medians (no gate).
- [ ] **Step 3: Run both** — `mvnt extensions/indexes/schema-type "org.exist.indexing.schematype.*Benchmark"`. Expected: store gate passes. If it does not, optimize `ValueChecker` (tier classification, cache size) and re-run before continuing.
- [ ] **Step 4: Full regression** — `mvn test -pl exist-core -Ddependency-check.skip=true -Ddocker=false` and `mvn test -pl extensions/indexes/schema-type -am -Ddependency-check.skip=true -Ddocker=false`. Expected: no new failures vs `develop` (known flaky: `RenameCollectionTest`).
- [ ] **Step 5: Write README, commit, push**

```bash
git add -A extensions/indexes/schema-type .github/workflows/ci-benchmarks.yml
git commit -m "[doc] Document schema-type index and add store/query benchmarks"
git push fork feature/schema-aware-xquery
```

---

## Follow-up (separate repository, separate plan)

- `eXist-db/exist-xqts-runner`: declare `schemaImport` and `typedData` features; store schema-bound test sources into a temporary collection configured with the source's schema; keep `validate {}` and typed-construction tests excluded. Produce the XQTS before/after table (spec §9.5) and confirm XQ 3.1 / QT4 / FTTS scores do not drop.
