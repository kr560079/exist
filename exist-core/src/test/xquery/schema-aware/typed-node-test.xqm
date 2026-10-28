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
 : element(N, T) and attribute(N, T) must test the type annotation of the node:
 : without a schema, elements are xs:untyped and attributes xs:untypedAtomic.
 :)
module namespace tnt="http://exist-db.org/xquery/test/schema-aware/typed-node-test";

declare namespace test="http://exist-db.org/xquery/xqsuite";

declare variable $tnt:COLLECTION_NAME := "typed-node-test";
declare variable $tnt:COLLECTION := "/db/" || $tnt:COLLECTION_NAME;

declare %test:setUp function tnt:setup() {
    xmldb:create-collection("/db", $tnt:COLLECTION_NAME),
    xmldb:store($tnt:COLLECTION, "test.xml", <r x="1"><a/><b/></r>)
};

declare %test:tearDown function tnt:tearDown() {
    xmldb:remove($tnt:COLLECTION)
};

declare %test:assertFalse function tnt:untyped-is-not-integer() { <a>1</a> instance of element(*, xs:integer) };
declare %test:assertTrue function tnt:untyped-is-anyType() { <a>1</a> instance of element(*, xs:anyType) };
declare %test:assertTrue function tnt:untyped-is-untyped() { <a>1</a> instance of element(a, xs:untyped) };
declare %test:assertFalse function tnt:named-wrong-name() { <a>1</a> instance of element(b, xs:untyped) };
declare %test:assertFalse function tnt:named-wrong-type() { <a>1</a> instance of element(a, xs:integer) };
declare %test:assertTrue function tnt:attr-instance-of() { <r x="1"/>/@x instance of attribute(x, xs:untypedAtomic) };
declare %test:assertFalse function tnt:attr-not-integer() { <r x="1"/>/@x instance of attribute(*, xs:integer) };
declare %test:assertEquals(2) function tnt:step-anyType() { count(<r><a/><b/></r>/element(*, xs:anyType)) };
declare %test:assertEquals(0) function tnt:step-integer() { count(<r><a/><b/></r>/element(*, xs:integer)) };
declare %test:assertEquals(1) function tnt:step-named-untyped() { count(<r><a/><b/></r>/element(a, xs:untyped)) };
declare %test:assertEquals(0) function tnt:step-named-integer() { count(<r><a/><b/></r>/element(a, xs:integer)) };
declare %test:assertEquals(1) function tnt:attr-untypedAtomic() { count(<r x="1"/>/attribute(x, xs:untypedAtomic)) };
declare %test:assertEquals(0) function tnt:attr-integer() { count(<r x="1"/>/attribute(x, xs:integer)) };

declare %test:assertEquals(2) function tnt:persistent-step-anyType() { count(doc($tnt:COLLECTION || "/test.xml")/r/element(*, xs:anyType)) };
declare %test:assertEquals(0) function tnt:persistent-step-integer() { count(doc($tnt:COLLECTION || "/test.xml")/r/element(*, xs:integer)) };
declare %test:assertEquals(1) function tnt:persistent-step-named() { count(doc($tnt:COLLECTION || "/test.xml")/r/element(a, xs:untyped)) };
declare %test:assertEquals(1) function tnt:persistent-attr() { count(doc($tnt:COLLECTION || "/test.xml")/r/attribute(x, xs:untypedAtomic)) };
declare %test:assertEquals(0) function tnt:persistent-attr-integer() { count(doc($tnt:COLLECTION || "/test.xml")/r/attribute(x, xs:integer)) };
declare %test:assertFalse function tnt:persistent-instance-of() { doc($tnt:COLLECTION || "/test.xml")/r/a instance of element(*, xs:integer) };
declare %test:assertTrue function tnt:persistent-instance-of-untyped() { doc($tnt:COLLECTION || "/test.xml")/r/a instance of element(a, xs:untyped) };
