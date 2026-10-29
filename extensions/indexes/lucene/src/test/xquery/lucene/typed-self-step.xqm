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
 : ft:query preloads the step it searches: a typed step (element(*, T), self::element(*, T)) keeps its type filter.
 : Without a schema type index every stored element is annotated xs:untyped, so element(*, xs:decimal) matches none
 : and element(*, xs:untyped) every one.
 :)
module namespace ts="http://exist-db.org/xquery/lucene/test/typed-self-step";

declare namespace test="http://exist-db.org/xquery/xqsuite";

import module namespace ft="http://exist-db.org/xquery/lucene";

declare variable $ts:COLLECTION_NAME := "lucene-typed-self-step-test";
declare variable $ts:COLLECTION := "/db/" || $ts:COLLECTION_NAME;
declare variable $ts:CONFIG_COLLECTION := "/db/system/config/db/" || $ts:COLLECTION_NAME;

declare variable $ts:XCONF :=
    <collection xmlns="http://exist-db.org/collection-config/1.0">
        <index>
            <lucene>
                <text qname="title"/>
            </lucene>
        </index>
    </collection>;

declare variable $ts:DATA :=
    <items>
        <item><title>apple pie</title></item>
        <item><title>pear tart</title></item>
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

declare %test:assertEquals(0) function ts:self-typed-no-match() {
    count(collection($ts:COLLECTION)//title[ft:query(self::element(*, xs:decimal), "apple")])
};

declare %test:assertEquals(1) function ts:self-typed-match() {
    count(collection($ts:COLLECTION)//title[ft:query(self::element(*, xs:untyped), "apple")])
};

declare %test:assertEquals(0) function ts:child-typed-no-match() {
    count(collection($ts:COLLECTION)//item[ft:query(element(title, xs:decimal), "apple")])
};

declare %test:assertEquals(1) function ts:child-typed-match() {
    count(collection($ts:COLLECTION)//item[ft:query(element(title, xs:untyped), "apple")])
};

declare %test:assertEquals(0) function ts:child-typed-wildcard-no-match() {
    count(collection($ts:COLLECTION)//item[ft:query(element(*, xs:decimal), "apple")])
};

declare %test:assertEquals(1) function ts:child-typed-wildcard-match() {
    count(collection($ts:COLLECTION)//item[ft:query(element(*, xs:untyped), "apple")])
};
