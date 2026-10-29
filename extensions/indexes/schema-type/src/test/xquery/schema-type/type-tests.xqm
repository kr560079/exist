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
 : element(N, T), attribute(N, T), schema-element(N) and schema-attribute(N) match stored nodes on their type
 : annotations (XQuery 3.1 §2.5.5.3–2.5.5.6).
 :
 : The tests name schema types and declarations, which needs an import schema of the trade namespace. This module
 : is compiled before fx:setUp() stores the schema and the collection configuration an import without a hint
 : resolves from, so the tests evaluate their expressions with util:eval, after a prolog importing the schema.
 :)
module namespace y="http://exist-db.org/xquery/test/schema-type/type-tests";

import module namespace fx="http://exist-db.org/xquery/test/schema-type/fixtures" at "fixtures.xqm";

declare namespace test="http://exist-db.org/xquery/xqsuite";
declare namespace t="urn:example:trade";

declare variable $y:doc := doc('/db/schema-type-test/trade-1.xml');

declare variable $y:PROLOG := '
    import schema namespace t="urn:example:trade";
    declare variable $y:doc := doc("/db/schema-type-test/trade-1.xml");
';

(: a party with the globally declared attribute t:role, and a document of a substitution member blocked by its head :)
declare variable $y:ROLES := <t:trades><t:trade><t:swap/><t:party id="p1" t:role="buyer"/><t:party id="p2"/></t:trade></t:trades>;
declare variable $y:BLOCKED := <t:blockedSwapMember/>;

declare %test:setUp function y:setUp() {
    fx:setUp(),
    xmldb:store($fx:COLLECTION, "roles.xml", $y:ROLES),
    xmldb:store($fx:COLLECTION, "blocked.xml", $y:BLOCKED)
};

declare %test:tearDown function y:tearDown() { fx:tearDown() };

(:~ evaluate an expression in the scope of the imported trade schema :)
declare function y:eval($expr as xs:string) {
    util:eval('declare namespace y="http://exist-db.org/xquery/test/schema-type/type-tests";' || $y:PROLOG || $expr)
};

(: --- element(N, T) and element(*, T) --- :)

declare %test:assertEquals(3) function y:element-star-abstract-base() { y:eval('count($y:doc//element(*, t:Product))') };
declare %test:assertEquals(1) function y:element-name-type() { y:eval('count($y:doc//element(t:vanillaSwap, t:Swap))') };
declare %test:assertEquals(0) function y:element-wrong-type() { y:eval('count($y:doc//element(t:fra, t:Swap))') };
declare %test:assertEquals(0) function y:nilled-excluded-without-q() { y:eval('count($y:doc//element(t:tradeId, xs:string))') };
declare %test:assertEquals(1) function y:nilled-included-with-q() { y:eval('count($y:doc//element(t:tradeId, xs:string?))') };
declare %test:assertEquals(1) function y:nilled-included-with-q-wildcard() {
    (: tradeId is the only nilled one of the xs:string-derived elements :)
    y:eval('count($y:doc//element(*, xs:string?)) - count($y:doc//element(*, xs:string))')
};
declare %test:assertTrue function y:nillable-instance-of() { y:eval('$y:doc//t:tradeId instance of element(t:tradeId, xs:string?)') };
declare %test:assertFalse function y:nilled-instance-of-without-q() { y:eval('$y:doc//t:tradeId instance of element(t:tradeId, xs:string)') };
declare %test:assertEquals(0) function y:unassignable-not-matched() { y:eval('count(doc("/db/schema-type-test/bad.xml")//element(t:bogus, t:Product))') };

(: --- schema-element(N) --- :)

