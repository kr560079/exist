(:
 : eXist-db Open Source Native XML Database
 : Copyright (C) 2001 The eXist-db Authors
 :
 : info@exist-db.org
 : http://www.exist-db.org
 :
 : This library is free software; you can redistribute it and/or
 : modify it under the terms of the GNU Lesser General Public
 : License as published by the Free Software Foundation; either
 : version 2.1 of the License, or (at your option) any later version.
 :
 : This library is distributed in the hope that it will be useful,
 : but WITHOUT ANY WARRANTY; without even the implied warranty of
 : MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 : Lesser General Public License for more details.
 :
 : You should have received a copy of the GNU Lesser General Public
 : License along with this library; if not, write to the Free Software
 : Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 :)
xquery version "3.1";

(:~
 : fn:node-type-annotation, fn:atomic-type-annotation and the schema: module (status, unassignable, types).
 :
 : The tests which name trade types use util:eval after a prolog importing the trade schema (this module is compiled
 : before fx:setUp() stores the schema); the others only navigate by element names.
 :
 : /db/schema-type-test/trade.xsd and trade-common.xsd were stored before the collection was configured, so they have
 : no index entries: their status is stale.
 :)
module namespace g="http://exist-db.org/xquery/test/schema-type/diagnostics";

import module namespace fx="http://exist-db.org/xquery/test/schema-type/fixtures" at "fixtures.xqm";
import module namespace schema="http://exist-db.org/xquery/schema-type";

declare namespace test="http://exist-db.org/xquery/xqsuite";
declare namespace t="urn:example:trade";

declare variable $g:doc := doc('/db/schema-type-test/trade-1.xml');
declare variable $g:bad := doc('/db/schema-type-test/bad.xml');

declare variable $g:PROLOG := '
    import schema namespace t="urn:example:trade";
    import module namespace schema="http://exist-db.org/xquery/schema-type";
    declare variable $g:doc := doc("/db/schema-type-test/trade-1.xml");
';

declare %test:setUp function g:setUp() { fx:setUp() };
declare %test:tearDown function g:tearDown() {
    (: the guest tests change the permissions of the collection: restore them whatever happened :)
    if (xmldb:collection-available($fx:COLLECTION)) then sm:chmod(xs:anyURI($fx:COLLECTION), "rwxr-xr-x") else (),
    for $c in ("schema-type-un", "schema-type-un2", "schema-type-amb1", "schema-type-amb2", "schema-type-st")
    return g:drop($c),
    fx:tearDown()
};

(: --- scratch collections with a small schema of their own (so that tests can break them) --- :)

