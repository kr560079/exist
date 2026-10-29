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
 : Comparisons of a schema-typed path with a literal in the first predicate of a descendant step
 : (step[path op literal], step[. op literal]) are answered from the typed-value index (spec §6.3, §7.5). Every test is
 : differential: the query gives the same nodes, in the same order, with the optimizer disabled (fx:same), or raises
 : the same error both ways (fx:same-error).
 :
 : The trade queries name schema types, so they are evaluated with util:eval after a prolog importing the trade schema.
 : /db/schema-type-values is configured with values.xsd (stored by the setUp, below), whose elements cover every family
 : of typed-value keys; its <typed-values> list every type of the schema but xs:long. Its documents:
 :   values.xml   one value of each type per item: negative, zero and tiny numbers, NaN, ±0, ±INF, dates and times
 :                with and without timezone, durations, lists, a union of a number and a string, a nilled element;
 :   dup.xml      an item with two vv:dec children (value comparisons over it raise XPTY0004 or compare each);
 :   untyped.xml  items below a processContents="skip" wildcard, which are not annotated;
 :   upd-*.xml    documents the update tests change.
 : /db/schema-type-vrange is configured with the trade schema, its typed values and a legacy range index on t:amount.
 :)
module namespace v="http://exist-db.org/xquery/test/schema-type/optimizer-values";

import module namespace fx="http://exist-db.org/xquery/test/schema-type/fixtures" at "fixtures.xqm";

declare namespace test="http://exist-db.org/xquery/xqsuite";
declare namespace t="urn:example:trade";
declare namespace vv="urn:example:values";

declare variable $v:PROLOG := 'import schema namespace t="urn:example:trade"; ';
declare variable $v:VPROLOG := 'declare namespace vv="urn:example:values"; ';
declare variable $v:C := 'collection("/db/schema-type-test")';
declare variable $v:R := 'collection("/db/schema-type-vrange")';
declare variable $v:V := 'collection("/db/schema-type-values")';
declare variable $v:VD := 'doc("/db/schema-type-values/values.xml")';

declare variable $v:VALUES_COLLECTION := "/db/schema-type-values";
declare variable $v:VALUES_CONFIG_COLLECTION := "/db/system/config/db/schema-type-values";
declare variable $v:RANGE_COLLECTION := "/db/schema-type-vrange";
declare variable $v:RANGE_CONFIG_COLLECTION := "/db/system/config/db/schema-type-vrange";

declare variable $v:XSD :=
    <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:vv="urn:example:values"
               targetNamespace="urn:example:values" elementFormDefault="qualified">
        <xs:simpleType name="DecimalList"><xs:list itemType="xs:decimal"/></xs:simpleType>
        <xs:simpleType name="Mixed"><xs:union memberTypes="xs:decimal xs:string"/></xs:simpleType>
        <xs:element name="items">
            <xs:complexType>
                <xs:sequence>
                    <xs:element name="item" minOccurs="0" maxOccurs="unbounded">
                        <xs:complexType>
                            <xs:sequence>
                                <xs:element name="d" type="xs:double" minOccurs="0"/>
                                <xs:element name="f" type="xs:float" minOccurs="0"/>
                                <xs:element name="dec" type="xs:decimal" minOccurs="0" maxOccurs="unbounded"/>
                                <xs:element name="int" type="xs:integer" minOccurs="0"/>
                                <xs:element name="long" type="xs:long" minOccurs="0"/>
                                <xs:element name="s" type="xs:string" minOccurs="0"/>
                                <xs:element name="date" type="xs:date" minOccurs="0"/>
                                <xs:element name="dt" type="xs:dateTime" minOccurs="0"/>
                                <xs:element name="tm" type="xs:time" minOccurs="0"/>
                                <xs:element name="dl" type="vv:DecimalList" minOccurs="0"/>
                                <xs:element name="mx" type="vv:Mixed" minOccurs="0"/>
                                <xs:element name="n" type="xs:decimal" nillable="true" minOccurs="0"/>
                            </xs:sequence>
                            <xs:attribute name="code" type="xs:string"/>
                        </xs:complexType>
                    </xs:element>
                    <xs:element name="skip" minOccurs="0">
                        <xs:complexType>
                            <xs:sequence>
                                <xs:any processContents="skip" minOccurs="0" maxOccurs="unbounded"/>
                            </xs:sequence>
                        </xs:complexType>
                    </xs:element>
                </xs:sequence>
            </xs:complexType>
        </xs:element>
    </xs:schema>;

