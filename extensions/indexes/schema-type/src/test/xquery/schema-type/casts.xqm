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
 : Casts, castable expressions and constructor functions for user-defined simple types (XQuery 3.1 §3.14,
 : Functions and Operators 3.1 §19). The tests import the trade schema, which is stored by fx:setUp() after this
 : module is compiled, so they evaluate their expressions with util:eval after a prolog importing the schema.
 :)
module namespace c="http://exist-db.org/xquery/test/schema-type/casts";

import module namespace fx="http://exist-db.org/xquery/test/schema-type/fixtures" at "fixtures.xqm";

declare namespace test="http://exist-db.org/xquery/xqsuite";

declare variable $c:PROLOG := 'import schema namespace t="urn:example:trade"; ';

declare %test:setUp function c:setUp() { fx:setUp() };
declare %test:tearDown function c:tearDown() { fx:tearDown() };

(:~ evaluate an expression in the scope of the imported trade schema :)
declare function c:eval($expr as xs:string) {
    util:eval($c:PROLOG || $expr)
};

declare %test:assertTrue function c:constructor() { c:eval('t:NonNegativeDecimal("5") instance of t:NonNegativeDecimal') };
declare %test:assertTrue function c:constructor-base-instance() { c:eval('t:NonNegativeDecimal("5") instance of xs:decimal') };
declare %test:assertEquals(0) function c:constructor-empty() { c:eval('count(t:NonNegativeDecimal(()))') };
declare %test:assertError("FORG0001") function c:facet-violation() { c:eval('t:NonNegativeDecimal("-1")') };
declare %test:assertError("FORG0001") function c:pattern-violation() { c:eval('"usd" cast as t:CurrencyCode') };
declare %test:assertTrue function c:cast-numeric-source() { c:eval('(5 cast as t:NonNegativeDecimal) instance of t:NonNegativeDecimal') };
declare %test:assertError("FORG0001") function c:cast-numeric-source-violation() { c:eval('-5 cast as t:NonNegativeDecimal') };
declare %test:assertTrue function c:castable() { c:eval('"USD" castable as t:CurrencyCode and not("usd" castable as t:CurrencyCode)') };
declare %test:assertEquals(2) function c:list-cast() { c:eval('count("2026-01-01 2026-02-01" cast as t:DateList)') };
declare %test:assertTrue function c:list-cast-items() { c:eval('every $d in ("2026-01-01 2026-02-01" cast as t:DateList) satisfies $d instance of xs:date') };
declare %test:assertError("XPTY0004") function c:list-cast-non-string() { c:eval('5 cast as t:DecimalList') };
declare %test:assertFalse function c:list-castable-non-string() { c:eval('5 castable as t:DecimalList') };
declare %test:assertTrue function c:list-castable() { c:eval('"1 2.5" castable as t:DecimalList') };
declare %test:assertError("FORG0001") function c:list-bad-item() { c:eval('"1 x" cast as t:DecimalList') };
declare %test:assertTrue function c:union-cast() { c:eval('("P1D" cast as t:DateOrDuration) instance of xs:duration') };
declare %test:assertTrue function c:union-first-member() { c:eval('("2026-01-01" cast as t:DateOrDuration) instance of xs:date') };
declare %test:assertError("FORG0001") function c:union-no-member() { c:eval('"x" cast as t:DateOrDuration') };
declare %test:assertTrue function c:arith-result-decimal() { c:eval('(t:NonNegativeDecimal("5") + 1) instance of xs:decimal') };
declare %test:assertFalse function c:arith-result-not-user() { c:eval('(t:NonNegativeDecimal("5") + 1) instance of t:NonNegativeDecimal') };
declare %test:assertEquals("5") function c:cast-user-to-string() { c:eval('t:NonNegativeDecimal("5") cast as xs:string') };
declare %test:assertError("XQST0052") function c:cast-to-complex() { c:eval('"x" cast as t:Product') };
declare %test:assertError("XQST0052") function c:castable-to-complex() { c:eval('"x" castable as t:Product') };
declare %test:assertError("XPST0017") function c:unknown-constructor() { c:eval('t:NoSuchType("x")') };
declare %test:assertError("XPST0017") function c:complex-constructor() { c:eval('t:Product("x")') };
declare %test:assertError("XPST0017") function c:constructor-arity() { c:eval('t:NonNegativeDecimal("1", "2")') };
declare %test:assertError("XPTY0004") function c:cast-many() { c:eval('(1, 2) cast as t:NonNegativeDecimal') };