declare function g:xsd($ns as xs:string, $extra as xs:string) as element() {
    parse-xml('<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:u="' || $ns || '" targetNamespace="' || $ns
        || '" elementFormDefault="qualified">'
        || '<xs:simpleType name="Code"><xs:restriction base="xs:string"/></xs:simpleType>'
        || '<xs:element name="a" type="u:Code"/>'
        || (if ($extra ne "") then '<xs:element name="' || $extra || '" type="u:Code"/>' else "")
        || '</xs:schema>')/*
};

declare function g:configure($name as xs:string, $xsd as element()) {
    (: the schema is in a collection of its own: it is not indexed with the schema it defines :)
    let $coll := xmldb:create-collection("/db", $name)
    let $schemas := xmldb:create-collection("/db", $name || "-schema")
    let $s := xmldb:store("/db/" || $name || "-schema", "s.xsd", $xsd)
    return g:store-config($name)
};

declare function g:store-config($name as xs:string) {
    let $c := if (xmldb:collection-available("/db/system/config/db/" || $name)) then ()
              else xmldb:create-collection("/db/system/config/db", $name)
    return xmldb:store("/db/system/config/db/" || $name, "collection.xconf",
        <collection xmlns="http://exist-db.org/collection-config/1.0">
            <index><schema-type href="xmldb:exist:///db/{$name}-schema/s.xsd"/></index>
        </collection>)
};

declare function g:drop($name as xs:string) {
    for $c in ("/db/" || $name, "/db/" || $name || "-schema", "/db/system/config/db/" || $name)
    where xmldb:collection-available($c)
    return xmldb:remove($c)
};

declare function g:doc-in($name as xs:string, $ns as xs:string) as xs:string {
    xmldb:store("/db/" || $name, "d.xml", element {QName($ns, "u:a")} { "x" })
};

declare function g:eval($expr as xs:string) {
    util:eval('declare namespace g="http://exist-db.org/xquery/test/schema-type/diagnostics";' || $g:PROLOG || $expr)
};

declare function g:local($name as xs:QName?) as xs:string? { local-name-from-QName($name) };

(: --- fn:node-type-annotation --- :)

declare %test:assertEquals("NonNegativeDecimal") function g:node-anno() { g:local(fn:node-type-annotation($g:doc//t:notional/t:amount)?name) };
declare %test:assertEquals("urn:example:trade") function g:node-anno-namespace() {
    namespace-uri-from-QName(fn:node-type-annotation($g:doc//t:notional/t:amount)?name)
};
declare %test:assertEquals("decimal") function g:primitive() {
    g:local(fn:atomic-type-annotation(data($g:doc//t:notional/t:amount))?primitive-type()?name)
};
declare %test:assertEquals("decimal") function g:node-primitive() {
    g:local(fn:node-type-annotation($g:doc//t:notional/t:amount)?primitive-type()?name)
};
declare %test:assertEquals("decimal") function g:node-base-type() {
    g:local(fn:node-type-annotation($g:doc//t:notional/t:amount)?base-type()?name)
};
declare %test:assertEquals("atomic") function g:node-variety() { fn:node-type-annotation($g:doc//t:notional/t:amount)?variety };
declare %test:assertTrue function g:node-is-simple() { fn:node-type-annotation($g:doc//t:notional/t:amount)?is-simple };
declare %test:assertEquals("NonNegativeDecimal") function g:atomic-user-type() {
    g:local(fn:atomic-type-annotation(data($g:doc//t:notional/t:amount))?name)
};

declare %test:assertEquals("untyped") function g:unconfigured() { g:local(fn:node-type-annotation(<a/>)?name) };
declare %test:assertEquals("untypedAtomic") function g:unconfigured-attribute() { g:local(fn:node-type-annotation(<a b="1"/>/@b)?name) };
declare %test:assertEquals("untyped") function g:plain-collection() { g:local(fn:node-type-annotation((doc('/db/plain/t1.xml')//t:amount)[1])?name) };
declare %test:assertEquals("anyType") function g:unassignable-node() { g:local(fn:node-type-annotation($g:bad//t:bogus)?name) };
declare %test:assertEquals(0) function g:any-type-has-no-base() { count(fn:node-type-annotation($g:bad//t:bogus)?base-type()) };
declare %test:assertEquals("anyType") function g:untyped-base() { g:local(fn:node-type-annotation(<a/>)?base-type()?name) };

declare %test:assertEquals("VanillaSwap", "Swap", "Product", "anyType") function g:derivation-chain() {
    let $r := fn:node-type-annotation($g:doc//t:vanillaSwap)
    return (g:local($r?name), g:local($r?base-type()?name), g:local($r?base-type()?base-type()?name),
            g:local($r?base-type()?base-type()?base-type()?name))
};
declare %test:assertEquals("element-only") function g:complex-variety() { fn:node-type-annotation($g:doc//t:vanillaSwap)?variety };
declare %test:assertFalse function g:complex-is-simple() { fn:node-type-annotation($g:doc//t:vanillaSwap)?is-simple };
declare %test:assertFalse function g:complex-has-no-primitive-type() { map:contains(fn:node-type-annotation($g:doc//t:vanillaSwap), "primitive-type") };
declare %test:assertEquals("simple") function g:complex-simple-content() { fn:node-type-annotation($g:doc//t:tradeDate)?variety };
declare %test:assertFalse function g:complex-simple-content-not-simple() { fn:node-type-annotation($g:doc//t:tradeDate)?is-simple };
declare %test:assertEquals("list") function g:list-variety() { fn:node-type-annotation($g:doc//t:fixingDates)?variety };
declare %test:assertFalse function g:list-has-no-primitive-type() { map:contains(fn:node-type-annotation($g:doc//t:fixingDates), "primitive-type") };
declare %test:assertEquals("anySimpleType") function g:list-base() { g:local(fn:node-type-annotation($g:doc//t:fixingDates)?base-type()?name) };
declare %test:assertEquals(0) function g:anonymous-type-has-no-name() { count(fn:node-type-annotation($g:doc/t:trades)?name) };
declare %test:assertEquals("element-only") function g:anonymous-type-variety() { fn:node-type-annotation($g:doc/t:trades)?variety };
declare %test:assertEquals("ID") function g:attribute-annotation() { g:local(fn:node-type-annotation($g:doc//t:party/@id)?name) };
declare %test:assertError("XPTY0004") function g:node-not-element() { fn:node-type-annotation($g:doc) };
declare %test:assertError("XPTY0004") function g:node-text() { fn:node-type-annotation(<a>x</a>/text()) };
declare %test:assertEquals("VanillaSwap") function g:eval-form() { g:eval('local-name-from-QName(fn:node-type-annotation($g:doc//t:vanillaSwap)?name)') };
declare %test:assertTrue function g:eval-name-equality() { g:eval('fn:node-type-annotation($g:doc//t:vanillaSwap)?name eq xs:QName("t:VanillaSwap")') };

(: --- fn:atomic-type-annotation, built-in types --- :)

declare %test:assertEquals("integer", "decimal", "decimal", "true", "atomic") function g:builtin-integer() {
    let $r := fn:atomic-type-annotation(1)
    return (g:local($r?name), g:local($r?base-type()?name), g:local($r?primitive-type()?name), string($r?is-simple), $r?variety)
};
declare %test:assertEquals("http://www.w3.org/2001/XMLSchema") function g:builtin-namespace() { namespace-uri-from-QName(fn:atomic-type-annotation("s")?name) };
declare %test:assertEquals("normalizedString", "string") function g:builtin-token-chain() {
    let $r := fn:atomic-type-annotation(xs:token("a"))
    return (g:local($r?base-type()?name), g:local($r?primitive-type()?name))
};
declare %test:assertEquals("anyAtomicType") function g:primitive-base() { g:local(fn:atomic-type-annotation(xs:date("2026-01-01"))?base-type()?name) };
declare %test:assertEquals("untypedAtomic", "anyAtomicType") function g:untyped-atomic() {
    let $r := fn:atomic-type-annotation(xs:untypedAtomic("a"))
    return (g:local($r?name), g:local($r?base-type()?name))
};
declare %test:assertEquals("anySimpleType", "anyType") function g:any-atomic-chain() {
    let $r := fn:atomic-type-annotation(1)?base-type()?base-type()?base-type()
    return (g:local($r?name), g:local($r?base-type()?name))
};

(: --- the records of the built-in types without a schema --- :)

declare function g:keys($record as map(*)) as xs:string* { sort(map:keys($record)) };
declare function g:any-atomic() as map(*) { fn:atomic-type-annotation(1)?base-type()?base-type() };
declare function g:any-simple() as map(*) { g:any-atomic()?base-type() };

declare %test:assertEquals("base-type", "is-simple", "name", "anySimpleType", "true", "anyType")
function g:record-any-simple-type() {
    let $r := g:any-simple()
    return (g:keys($r), g:local($r?name), string($r?is-simple), g:local($r?base-type()?name))
};
declare %test:assertEquals("base-type", "is-simple", "name", "variety", "anyAtomicType", "true", "atomic", "anySimpleType")
function g:record-any-atomic-type() {
    let $r := g:any-atomic()
    return (g:keys($r), g:local($r?name), string($r?is-simple), $r?variety, g:local($r?base-type()?name))
};
declare %test:assertEquals("base-type", "is-simple", "name", "variety", "untyped", "false", "mixed", "anyType")
function g:record-untyped() {
    let $r := fn:node-type-annotation(<a/>)
    return (g:keys($r), g:local($r?name), string($r?is-simple), $r?variety, g:local($r?base-type()?name))
};
declare %test:assertEquals("base-type", "is-simple", "name", "variety", "untypedAtomic", "true", "atomic", "anyAtomicType")
function g:record-untyped-atomic() {
    let $r := fn:atomic-type-annotation(xs:untypedAtomic("a"))
    return (g:keys($r), g:local($r?name), string($r?is-simple), $r?variety, g:local($r?base-type()?name))
};
declare %test:assertEquals(0) function g:any-simple-type-has-no-variety() { count(g:any-simple()?variety) };

(: --- schema:status and schema:unassignable --- :)

declare %test:assertEquals("typed") function g:status-typed() { schema:status($g:doc)?status };
declare %test:assertEquals("partial") function g:status-partial() { schema:status(doc('/db/schema-type-test/bad.xml'))?status };
declare %test:assertEquals("untyped") function g:status-plain() { schema:status(doc('/db/plain/t1.xml'))?status };
declare %test:assertEquals("untyped") function g:status-in-memory() { schema:status(document { <a/> })?status };
declare %test:assertEquals("stale") function g:status-stale() { schema:status(doc('/db/schema-type-test/trade.xsd'))?status };
declare %test:assertEquals(64) function g:status-schema-checksum() { string-length(schema:status($g:doc)?schema) };
declare %test:assertEquals(0) function g:status-untyped-no-schema() { count(schema:status(doc('/db/plain/t1.xml'))?schema) };
declare %test:assertEquals(0) function g:status-stale-no-error() { count(schema:unassignable(doc('/db/schema-type-test/trade.xsd'))) };
declare %test:assertTrue function g:status-same-schema() { schema:status($g:doc)?schema eq schema:status($g:bad)?schema };

declare %test:assertEquals("/t:trades[1]/t:trade[1]/t:bogus[1]") function g:unassignable() { schema:unassignable(doc('/db/schema-type-test/bad.xml'))[1] };
declare %test:assertEquals(0) function g:unassignable-typed() { count(schema:unassignable($g:doc)) };
declare %test:assertEquals(0) function g:unassignable-in-memory() { count(schema:unassignable(document { <a/> })) };
declare %test:assertEquals(0) function g:unassignable-plain() { count(schema:unassignable(doc('/db/plain/t1.xml'))) };

(: --- the message of EXSA0003 (R42) --- :)

declare %test:assertError("EXSA0003") function g:stale-annotation-raises() { fn:node-type-annotation(doc('/db/schema-type-test/trade.xsd')/*) };
declare %test:assertTrue function g:stale-message-names-reindex() {
    try {
        fn:node-type-annotation(doc('/db/schema-type-test/trade.xsd')/*) ! false()
    } catch * {
        local-name-from-QName($err:code) eq 'EXSA0003' and contains($err:description, "xmldb:reindex('/db/schema-type-test')")
    }
};

(: --- schema:types --- :)

declare %test:assertTrue function g:types() { exists(schema:types("urn:example:trade")[@name = "VanillaSwap"][@base = "Swap"]) };
declare %test:assertTrue function g:types-order() {
    let $names := schema:types("urn:example:trade")/@name/string()
    return index-of($names, "Product") lt index-of($names, "Swap") and index-of($names, "Swap") lt index-of($names, "VanillaSwap")
};
declare %test:assertEquals("decimal") function g:types-builtin-base() { substring-after(schema:types("urn:example:trade")[@name = "NonNegativeDecimal"]/@base, "xs:") };
declare %test:assertEquals("atomic", "list", "union", "element-only", "simple") function g:types-variety() {
    for $n in ("NonNegativeDecimal", "DateList", "DateOrDuration", "Swap", "AdjustableDate")
    return schema:types("urn:example:trade")[@name = $n]/@variety/string()
};
declare %test:assertTrue function g:types-no-anonymous() {
    (: only named types are listed, each once, and the named global types of the schema are all there :)
    let $types := schema:types("urn:example:trade")
    return
        exists($types) and (every $t in $types satisfies exists($t/@name) and $t/@name ne "")
        and count($types) eq count(distinct-values($types/@name))
        and (every $n in ("Product", "Swap", "Fra", "VanillaSwap", "AdjustableDate", "NonNegativeDecimal", "CurrencyCode",
                          "DateList", "DecimalList", "DateOrDuration") satisfies $n = $types/@name)
};
declare %test:assertEquals(0) function g:types-unknown-namespace() { count(schema:types("urn:example:unknown")) };
declare %test:assertEquals(0) function g:types-xs-namespace() { count(schema:types("http://www.w3.org/2001/XMLSchema")) };
declare %test:assertEquals(0) function g:types-no-namespace() { count(schema:types("")) };
declare %test:assertTrue function g:types-imported() { g:eval('exists(schema:types("urn:example:trade")[@name = "VanillaSwap"])') };
declare %test:assertEquals(0) function g:types-hidden-from-unreadable-collection() {
    (: the guest may not read the only collection configured with the schema: the namespace is as unknown as any other :)
    let $chmod := sm:chmod(xs:anyURI($fx:COLLECTION), "rwx------")
    let $result := try {
            system:as-user("guest", "guest", count(schema:types("urn:example:trade")))
        } catch * {
            "error " || $err:code
        }
    let $restore := sm:chmod(xs:anyURI($fx:COLLECTION), "rwxr-xr-x")
    return $result
};
declare %test:assertTrue function g:types-visible-to-reader() {
    system:as-user("guest", "guest", exists(schema:types("urn:example:trade")[@name = "Swap"]))
};

(: --- unavailable and stale schemas --- :)

declare %test:assertEquals("unavailable", 0) function g:status-unavailable() {
    let $configured := g:configure("schema-type-un", g:xsd("urn:example:un", ""))
    let $doc := g:doc-in("schema-type-un", "urn:example:un")
    let $before := schema:status(doc($doc))?status
    let $removed := xmldb:remove("/db/schema-type-un-schema", "s.xsd")
    (: a failed store makes the unavailability known :)
    let $failed := try { g:doc-in("schema-type-un", "urn:example:un") ! () } catch * { () }
    let $status := schema:status(doc($doc))
    let $paths := count(schema:unassignable(doc($doc)))
    let $dropped := g:drop("schema-type-un")
    return ($status?status, $paths + count($status?schema) + (if ($before eq "typed") then 0 else 100))
};

declare %test:assertEquals("stale", 64, "typed") function g:status-stale-older-schema-key() {
    let $configured := g:configure("schema-type-st", g:xsd("urn:example:st", ""))
    let $doc := g:doc-in("schema-type-st", "urn:example:st")
    let $typed := schema:status(doc($doc))?status
    (: another version of the schema, which the configuration picks up: the document records the older key :)
    let $changed := xmldb:store("/db/schema-type-st-schema", "s.xsd", g:xsd("urn:example:st", "b"))
    let $reconfigured := g:store-config("schema-type-st")
    let $stale := schema:status(doc($doc))
    let $reindexed := xmldb:reindex("/db/schema-type-st")
    let $fresh := schema:status(doc($doc))?status
    let $dropped := g:drop("schema-type-st")
    return if ($typed ne "typed") then "was " || $typed else ($stale?status, string-length($stale?schema), $fresh)
};

(: --- schema:types of unavailable and ambiguous schemas (R50) --- :)

declare %test:assertEquals("listed", 0) function g:types-unavailable-schema() {
    let $configured := g:configure("schema-type-un2", g:xsd("urn:example:un2", ""))
    let $before := count(schema:types("urn:example:un2")) gt 0
    let $removed := xmldb:remove("/db/schema-type-un2-schema", "s.xsd")
    let $after := count(schema:types("urn:example:un2"))
    let $dropped := g:drop("schema-type-un2")
    return (if ($before) then "listed" else "not listed", $after)
};

declare %test:assertEquals("listed", 0) function g:types-ambiguous-schema() {
    let $first := g:configure("schema-type-amb1", g:xsd("urn:example:amb", ""))
    let $alone := count(schema:types("urn:example:amb")) gt 0
    let $second := g:configure("schema-type-amb2", g:xsd("urn:example:amb", "other"))
    let $ambiguous := count(schema:types("urn:example:amb"))
    let $dropped := (g:drop("schema-type-amb1"), g:drop("schema-type-amb2"))
    return (if ($alone) then "listed" else "not listed", $ambiguous)
};
