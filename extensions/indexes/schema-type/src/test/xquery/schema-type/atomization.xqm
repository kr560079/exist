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
 : Stored nodes of a collection configured with the schema type index atomize to their schema-typed values;
 : nodes of unconfigured collections keep atomizing to xs:untypedAtomic.
 :)
module namespace a="http://exist-db.org/xquery/test/schema-type/atomization";

import module namespace fx="http://exist-db.org/xquery/test/schema-type/fixtures" at "fixtures.xqm";

declare namespace test="http://exist-db.org/xquery/xqsuite";
declare namespace t="urn:example:trade";

declare variable $a:doc := doc('/db/schema-type-test/trade-1.xml');
(:~ values whose typed order, equality and arithmetic differ from their untyped ones :)
declare variable $a:doc2 := doc('/db/schema-type-test/trade-2.xml');

declare %test:setUp function a:setUp() { fx:setUp() };

declare %test:tearDown function a:tearDown() { fx:tearDown() };

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

(: beyond the brief :)
declare %test:assertTrue function a:list-general-compare() { $a:doc//t:fixingDates = xs:date('2026-02-01') };
declare %test:assertTrue function a:attribute-typed() { data($a:doc//t:party/@id) instance of xs:ID };
declare %test:assertTrue function a:simple-content-compare() { $a:doc//t:tradeDate eq xs:date('2026-03-02') };
declare %test:assertFalse function a:unconfigured-not-nilled() { fn:nilled(doc('/db/plain/t1.xml')//*:tradeId) };

(: typed and untyped results differ :)
declare %test:assertEquals("9", "10") function a:order-by-typed() { for $v in $a:doc2//t:notional/t:amount order by $v return string($v) };
declare %test:assertEquals(1) function a:distinct-typed-equality() { count(distinct-values($a:doc2//t:fee/t:amount)) };
declare %test:assertTrue function a:arithmetic-decimal() { ($a:doc//t:notional/t:amount * 2) instance of xs:decimal };
declare %test:assertTrue function a:sum-decimal() { sum($a:doc2//t:notional/t:amount) instance of xs:decimal };
declare %test:assertEquals("19") function a:sum-value() { string(sum($a:doc2//t:notional/t:amount)) };
declare %test:assertTrue function a:max-decimal() { max($a:doc2//t:notional/t:amount) instance of xs:decimal };
declare %test:assertEquals("1.00") function a:format-typed() { format-number(($a:doc2//t:fee/t:amount)[1], "0.00") };

(: list-typed and nilled nodes in every atomizing context :)
declare %test:assertError("XPTY0004") function a:list-value-compare() { $a:doc//t:fixingDates eq xs:date('2026-01-01') };
declare %test:assertError("XPTY0004") function a:list-order-by() { for $t in $a:doc//t:trade order by $t/t:fixingDates return 1 };
declare %test:assertError("XPTY0004") function a:list-group-by() { for $t in $a:doc//t:trade group by $d := $t/t:fixingDates return 1 };
declare %test:assertError("XPTY0004") function a:list-switch() { switch ($a:doc//t:fixingDates) case xs:date('2026-01-01') return 1 default return 0 };
declare %test:assertError("XPTY0004") function a:list-cast() { $a:doc//t:fixingDates cast as xs:date };
declare %test:assertEquals(1) function a:list-general-compare-predicate() { count($a:doc//t:trade[t:fixingDates = xs:date('2026-02-01')]) };
declare %test:assertEquals(0) function a:list-general-compare-predicate-no-match() { count($a:doc//t:trade[t:fixingDates = xs:date('2026-03-01')]) };
declare %test:assertEquals(4) function a:list-sum() { sum($a:doc2//t:rates) };
declare %test:assertEmpty function a:nilled-value-compare() { $a:doc//t:tradeId eq "x" };
declare %test:assertEquals(0) function a:nilled-value-compare-predicate() { count($a:doc//t:trade[t:tradeId eq ""]) };
declare %test:assertFalse function a:nilled-general-compare() { $a:doc//t:tradeId = "" };
declare %test:assertEmpty function a:nilled-arithmetic() { $a:doc//t:tradeId * 2 };
declare %test:assertEquals(1, 2, 3) function a:nilled-order-by-empty-least() { for $t at $p in $a:doc//t:trade order by $t/t:tradeId empty least, $p return $p };
