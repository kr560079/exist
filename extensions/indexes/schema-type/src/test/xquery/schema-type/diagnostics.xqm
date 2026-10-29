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
declare %test:tearDown function g:tearDown() { fx:tearDown() };

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
declare %test:assertEquals(0) function g:types-no-anonymous() { count(schema:types("urn:example:trade")[@name = ""]) };
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