declare variable $v:VALUES_XCONF :=
    <collection xmlns="http://exist-db.org/collection-config/1.0">
        <index xmlns:vv="urn:example:values" xmlns:xs="http://www.w3.org/2001/XMLSchema">
            <schema-type href="xmldb:exist://{$v:VALUES_COLLECTION}/values.xsd"/>
            <typed-values>
                <type name="xs:double"/>
                <type name="xs:float"/>
                <type name="xs:decimal"/>
                <type name="xs:integer"/>
                <type name="xs:string"/>
                <type name="xs:date"/>
                <type name="xs:dateTime"/>
                <type name="xs:time"/>
                <type name="vv:DecimalList"/>
                <type name="vv:Mixed"/>
            </typed-values>
        </index>
    </collection>;

declare variable $v:RANGE_XCONF :=
    <collection xmlns="http://exist-db.org/collection-config/1.0">
        <index xmlns:t="urn:example:trade" xmlns:xs="http://www.w3.org/2001/XMLSchema">
            <schema-type href="xmldb:exist://{$fx:COLLECTION}/trade.xsd"/>
            <typed-values>
                <type name="xs:decimal"/>
                <type name="t:NonNegativeDecimal"/>
            </typed-values>
            <create qname="t:amount" type="xs:decimal"/>
        </index>
    </collection>;

declare variable $v:VALUES :=
    <vv:items xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
        <vv:item code="a">
            <vv:d>1.5</vv:d><vv:f>1.5</vv:f><vv:dec>9.5</vv:dec><vv:int>9</vv:int><vv:long>5</vv:long>
            <vv:s>apple</vv:s><vv:date>2026-01-01</vv:date><vv:dt>2026-01-01T10:00:00Z</vv:dt><vv:tm>10:00:00Z</vv:tm><vv:dl>1 2 3</vv:dl><vv:mx>5</vv:mx><vv:n xsi:nil="true"/>
        </vv:item>
        <vv:item code="b">
            <vv:d>NaN</vv:d><vv:f>-0</vv:f><vv:dec>10</vv:dec><vv:int>10</vv:int>
            <vv:s>Banana</vv:s><vv:date>2026-01-01+14:00</vv:date><vv:dt>2026-01-01T10:00:00</vv:dt><vv:tm>10:00:00</vv:tm><vv:dl/><vv:mx>abc</vv:mx>
        </vv:item>
        <vv:item code="c">
            <vv:d>INF</vv:d><vv:f>INF</vv:f><vv:dec>-1</vv:dec><vv:int>-12345678901234567890</vv:int>
            <vv:s>banana</vv:s><vv:date>2025-12-31-14:00</vv:date><vv:dt>2026-01-01T00:00:00+14:00</vv:dt>
            <vv:tm>23:59:59-14:00</vv:tm><vv:dl>10</vv:dl><vv:n>7</vv:n>
        </vv:item>
        <vv:item code="d">
            <vv:d>-INF</vv:d><vv:f>0</vv:f><vv:dec>0.000000000000000000001</vv:dec><vv:int>0</vv:int>
            <vv:s></vv:s><vv:date>2026-01-02</vv:date><vv:dt>2025-12-31T23:00:00-11:00</vv:dt><vv:tm>00:00:00</vv:tm>
        </vv:item>
        <vv:item code="e">
            <vv:d>-0</vv:d><vv:dec>-0.5</vv:dec><vv:int>123456789012345678901234567890</vv:int><vv:s>apple pie</vv:s>
            <vv:date>1999-12-31Z</vv:date>
        </vv:item>
        <vv:item code="f">
            <vv:d>1e308</vv:d><vv:dec>99999999999999999999.99</vv:dec><vv:int>-1</vv:int><vv:s>z</vv:s>
            <vv:date>2026-01-01Z</vv:date>
        </vv:item>
        <vv:item code="g">
            <vv:d>0</vv:d><vv:f>1.4</vv:f><vv:dec>1</vv:dec><vv:int>1</vv:int>
        </vv:item>
    </vv:items>;