declare %test:assertEquals(3) function y:schema-element-substitution() { y:eval('count($y:doc//schema-element(t:product))') };
declare %test:assertEquals(2) function y:schema-element-transitive-member() { y:eval('count($y:doc//schema-element(t:swap))') };
declare %test:assertEquals(1) function y:schema-element-no-members() { y:eval('count($y:doc//schema-element(t:fra))') };
declare %test:assertEquals(1) function y:schema-element-anonymous-type() { y:eval('count($y:doc/schema-element(t:trades))') };
declare %test:assertEquals(3) function y:schema-element-anonymous-child() { y:eval('count($y:doc/t:trades/schema-element(t:trade))') };
declare %test:assertEquals(2) function y:schema-element-child-axis() { y:eval('count($y:doc/t:trades/t:trade/schema-element(t:swap))') };
declare %test:assertEquals(2) function y:element-type-child-axis() { y:eval('count($y:doc/t:trades/t:trade/element(*, t:Swap))') };
declare %test:assertEquals(3) function y:schema-element-predicate() { y:eval('count($y:doc//t:trade[schema-element(t:product)])') };
declare %test:assertEquals(0) function y:schema-element-blocked-member() {
    (: blockedSwapMember is in the substitution group of blockedSwap, which blocks substitution :)
    y:eval('count(doc("/db/schema-type-test/blocked.xml")/schema-element(t:blockedSwap))')
};
declare %test:assertEquals(1) function y:blocked-member-typed() { y:eval('count(doc("/db/schema-type-test/blocked.xml")/element(*, t:Swap))') };
declare %test:assertEquals(1) function y:schema-element-self() { y:eval('count(doc("/db/schema-type-test/blocked.xml")/schema-element(t:blockedSwapMember))') };
declare %test:assertTrue function y:schema-element-instance-of() { y:eval('$y:doc//t:vanillaSwap instance of schema-element(t:product)') };
declare %test:assertFalse function y:schema-element-instance-of-other() { y:eval('$y:doc//t:trade[1] instance of schema-element(t:product)') };
declare %test:assertTrue function y:function-param-type() {
    y:eval('declare function local:f($p as schema-element(t:product)) { true() }; local:f($y:doc//t:swap[1])')
};
declare %test:assertError("XPTY0004") function y:function-param-type-mismatch() {
    y:eval('declare function local:f($p as schema-element(t:product)) { true() }; local:f($y:doc//t:trade[1])')
};
declare %test:assertError("XPTY0004") function y:function-param-element-type-mismatch() {
    y:eval('declare function local:f($p as element(*, t:Swap)) { true() }; local:f($y:doc//t:fra)')
};
declare %test:assertError("XPTY0004") function y:function-return-type-mismatch() {
    y:eval('declare function local:g() as schema-element(t:swap) { $y:doc//t:fra }; local:g()')
};
declare %test:assertEquals(1) function y:function-return-type() {
    y:eval('declare function local:g() as schema-element(t:swap)* { $y:doc//t:vanillaSwap }; count(local:g())')
};
declare %test:assertError("XPTY0004") function y:let-type-mismatch() {
    y:eval('let $x as schema-element(t:swap) := $y:doc//t:fra return 1')
};
declare %test:assertEquals(1) function y:typeswitch-schema-element() {
    y:eval('count(for $p in $y:doc//t:trade/* return typeswitch ($p) case schema-element(t:swap) return () case schema-element(t:product) return $p default return ())')
};
declare %test:assertTrue function y:document-node-schema-element() { y:eval('$y:doc instance of document-node(schema-element(t:trades))') };
declare %test:assertFalse function y:document-node-schema-element-other() { y:eval('$y:doc instance of document-node(schema-element(t:trade))') };
declare %test:assertEquals(1) function y:document-node-schema-element-step() { y:eval('count($y:doc/self::document-node(schema-element(t:trades)))') };
declare %test:assertEquals(0) function y:document-node-schema-element-step-other() { y:eval('count($y:doc/self::document-node(schema-element(t:trade)))') };
declare %test:assertEquals(0) function y:document-node-element-step-other() { y:eval('count($y:doc/self::document-node(element(t:trade)))') };
declare %test:assertEquals(1) function y:document-node-element-type-step() { y:eval('count(doc("/db/schema-type-test/blocked.xml")/self::document-node(element(*, t:Swap)))') };
declare %test:assertEquals(0) function y:document-node-element-type-step-other() { y:eval('count($y:doc/self::document-node(element(*, t:Swap)))') };
declare %test:assertEquals(1, 0, 1, 0) function y:document-node-parent-axis() {
    y:eval('count($y:doc/t:trades/parent::document-node(element(t:trades)))'),
    y:eval('count($y:doc/t:trades/parent::document-node(element(t:trade)))'),
    y:eval('count($y:doc//t:swap/ancestor::document-node(schema-element(t:trades)))'),
    y:eval('count($y:doc//t:swap/ancestor::document-node(schema-element(t:trade)))')
};
declare %test:assertTrue function y:document-node-element-type() { y:eval('doc("/db/schema-type-test/blocked.xml") instance of document-node(element(*, t:Swap))') };

