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
 : Descendant steps with element(*, T), attribute(*, T), schema-element(N) and schema-attribute(N) tests are answered
 : from the schema type index (spec §7.5); element(N, T) and attribute(N, T) steps use the structural (name) index and
 : filter by type. Every test is differential: the query gives the same nodes, in the same order, with the optimizer
 : disabled (fx:same).
 :
 : The tests name schema types and declarations, so they evaluate their queries with util:eval after a prolog importing
 : the trade schema (this module is compiled before fx:setUp() stores it). A query over "//" from the root sees every
 : document of the database, including the unconfigured /db/plain, and is therefore evaluated without the index: the
 : tests which check that the index is used start from the configured collection.
 :
 : The setUp reindexes /db/schema-type-test, so that the schema documents fx:setUp() stores before the configuration
 : are not stale (EXSA0003) for queries over the whole collection.
 :
 : /db/schema-type-opt is configured with the schema and a legacy range index on t:amount, so that a comparison in a
 : predicate is answered from the range index and preloads the nodes of the typed step it compares.
 :)
module namespace o="http://exist-db.org/xquery/test/schema-type/optimizer-types";

import module namespace fx="http://exist-db.org/xquery/test/schema-type/fixtures" at "fixtures.xqm";

declare namespace test="http://exist-db.org/xquery/xqsuite";
declare namespace t="urn:example:trade";

declare variable $o:PROLOG := 'import schema namespace t="urn:example:trade"; ';
declare variable $o:V2_PROLOG := 'import schema namespace t="urn:example:trade" at "xmldb:exist:///db/schema-type-test-v2/trade.xsd"; ';
declare variable $o:C := 'collection("/db/schema-type-test")';
declare variable $o:OPT := 'collection("/db/schema-type-opt")';

declare variable $o:OPT_COLLECTION := "/db/schema-type-opt";
declare variable $o:OPT_CONFIG_COLLECTION := "/db/system/config/db/schema-type-opt";
declare variable $o:OPT_XCONF :=
    <collection xmlns="http://exist-db.org/collection-config/1.0">
        <index xmlns:t="urn:example:trade" xmlns:xs="http://www.w3.org/2001/XMLSchema">
            <schema-type href="xmldb:exist://{$fx:COLLECTION}/trade.xsd"/>
            <create qname="t:amount" type="xs:decimal"/>
        </index>
    </collection>;

declare variable $o:ROLES := <t:trades><t:trade><t:swap/><t:party id="p1" t:role="buyer"/><t:party id="p2" t:role="seller"/></t:trade></t:trades>;
declare variable $o:MEMO := <t:memo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:nil="true"/>;
declare variable $o:MISPLACED_ANY := <t:trades><t:trade><t:swap/><t:any/></t:trade></t:trades>;

declare %test:setUp function o:setUp() {
    fx:setUp(),
    xmldb:store($fx:COLLECTION, "roles.xml", $o:ROLES),
    xmldb:store($fx:COLLECTION, "memo.xml", $o:MEMO),
    xmldb:store($fx:COLLECTION, "misplaced-any.xml", $o:MISPLACED_ANY),
    (: index the schema documents, stored before the configuration, so that the collection has no stale document :)
    xmldb:reindex($fx:COLLECTION),
    xmldb:create-collection("/db", "schema-type-opt"),
    xmldb:create-collection("/db/system/config/db", "schema-type-opt"),
    xmldb:store($o:OPT_CONFIG_COLLECTION, "collection.xconf", $o:OPT_XCONF),
    xmldb:store($o:OPT_COLLECTION, "trade-1.xml", doc($fx:COLLECTION || "/trade-1.xml")),
    xmldb:store($o:OPT_COLLECTION, "trade-2.xml", doc($fx:COLLECTION || "/trade-2.xml"))
};

declare %test:tearDown function o:tearDown() {
    for $collection in ($o:OPT_COLLECTION, $o:OPT_CONFIG_COLLECTION)
    where xmldb:collection-available($collection)
    return
        xmldb:remove($collection),
    fx:tearDown()
};

(:~ fx:same with the trade schema imported :)
declare function o:same($expr as xs:string) as xs:boolean { fx:same($o:PROLOG, $expr) };