declare variable $v:DUP := <vv:items><vv:item><vv:dec>5</vv:dec><vv:dec>6</vv:dec></vv:item></vv:items>;
declare variable $v:UNTYPED :=
    <vv:items><vv:item><vv:dec>3</vv:dec></vv:item><vv:skip><vv:item><vv:dec>4</vv:dec></vv:item></vv:skip></vv:items>;
(: long strings: their keys are cut at 256 bytes, here within a multi-byte character :)
declare variable $v:A254 := string-join((1 to 254) ! "a");
declare variable $v:A255 := $v:A254 || "a";
declare variable $v:A256 := $v:A255 || "a";
declare variable $v:LONG-STRINGS := (
    $v:A255 || "éx", $v:A255 || "b", $v:A255 || "bz", $v:A255 || "é", $v:A255 || "ê", $v:A255, $v:A254 || "中",
    $v:A254 || "中x", $v:A254 || "é", $v:A256 || "é", $v:A256, $v:A256 || "b", "z", "a");
(: the review's counterexample (a255 || "b", which a255 || "é..." is greater than), and literals cut within a character :)
declare variable $v:LONG-LITERALS := ($v:A255 || "b", $v:A255 || "éx", $v:A255 || "é", $v:A254 || "中x", $v:A256, $v:A255);
declare variable $v:LONG := <vv:items>{ $v:LONG-STRINGS ! <vv:item><vv:s>{.}</vv:s></vv:item> }</vv:items>;
declare variable $v:UPD := <vv:items><vv:item><vv:dec>1</vv:dec></vv:item><vv:item><vv:dec>2</vv:dec></vv:item></vv:items>;

declare %test:setUp function v:setUp() {
    fx:setUp(),
    (: index the schema documents, stored before the configuration, so that the collection has no stale document :)
    xmldb:reindex($fx:COLLECTION),
    xmldb:create-collection("/db", "schema-type-values"),
    xmldb:store($v:VALUES_COLLECTION, "values.xsd", $v:XSD),
    xmldb:create-collection("/db/system/config/db", "schema-type-values"),
    xmldb:store($v:VALUES_CONFIG_COLLECTION, "collection.xconf", $v:VALUES_XCONF),
    xmldb:store($v:VALUES_COLLECTION, "values.xml", $v:VALUES),
    xmldb:store($v:VALUES_COLLECTION, "dup.xml", $v:DUP),
    xmldb:store($v:VALUES_COLLECTION, "untyped.xml", $v:UNTYPED),
    for $i in 1 to 5 return xmldb:store($v:VALUES_COLLECTION, "upd-" || $i || ".xml", $v:UPD),
    xmldb:store($v:VALUES_COLLECTION, "long.xml", $v:LONG),
    xmldb:reindex($v:VALUES_COLLECTION),
    xmldb:create-collection("/db", "schema-type-vrange"),
    xmldb:create-collection("/db/system/config/db", "schema-type-vrange"),
    xmldb:store($v:RANGE_CONFIG_COLLECTION, "collection.xconf", $v:RANGE_XCONF),
    xmldb:store($v:RANGE_COLLECTION, "trade-1.xml", doc($fx:COLLECTION || "/trade-1.xml")),
    xmldb:store($v:RANGE_COLLECTION, "trade-2.xml", doc($fx:COLLECTION || "/trade-2.xml"))
};

declare %test:tearDown function v:tearDown() {
    for $collection in ($v:VALUES_COLLECTION, $v:VALUES_CONFIG_COLLECTION, $v:RANGE_COLLECTION, $v:RANGE_CONFIG_COLLECTION)
    where xmldb:collection-available($collection)
    return
        xmldb:remove($collection),
    fx:tearDown()
};

(: --- helpers --- :)

(:~ fx:same with the trade schema imported :)
declare function v:same($expr as xs:string) as xs:boolean { fx:same($v:PROLOG, $expr) };
(:~ fx:uses-index of the schema-type index with the trade schema imported :)
declare function v:uses-index($expr as xs:string) as xs:boolean { fx:uses-index($v:PROLOG || $expr, 'schema-type') };
declare function v:eval($expr as xs:string) { util:eval($v:PROLOG || $expr) };

declare function v:vsame($expr as xs:string) as xs:boolean { fx:same($v:VPROLOG, $expr) };
declare function v:vuses-index($expr as xs:string) as xs:boolean { fx:uses-index($v:VPROLOG || $expr, 'schema-type') };
declare function v:veval($expr as xs:string) { util:eval($v:VPROLOG || $expr) };

(:~ the same items, or the same error, optimized and unoptimized :)
declare function v:vsame-or-error($expr as xs:string) as xs:boolean {
    v:vsame($expr) or not(fx:same-error($v:VPROLOG, $expr) = ("no error", "different"))
};

(: --- the brief's tests --- :)

declare %test:assertTrue function v:gt-same-results() { v:same('//t:notional[t:amount > 500000]') };
(: "//" from the root includes the untyped /db/plain/t1.xml, whose tradeDate atomizes to xs:untypedAtomic, which a value
 : comparison compares as xs:string: XPTY0004 both ways. The same query over the configured collection has results. :)
declare %test:assertEquals("err:XPTY0004") function v:date-range-same-results() {
    fx:same-error($v:PROLOG, '//t:trade[t:tradeDate ge xs:date("2026-01-01")]')
};
declare %test:assertTrue function v:date-range-same-results-collection() {
    v:same($v:C || '//t:trade[t:tradeDate ge xs:date("2026-01-01")]')
};
(: from the configured collection: "//" from the root includes the unconfigured /db/plain and falls back :)
declare %test:assertTrue function v:index-used() { v:uses-index($v:C || '//t:notional[t:amount > 500000]') };
(: t:CurrencyCode is not in <typed-values> :)
declare %test:assertTrue function v:unlisted-type-not-rewritten() { not(v:uses-index($v:C || '//t:notional[t:currency = "USD"]')) };

(: --- trades: decimal and t:NonNegativeDecimal amounts --- :)

declare %test:assertTrue function v:gt() { v:same($v:C || '//t:notional[t:amount > 500000]') };
declare %test:assertEquals(2) function v:gt-count() { v:eval('count(' || $v:C || '//t:notional[t:amount > 500000])') };
(: typed, not string, order: 10 > 9 :)
declare %test:assertTrue function v:nine-vs-ten() { v:same($v:C || '//t:notional[t:amount > 9]') };
declare %test:assertEquals(3) function v:nine-vs-ten-count() { v:eval('count(' || $v:C || '//t:notional[t:amount > 9])') };
declare %test:assertTrue function v:lt-ten() { v:same($v:C || '//t:notional[t:amount lt 10]') };
declare %test:assertEquals(1) function v:lt-ten-count() { v:eval('count(' || $v:C || '//t:notional[t:amount lt 10])') };
declare %test:assertTrue function v:negative-fee() { v:same($v:C || '//t:fee[t:amount < 0]') };
declare %test:assertEquals(2) function v:negative-fee-count() { v:eval('count(' || $v:C || '//t:fee[t:amount < 0])') };
declare %test:assertTrue function v:negative-fee-index-used() { v:uses-index($v:C || '//t:fee[t:amount < 0]') };
declare %test:assertTrue function v:fee-typed-equality() { v:same($v:C || '//t:fee[t:amount = 1]') };
declare %test:assertEquals(2) function v:fee-typed-equality-count() { v:eval('count(' || $v:C || '//t:fee[t:amount = 1])') };
declare %test:assertTrue function v:sequence-literal() { v:same($v:C || '//t:notional[t:amount = (9, 1000000)]') };
declare %test:assertEquals(3) function v:sequence-literal-count() { v:eval('count(' || $v:C || '//t:notional[t:amount = (9, 1000000)])') };
declare %test:assertTrue function v:sequence-literal-index-used() { v:uses-index($v:C || '//t:notional[t:amount = (9, 1000000)]') };
declare %test:assertTrue function v:reversed() { v:same($v:C || '//t:notional[500000 < t:amount]') };
declare %test:assertTrue function v:value-gt() { v:same($v:C || '//t:notional[t:amount gt 500000]') };
declare %test:assertTrue function v:value-gt-index-used() { v:uses-index($v:C || '//t:notional[t:amount gt 500000]') };
declare %test:assertTrue function v:value-eq() { v:same($v:C || '//t:notional[t:amount eq 10]') };
declare %test:assertTrue function v:self() { v:same($v:C || '//t:amount[. > 500000]') };
declare %test:assertTrue function v:self-index-used() { v:uses-index($v:C || '//t:amount[. > 500000]') };
declare %test:assertTrue function v:self-value() { v:same($v:C || '//t:amount[. le 9]') };
declare %test:assertTrue function v:ne() { v:same($v:C || '//t:notional[t:amount != 9]') };
declare %test:assertFalse function v:ne-not-rewritten() { v:uses-index($v:C || '//t:notional[t:amount != 9]') };
declare %test:assertTrue function v:user-typed-literal() { v:same($v:C || '//t:notional[t:amount > t:NonNegativeDecimal("500000")]') };
declare %test:assertTrue function v:double-literal() { v:same($v:C || '//t:notional[t:amount > 5e5]') };
declare %test:assertTrue function v:positional-after() { v:same($v:C || '//t:notional[t:amount > 9][1]') };
declare %test:assertTrue function v:other-predicate-after() { v:same($v:C || '//t:notional[t:amount > 9][t:currency = "EUR"]') };
declare %test:assertTrue function v:in-for() { v:same('for $n in ' || $v:C || '//t:notional[t:amount > 9] return $n/t:currency') };
declare %test:assertTrue function v:from-document() { v:same('doc("/db/schema-type-test/trade-2.xml")//t:notional[t:amount > 9]') };
(: an xs:string literal does not compare with an xs:decimal value :)
declare %test:assertEquals("err:XPTY0004") function v:string-literal-error() {
    fx:same-error($v:PROLOG, $v:C || '//t:notional[t:amount = "500000"]')
};
declare %test:assertTrue function v:mixed-collections() {
    v:same('(collection("/db/schema-type-test"), collection("/db/plain"))//t:notional[t:amount > 9]')
};
declare %test:assertFalse function v:mixed-collections-index-not-used() {
    v:uses-index('(collection("/db/schema-type-test"), collection("/db/plain"))//t:notional[t:amount > 9]')
};
(: a legacy range index on the compared path: left to that index :)
declare %test:assertTrue function v:range-index-alongside() { v:same($v:R || '//t:notional[t:amount > 9]') };
declare %test:assertTrue function v:range-index-alongside-fee() { v:same($v:R || '//t:fee[t:amount < 0]') };
declare %test:assertFalse function v:range-index-alongside-not-rewritten() { v:uses-index($v:R || '//t:notional[t:amount > 9]') };

(: --- doubles and floats: NaN, ±0, ±INF, decimal literals --- :)

declare %test:assertTrue function v:double-gt() { v:vsame($v:V || '//vv:item[vv:d > 1]') };
declare %test:assertTrue function v:double-gt-index-used() { v:vuses-index($v:VD || '//vv:item[vv:d > 1]') };
declare %test:assertTrue function v:double-zero() { v:vsame($v:V || '//vv:item[vv:d = 0]') };
declare %test:assertTrue function v:double-eq-zero() { v:vsame($v:VD || '//vv:item[vv:d eq 0]') };
declare %test:assertTrue function v:double-nan-literal() { v:vsame($v:V || '//vv:item[vv:d = xs:double("NaN")]') };
declare %test:assertTrue function v:double-ne() { v:vsame($v:V || '//vv:item[vv:d != 1.5]') };
declare %test:assertTrue function v:double-inf() { v:vsame($v:V || '//vv:item[vv:d >= xs:double("INF")]') };
declare %test:assertTrue function v:double-lt-neg-inf() { v:vsame($v:V || '//vv:item[vv:d < xs:double("-INF")]') };
declare %test:assertTrue function v:double-le-neg-inf() { v:vsame($v:V || '//vv:item[vv:d le xs:double("-INF")]') };
declare %test:assertTrue function v:double-lt() { v:vsame($v:V || '//vv:item[vv:d < 2]') };
declare %test:assertTrue function v:double-decimal-literal() { v:vsame($v:V || '//vv:item[vv:d > 1.4]') };
declare %test:assertTrue function v:double-large() { v:vsame($v:V || '//vv:item[vv:d > 1e307]') };
declare %test:assertTrue function v:float-gt() { v:vsame($v:V || '//vv:item[vv:f > 1.4]') };
declare %test:assertTrue function v:float-eq() { v:vsame($v:V || '//vv:item[vv:f = 1.4]') };
declare %test:assertTrue function v:float-double-literal() { v:vsame($v:V || '//vv:item[vv:f = 1.4e0]') };
declare %test:assertTrue function v:float-float-literal() { v:vsame($v:V || '//vv:item[vv:f = xs:float("1.4")]') };
declare %test:assertTrue function v:float-zero() { v:vsame($v:V || '//vv:item[vv:f = 0]') };

(: --- decimals and integers: negative, tiny, many digits --- :)

declare %test:assertTrue function v:decimal-gt() { v:vsame($v:V || '//vv:item[vv:dec > 9]') };
declare %test:assertTrue function v:decimal-gt-index-used() { v:vuses-index($v:VD || '//vv:item[vv:dec > 9]') };
declare %test:assertTrue function v:decimal-tiny() { v:vsame($v:V || '//vv:item[vv:dec > 0]') };
declare %test:assertTrue function v:decimal-tiny-lt() { v:vsame($v:VD || '//vv:item[vv:dec lt 0.000000000000000000002]') };
declare %test:assertTrue function v:decimal-negative() { v:vsame($v:V || '//vv:item[vv:dec <= -0.5]') };
declare %test:assertTrue function v:decimal-large() { v:vsame($v:V || '//vv:item[vv:dec > 99999999999999999999.98]') };
declare %test:assertTrue function v:decimal-double-literal() { v:vsame($v:V || '//vv:item[vv:dec > 9.4e0]') };
declare %test:assertTrue function v:decimal-float-literal() { v:vsame($v:V || '//vv:item[vv:dec >= xs:float("9.5")]') };
declare %test:assertTrue function v:integer-big() { v:vsame($v:V || '//vv:item[vv:int > 100000000000000000000]') };
declare %test:assertTrue function v:integer-big-negative() { v:vsame($v:V || '//vv:item[vv:int < -10000000000000000000]') };
declare %test:assertTrue function v:integer-decimal-literal() { v:vsame($v:V || '//vv:item[vv:int = 9.0]') };
declare %test:assertTrue function v:unlisted-long() { v:vsame($v:VD || '//vv:item[vv:long > 1]') };
declare %test:assertFalse function v:unlisted-long-not-rewritten() { v:vuses-index($v:VD || '//vv:item[vv:long > 1]') };
declare %test:assertTrue function v:nilled() { v:vsame($v:V || '//vv:item[vv:n > 1]') };

(: --- value comparisons over several nodes, lists, unions --- :)

declare %test:assertTrue function v:dup-children-eq() { v:vsame-or-error($v:V || '//vv:item[vv:dec eq 5]') };
declare %test:assertTrue function v:dup-children-general() { v:vsame($v:V || '//vv:item[vv:dec = 6]') };
declare %test:assertTrue function v:list-general() { v:vsame($v:V || '//vv:item[vv:dl = 2]') };
declare %test:assertTrue function v:list-general-index-used() { v:vuses-index($v:VD || '//vv:item[vv:dl = 2]') };
declare %test:assertTrue function v:list-general-gt() { v:vsame($v:V || '//vv:item[vv:dl > 5]') };
declare %test:assertEquals("err:XPTY0004") function v:list-eq-error() { fx:same-error($v:VPROLOG, $v:VD || '//vv:item[vv:dl eq 2]') };
declare %test:assertFalse function v:list-eq-not-rewritten() { v:vuses-index($v:VD || '//vv:item[vv:dl eq 10]') };
declare %test:assertTrue function v:union-mixed() { v:vsame-or-error($v:V || '//vv:item[vv:mx = 5]') };

(: --- strings (codepoint order) and attributes --- :)

declare %test:assertTrue function v:string-eq() { v:vsame($v:V || '//vv:item[vv:s = "apple"]') };
declare %test:assertTrue function v:string-eq-index-used() { v:vuses-index($v:VD || '//vv:item[vv:s = "apple"]') };
declare %test:assertTrue function v:string-gt() { v:vsame($v:V || '//vv:item[vv:s > "a"]') };
declare %test:assertTrue function v:string-lt() { v:vsame($v:VD || '//vv:item[vv:s lt "b"]') };
declare %test:assertTrue function v:string-empty() { v:vsame($v:V || '//vv:item[vv:s = ""]') };
declare %test:assertTrue function v:string-sequence() { v:vsame($v:V || '//vv:item[vv:s = ("apple", "z")]') };
declare %test:assertTrue function v:attribute() { v:vsame($v:V || '//vv:item[@code = "b"]') };
declare %test:assertTrue function v:attribute-index-used() { v:vuses-index($v:VD || '//vv:item[@code = "b"]') };
declare %test:assertTrue function v:attribute-gt() { v:vsame($v:V || '//vv:item[@code > "c"]') };
declare %test:assertTrue function v:string-number() { v:vsame-or-error($v:V || '//vv:item[vv:s = 1]') };

(: --- dates and times with and without timezone --- :)

declare %test:assertTrue function v:date-ge-tzless() { v:vsame($v:V || '//vv:item[vv:date ge xs:date("2026-01-01")]') };
declare %test:assertTrue function v:date-eq-tz() { v:vsame($v:V || '//vv:item[vv:date = xs:date("2026-01-01Z")]') };
declare %test:assertTrue function v:date-lt-tz() { v:vsame($v:V || '//vv:item[vv:date lt xs:date("2026-01-01+05:00")]') };
declare %test:assertTrue function v:date-gt-tzless() { v:vsame($v:V || '//vv:item[vv:date > xs:date("2025-12-31")]') };
declare %test:assertTrue function v:date-index-used() { v:vuses-index($v:VD || '//vv:item[vv:date ge xs:date("2026-01-01Z")]') };
declare %test:assertTrue function v:datetime-gt() { v:vsame($v:V || '//vv:item[vv:dt > xs:dateTime("2026-01-01T00:00:00Z")]') };
declare %test:assertTrue function v:datetime-eq-tzless() { v:vsame($v:VD || '//vv:item[vv:dt eq xs:dateTime("2026-01-01T10:00:00")]') };
declare %test:assertTrue function v:time-lt() { v:vsame($v:V || '//vv:item[vv:tm < xs:time("12:00:00Z")]') };
declare %test:assertTrue function v:time-gt-tzless() { v:vsame($v:V || '//vv:item[vv:tm > xs:time("00:00:00")]') };
declare %test:assertTrue function v:date-vs-datetime() { v:vsame-or-error($v:V || '//vv:item[vv:date = xs:dateTime("2026-01-01T00:00:00Z")]') };

(: --- durations: XML Schema 1.0 has no xs:dayTimeDuration or xs:yearMonthDuration elements; their keys are tested by
 : TypedKeyOrderTest --- :)

(: --- unannotated nodes: the whole step falls back --- :)

declare %test:assertTrue function v:untyped() { v:vsame('doc("/db/schema-type-values/untyped.xml")//vv:item[vv:dec > 0]') };
declare %test:assertEquals(2) function v:untyped-count() { v:veval('count(doc("/db/schema-type-values/untyped.xml")//vv:item[vv:dec > 0])') };
declare %test:assertFalse function v:untyped-not-rewritten() { v:vuses-index('doc("/db/schema-type-values/untyped.xml")//vv:item[vv:dec > 0]') };

(: --- index maintenance: updates followed by a range query --- :)

declare %test:assertTrue function v:update-replace() {
    let $u := update replace doc("/db/schema-type-values/upd-1.xml")/vv:items/vv:item[1]/vv:dec with <vv:dec>777</vv:dec>
    return
        empty($u)
        and v:vsame('doc("/db/schema-type-values/upd-1.xml")//vv:item[vv:dec > 700]')
        and v:veval('count(doc("/db/schema-type-values/upd-1.xml")//vv:item[vv:dec > 700])') eq 1
        and v:veval('count(doc("/db/schema-type-values/upd-1.xml")//vv:item[vv:dec = 1])') eq 0
        and v:vuses-index('doc("/db/schema-type-values/upd-1.xml")//vv:item[vv:dec > 700]')
};
declare %test:assertTrue function v:update-insert() {
    let $u := update insert <vv:item><vv:dec>888</vv:dec></vv:item> into doc("/db/schema-type-values/upd-2.xml")/vv:items
    return
        empty($u)
        and v:vsame('doc("/db/schema-type-values/upd-2.xml")//vv:item[vv:dec > 800]')
        and v:veval('count(doc("/db/schema-type-values/upd-2.xml")//vv:item[vv:dec > 800])') eq 1
        and v:vuses-index('doc("/db/schema-type-values/upd-2.xml")//vv:item[vv:dec > 800]')
};
declare %test:assertTrue function v:update-value() {
    let $u := update value doc("/db/schema-type-values/upd-3.xml")/vv:items/vv:item[1]/vv:dec with "555"
    return
        empty($u)
        and v:vsame('doc("/db/schema-type-values/upd-3.xml")//vv:item[vv:dec > 500]')
        and v:veval('count(doc("/db/schema-type-values/upd-3.xml")//vv:item[vv:dec > 500])') eq 1
        and v:veval('count(doc("/db/schema-type-values/upd-3.xml")//vv:item[vv:dec = 1])') eq 0
};

(: --- updates which make the index incomplete, or leave it complete --- :)

declare %test:assertTrue function v:update-insert-unannotated() {
    let $before := v:vuses-index('doc("/db/schema-type-values/upd-4.xml")//vv:item[vv:dec > 8]')
    let $u := update insert <vv:skip><vv:item><vv:dec>9</vv:dec></vv:item></vv:skip> into doc("/db/schema-type-values/upd-4.xml")/vv:items
    return
        $before and empty($u)
        and v:vsame('doc("/db/schema-type-values/upd-4.xml")//vv:item[vv:dec > 8]')
        and v:veval('count(doc("/db/schema-type-values/upd-4.xml")//vv:item[vv:dec > 8])') eq 1
        (: the unannotated vv:dec is flagged in the document's summary :)
        and not(v:vuses-index('doc("/db/schema-type-values/upd-4.xml")//vv:item[vv:dec > 8]'))
        (: removing it again leaves the summary conservative: still the usual evaluation, still right :)
        and empty(update delete doc("/db/schema-type-values/upd-4.xml")/vv:items/vv:skip)
        and v:vsame('doc("/db/schema-type-values/upd-4.xml")//vv:item[vv:dec > 8]')
        and v:veval('count(doc("/db/schema-type-values/upd-4.xml")//vv:item[vv:dec > 8])') eq 0
};
declare %test:assertTrue function v:update-delete() {
    let $u := update delete doc("/db/schema-type-values/upd-5.xml")/vv:items/vv:item[1]
    return
        empty($u)
        and v:vsame('doc("/db/schema-type-values/upd-5.xml")//vv:item[vv:dec >= 1]')
        and v:veval('count(doc("/db/schema-type-values/upd-5.xml")//vv:item[vv:dec >= 1])') eq 1
        and v:veval('count(doc("/db/schema-type-values/upd-5.xml")//vv:item[vv:dec = 1])') eq 0
        and v:vuses-index('doc("/db/schema-type-values/upd-5.xml")//vv:item[vv:dec >= 1]')
};

(: --- long strings: keys cut at 256 bytes, within a multi-byte character --- :)

declare function v:long-same($op as xs:string) as xs:boolean {
    every $literal in $v:LONG-LITERALS satisfies
        v:vsame('doc("/db/schema-type-values/long.xml")//vv:item[vv:s ' || $op || ' "' || $literal || '"]')
};

declare %test:assertTrue function v:long-string-eq() { v:long-same("=") };
declare %test:assertTrue function v:long-string-lt() { v:long-same("<") };
declare %test:assertTrue function v:long-string-le() { v:long-same("<=") };
declare %test:assertTrue function v:long-string-gt() { v:long-same(">") };
declare %test:assertTrue function v:long-string-ge() { v:long-same(">=") };
declare %test:assertTrue function v:long-string-value-gt() { v:long-same("gt") };
declare %test:assertTrue function v:long-string-value-eq() { v:long-same("eq") };
declare %test:assertTrue function v:long-string-uses-index() {
    every $op in ("=", "<", "<=", ">", ">=") satisfies
        v:vuses-index('doc("/db/schema-type-values/long.xml")//vv:item[vv:s ' || $op || ' "' || $v:A255 || 'b"]')
};
(: the review's counterexample: a255 || "é..." > a255 || "b", though it sorts before it when cut at a character :)
declare %test:assertEquals(8) function v:long-string-gt-count() {
    v:veval('count(doc("/db/schema-type-values/long.xml")//vv:item[vv:s > "' || $v:A255 || 'b"])')
};
declare %test:assertEquals(2) function v:long-string-eq-count() {
    v:veval('count(doc("/db/schema-type-values/long.xml")//vv:item[vv:s = "' || $v:A255 || 'é"])')
        + v:veval('count(doc("/db/schema-type-values/long.xml")//vv:item[vv:s = "' || $v:A255 || 'éx"])')
};
