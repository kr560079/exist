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
 : A typed self step (self::element(*, T)) is not transparent for the range index: rewriting the comparison it is
 : part of into a range lookup must not drop its type filter. Without a schema type index every stored element is
 : annotated xs:untyped, so self::element(*, xs:decimal) matches none and self::element(*, xs:untyped) every one.
 :)
module namespace ts="http://exist-db.org/xquery/range/test/typed-self-step";

declare namespace test="http://exist-db.org/xquery/xqsuite";

declare variable $ts:COLLECTION_NAME := "typed-self-step-test";
declare variable $ts:COLLECTION := "/db/" || $ts:COLLECTION_NAME;
declare variable $ts:CONFIG_COLLECTION := "/db/system/config/db/" || $ts:COLLECTION_NAME;

declare variable $ts:XCONF :=
    <collection xmlns="http://exist-db.org/collection-config/1.0">
        <index xmlns:xs="http://www.w3.org/2001/XMLSchema">
            <range>
                <create qname="amount" type="xs:decimal"/>
            </range>
        </index>
    </collection>;

declare variable $ts:DATA :=
    <items>
        <item><amount>5</amount></item>
        <item><amount>7</amount></item>
    </items>;

declare %test:setUp function ts:setUp() {
    xmldb:create-collection("/db/system/config/db", $ts:COLLECTION_NAME),
    xmldb:store($ts:CONFIG_COLLECTION, "collection.xconf", $ts:XCONF),
    xmldb:create-collection("/db", $ts:COLLECTION_NAME),
    xmldb:store($ts:COLLECTION, "items.xml", $ts:DATA)
};

declare %test:tearDown function ts:tearDown() {
    xmldb:remove($ts:COLLECTION),
    xmldb:remove($ts:CONFIG_COLLECTION)
};

declare %test:assertEquals(0) function ts:self-typed-predicate-no-match() {
    count(collection($ts:COLLECTION)//amount[self::element(*, xs:decimal) = 5])
};

declare %test:assertEquals(1) function ts:self-typed-predicate-match() {
    count(collection($ts:COLLECTION)//amount[self::element(*, xs:untyped) = 5])
};

declare %test:assertEquals(0) function ts:self-typed-in-path-no-match() {
    count(collection($ts:COLLECTION)//item[amount/self::element(*, xs:decimal) = 5])
};

declare %test:assertEquals(1) function ts:self-typed-in-path-match() {
    count(collection($ts:COLLECTION)//item[amount/self::element(*, xs:untyped) = 5])
};

declare %test:assertEquals(0) function ts:child-typed-no-match() {
    count(collection($ts:COLLECTION)//item[element(amount, xs:decimal) = 5])
};

declare %test:assertEquals(1) function ts:child-typed-match() {
    count(collection($ts:COLLECTION)//item[element(amount, xs:untyped) = 5])
};

declare %test:assertEquals(0) function ts:child-typed-wildcard-no-match() {
    count(collection($ts:COLLECTION)//item[element(*, xs:decimal) = 5])
};

declare %test:assertEquals(1) function ts:child-typed-wildcard-match() {
    count(collection($ts:COLLECTION)//item[element(*, xs:untyped) = 5])
};