(:~ fx:uses-index with the trade schema imported :)
declare function o:uses-index($expr as xs:string, $index as xs:string) as xs:boolean { fx:uses-index($o:PROLOG || $expr, $index) };

declare function o:eval($expr as xs:string) { util:eval($o:PROLOG || $expr) };

(: --- the brief's tests --- :)

declare %test:assertTrue function o:element-star-same-results() { o:same('//element(*, t:Product)') };
declare %test:assertTrue function o:schema-element-same-results() { o:same('//schema-element(t:product)') };
declare %test:assertTrue function o:index-used() { o:uses-index($o:C || '//element(*, t:Product)', 'schema-type') };
(: collection#1 takes one URI here: the brief's collection(("/db/schema-type-test","/db/plain")) is written as two calls :)
declare %test:assertTrue function o:mixed-collections-fallback() { o:same('(collection("/db/schema-type-test"), collection("/db/plain"))//element(*, xs:anyType)') };

(: --- answered from the index --- :)

declare %test:assertTrue function o:schema-element-index-used() { o:uses-index($o:C || '//schema-element(t:product)', 'schema-type') };
declare %test:assertTrue function o:attribute-star-index-used() { o:uses-index($o:C || '//attribute(*, xs:ID)', 'schema-type') };
declare %test:assertFalse function o:plain-step-index-not-used() { o:uses-index($o:C || '//t:trade', 'schema-type') };
declare %test:assertFalse function o:optimizer-disabled-index-not-used() {
    fx:uses-index($o:PROLOG || 'declare option exist:optimize "enable=no"; ' || $o:C || '//element(*, t:Product)', 'schema-type')
};

declare %test:assertTrue function o:element-star-count() { o:eval('count(' || $o:C || '//element(*, t:Product))') ge 7 };
declare %test:assertTrue function o:element-star() { o:same($o:C || '//element(*, t:Product)') };
declare %test:assertTrue function o:element-star-derived() { o:same($o:C || '//element(*, t:Swap)') };
declare %test:assertTrue function o:element-star-builtin-base() { o:same($o:C || '//element(*, xs:decimal)') };
declare %test:assertTrue function o:element-star-user-simple() { o:same($o:C || '//element(*, t:NonNegativeDecimal)') };
declare %test:assertTrue function o:element-star-not-nillable() { o:same($o:C || '//element(*, xs:string)') };
declare %test:assertTrue function o:element-star-nillable() { o:same($o:C || '//element(*, xs:string?)') };
declare %test:assertTrue function o:element-star-untyped() { o:same($o:C || '//element(*, xs:untyped)') };
declare %test:assertTrue function o:schema-element() { o:same($o:C || '//schema-element(t:product)') };
declare %test:assertTrue function o:schema-element-member() { o:same($o:C || '//schema-element(t:swap)') };
declare %test:assertTrue function o:schema-element-nillable() { o:same($o:C || '//schema-element(t:memo)') };
declare %test:assertTrue function o:schema-element-any() { o:same($o:C || '//schema-element(t:any)') };
declare %test:assertTrue function o:attribute-star() { o:same($o:C || '//attribute(*, xs:ID)') };
declare %test:assertTrue function o:attribute-star-abbreviated() { o:same($o:C || '//@attribute(*, xs:ID)') };
declare %test:assertTrue function o:attribute-star-untyped-atomic() { o:same($o:C || '//attribute(*, xs:untypedAtomic)') };
declare %test:assertTrue function o:schema-attribute() { o:same($o:C || '//schema-attribute(t:role)') };

declare %test:assertTrue function o:descendant-from-elements() { o:same($o:C || '//t:trade/descendant::element(*, t:Product)') };
declare %test:assertTrue function o:descendant-or-self() { o:same($o:C || '//t:trade/*/descendant-or-self::element(*, t:Product)') };
declare %test:assertTrue function o:descendant-from-document() { o:same('doc("/db/schema-type-test/trade-1.xml")//element(*, t:Product)') };
declare %test:assertTrue function o:positional-predicate() { o:same($o:C || '//element(*, t:Product)[1]') };
declare %test:assertTrue function o:predicate() { o:same($o:C || '//element(*, t:Product)[self::t:swap]') };
declare %test:assertTrue function o:in-predicate() { o:same($o:C || '//t:trade[.//element(*, t:Swap)]') };
declare %test:assertTrue function o:in-predicate-schema-element() { o:same($o:C || '//t:trade[not(.//schema-element(t:product))]') };
declare %test:assertTrue function o:in-for() { o:same('for $d in ' || $o:C || ' return $d//element(*, t:Product)') };
declare %test:assertTrue function o:in-memory() {
    o:same('let $e := <t:trades><t:trade><t:swap/></t:trade></t:trades> return count($e//element(*, t:Product))')
};
declare %test:assertTrue function o:mixed-collections-user-type() {
    o:same('(collection("/db/schema-type-test"), collection("/db/plain"))//element(*, t:Product)')
};
declare %test:assertFalse function o:mixed-collections-index-not-used() {
    o:uses-index('(collection("/db/schema-type-test"), collection("/db/plain"))//element(*, t:Product)', 'schema-type')
};
declare %test:assertEquals("exerr:EXSA0001") function o:other-schema-version() {
    fx:same-error($o:V2_PROLOG, $o:C || '//element(*, t:Product)')
};

(: --- element(N, T) and attribute(N, T): the name index, filtered by type --- :)

declare %test:assertTrue function o:element-name-type() { o:same($o:C || '//element(t:amount, xs:decimal)') };
declare %test:assertTrue function o:element-name-user-type() { o:same($o:C || '//element(t:amount, t:NonNegativeDecimal)') };
declare %test:assertTrue function o:element-name-user-type-count() { o:eval('count(' || $o:C || '//element(t:amount, t:NonNegativeDecimal))') ge 3 };
declare %test:assertTrue function o:element-name-nilled() { o:same($o:C || '//element(t:tradeId, xs:string)') };
declare %test:assertTrue function o:element-name-child() { o:same($o:C || '//t:notional/element(t:amount, xs:decimal)') };
declare %test:assertTrue function o:attribute-name-type() { o:same($o:C || '//attribute(id, xs:ID)') };
declare %test:assertTrue function o:attribute-name-child() { o:same($o:C || '//t:party/attribute(id, xs:ID)') };
declare %test:assertTrue function o:self-name-then-typed() { o:same($o:C || '//t:amount/self::element(t:amount, t:NonNegativeDecimal)') };
declare %test:assertTrue function o:self-typed-then-name() { o:same($o:C || '//element(t:amount, t:NonNegativeDecimal)/self::t:amount') };
declare %test:assertTrue function o:document-node-element() { o:same($o:C || '/document-node(element(t:trades, xs:anyType))') };

(: --- preloaded steps: a comparison answered by the (legacy) range index preloads the typed step --- :)

declare %test:assertTrue function o:preloaded-range-index-used() {
    o:uses-index($o:OPT || '//t:fee[element(t:amount, t:NonNegativeDecimal) = -5]', 'range')
};
declare %test:assertEquals(0) function o:preloaded-typed-child() {
    o:eval('count(' || $o:OPT || '//t:fee[element(t:amount, t:NonNegativeDecimal) = -5])')
};
declare %test:assertTrue function o:preloaded-typed-child-same() { o:same($o:OPT || '//t:fee[element(t:amount, t:NonNegativeDecimal) = -5]') };
declare %test:assertEquals(1) function o:preloaded-typed-child-match() {
    o:eval('count(' || $o:OPT || '//t:fee[element(t:amount, xs:decimal) = -5])')
};
declare %test:assertEquals(0) function o:preloaded-typed-self() {
    o:eval('count(' || $o:OPT || '//t:amount[self::element(*, t:NonNegativeDecimal) = -5])')
};
declare %test:assertTrue function o:preloaded-typed-self-same() { o:same($o:OPT || '//t:amount[self::element(*, t:NonNegativeDecimal) = -5]') };
declare %test:assertEquals(1) function o:preloaded-typed-self-match() {
    o:eval('count(' || $o:OPT || '//t:amount[self::element(*, t:NonNegativeDecimal) = 1000000])')
};
declare %test:assertEquals(0) function o:preloaded-schema-element-self() {
    o:eval('count(' || $o:OPT || '//t:amount[self::schema-element(t:product) = -5])')
};