(: --- attribute(N, T) and schema-attribute(N) --- :)

declare %test:assertTrue function y:schema-attribute() { y:eval('exists($y:doc//t:party/@id[. instance of attribute(*, xs:ID)])') };
declare %test:assertEquals(1) function y:schema-attribute-global() { y:eval('count(doc("/db/schema-type-test/roles.xml")//t:party/@schema-attribute(t:role))') };
declare %test:assertEquals(1) function y:schema-attribute-step() { y:eval('count(doc("/db/schema-type-test/roles.xml")//schema-attribute(t:role))') };
declare %test:assertEquals(1) function y:schema-attribute-axis() { y:eval('count(doc("/db/schema-type-test/roles.xml")//t:party/attribute::schema-attribute(t:role))') };
declare %test:assertTrue function y:schema-attribute-instance-of() {
    y:eval('doc("/db/schema-type-test/roles.xml")//@t:role instance of schema-attribute(t:role)')
};
declare %test:assertFalse function y:schema-attribute-other-attribute() {
    y:eval('doc("/db/schema-type-test/roles.xml")//t:party[1]/@id instance of schema-attribute(t:role)')
};
declare %test:assertEquals(0) function y:schema-element-on-attribute-axis() {
    y:eval('count(doc("/db/schema-type-test/roles.xml")//t:party/@schema-element(t:product))')
};
declare %test:assertError("XPST0003") function y:attribute-test-no-nillable() { y:eval('$y:doc//@id instance of attribute(*, xs:ID?)') };

(: --- unannotated nodes --- :)

declare %test:assertFalse function y:in-memory-not-schema-element() {
    y:eval('<t:swap xmlns:t="urn:example:trade"/> instance of schema-element(t:swap)')
};
declare %test:assertFalse function y:in-memory-not-user-type() {
    y:eval('<t:swap xmlns:t="urn:example:trade"/> instance of element(*, t:Swap)')
};
declare %test:assertTrue function y:in-memory-untyped() {
    y:eval('<t:swap xmlns:t="urn:example:trade"/> instance of element(*, xs:untyped)')
};
declare %test:assertEquals(0) function y:unconfigured-not-schema-element() {
    y:eval('count(doc("/db/plain/t1.xml")//schema-element(t:product))')
};

(: --- static errors --- :)

declare %test:assertError("XPST0008") function y:undeclared-schema-element() { util:eval('import schema namespace t="urn:example:trade"; //schema-element(t:nope)') };
declare %test:assertError("XPST0008") function y:undeclared-schema-attribute() { util:eval('import schema namespace t="urn:example:trade"; //schema-attribute(t:nope)') };
declare %test:assertError("XPST0008") function y:undeclared-schema-element-sequence-type() {
    util:eval('import schema namespace t="urn:example:trade"; 1 instance of schema-element(t:nope)')
};
declare %test:assertError("XPST0008") function y:schema-element-not-imported() { util:eval('declare namespace t="urn:example:trade"; //schema-element(t:product)') };
