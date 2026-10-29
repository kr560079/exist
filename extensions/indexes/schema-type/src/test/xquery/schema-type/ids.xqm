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
 : fn:id, fn:element-with-id and fn:idref find nodes whose schema type derives from xs:ID, xs:IDREF or xs:IDREFS in
 : stored documents of collections configured with the schema type index, besides the DTD and xml:id ones.
 :)
module namespace d="http://exist-db.org/xquery/test/schema-type/ids";

import module namespace fx="http://exist-db.org/xquery/test/schema-type/fixtures" at "fixtures.xqm";

declare namespace test="http://exist-db.org/xquery/xqsuite";
declare namespace t="urn:example:trade";

declare variable $d:doc := doc('/db/schema-type-test/trade-1.xml');
declare variable $d:ids := doc('/db/schema-type-test/ids-1.xml');
declare variable $d:plain := doc('/db/plain/t1.xml');
declare variable $d:LATE := "/db/schema-type-late";
declare variable $d:LATE_CONFIG := "/db/system/config/db/schema-type-late";

declare variable $d:IDS :=
    <t:trades>
        <t:trade>
            <t:swap/>
            <t:party id="p1"/>
            <t:party id="p2"/>
            <t:partyReference href="p1"/>
            <t:idElem>e1</t:idElem>
            <t:refElem>p2</t:refElem>
            <t:refs>p1 p2</t:refs>
            <t:multiRef hrefs="p2 e1"/>
        </t:trade>
        <t:trade>
            <t:swap/>
            <t:party id="p3"/>
            <t:partyReference href="p3"/>
        </t:trade>
    </t:trades>;

declare %test:setUp function d:setUp() {
    fx:setUp(),
    xmldb:store($fx:COLLECTION, "ids-1.xml", $d:IDS),
    xmldb:store($fx:PLAIN_COLLECTION, "xmlid.xml", <a xml:id="x1"><b xml:id="x2"/></a>),
    (: a document stored before the collection was configured is stale :)
    xmldb:create-collection("/db", "schema-type-late"),
    xmldb:store($d:LATE, "t1.xml", doc($fx:COLLECTION || "/trade-1.xml")),
    xmldb:create-collection("/db/system/config/db", "schema-type-late"),
    xmldb:store($d:LATE_CONFIG, "collection.xconf", $fx:XCONF)
};

declare %test:tearDown function d:tearDown() {
    for $c in ($d:LATE, $d:LATE_CONFIG)
    where xmldb:collection-available($c)
    return xmldb:remove($c),
    fx:tearDown()
};

declare %test:assertEquals("party1") function d:id-from-schema() { string(id("party1", $d:doc)/@id) };
declare %test:assertEquals(1) function d:href-to-party() { count(id($d:doc//t:partyReference/@href, $d:doc)) };
declare %test:assertEquals(1) function d:idref() { count(idref("party1", $d:doc)) };
declare %test:assertEquals("t:party") function d:element-with-id() { name(element-with-id("party1", $d:doc)) };

(: beyond the brief :)
declare %test:assertEquals("href") function d:idref-is-the-attribute() { name(idref("party1", $d:doc)) };
declare %test:assertEmpty function d:id-no-match() { id("nope", $d:doc) };
declare %test:assertEquals("p1", "p2") function d:id-two-attributes() { id(("p2", "p1"), $d:ids)/@id/string() };
declare %test:assertEquals("p1", "p2") function d:id-idrefs-string() { id("p2 p1", $d:ids)/@id/string() };
declare %test:assertEquals(1) function d:duplicates-removed() { count(id(("p1", "p1", "p1"), $d:ids)) };
declare %test:assertTrue function d:document-order() {
    let $r := id(("p3", "p2", "p1"), $d:ids)
    return deep-equal($r/@id/string(), ("p1", "p2", "p3")) and $r[1] << $r[2] and $r[2] << $r[3]
};
declare %test:assertEquals("t:idElem") function d:id-returns-id-typed-element() { name(id("e1", $d:ids)) };
declare %test:assertEquals("t:trade") function d:element-with-id-returns-parent-of-id-element() { name(element-with-id("e1", $d:ids)) };
declare %test:assertEquals("t:party") function d:element-with-id-attribute-owner() { name(element-with-id("p2", $d:ids)) };
declare %test:assertEquals("t:party") function d:id-attribute-owner() { name(id("p2", $d:ids)) };
declare %test:assertEquals(2) function d:element-with-id-two-parties() { count(element-with-id(("p1", "p2"), $d:ids)) };
(: p1: the partyReference/@href and the IDREFS element t:refs :)
declare %test:assertEquals("href", "t:refs") function d:idref-idrefs-token() { idref("p1", $d:ids)/name() };
(: p2: t:refElem (an IDREF element), t:refs, and the IDREFS attribute hrefs (second token) :)
declare %test:assertEquals("t:refElem", "t:refs", "hrefs") function d:idref-element-and-idrefs-attribute() { idref("p2", $d:ids)/name() };
declare %test:assertEquals("hrefs") function d:idref-idrefs-attribute-second-token() { idref("e1", $d:ids)/name() };
declare %test:assertEquals("href", "t:refElem", "t:refs") function d:idref-two-ids-document-order() { idref(("p2", "p1"), $d:ids)[. << $d:ids//t:multiRef]/name() };
declare %test:assertEquals(1) function d:idref-duplicates() { count(idref(("p3", "p3"), $d:ids)) };
declare %test:assertEquals("t:partyReference") function d:idref-owner-of-attribute() { name(idref("p3", $d:ids)/..) };

(: unconfigured collections: today's behaviour :)
declare %test:assertEmpty function d:plain-id-ignores-schema() { id("party1", $d:plain) };
declare %test:assertEmpty function d:plain-idref-ignores-schema() { idref("party1", $d:plain) };
declare %test:assertEmpty function d:plain-element-with-id() { element-with-id("party1", $d:plain) };
declare %test:assertEquals("a") function d:plain-xml-id() { name(id("x1", doc('/db/plain/xmlid.xml'))) };
declare %test:assertEquals("b") function d:plain-xml-id-nested() { name(element-with-id("x2", doc('/db/plain/xmlid.xml'))) };

(: a document of a configured collection without index entries is stale: EXSA0003 propagates :)
declare %test:assertError("EXSA0003") function d:stale-id() { id("party1", doc($d:LATE || "/t1.xml")) };
declare %test:assertError("EXSA0003") function d:stale-idref() { idref("party1", doc($d:LATE || "/t1.xml")) };
declare %test:assertError("EXSA0003") function d:stale-element-with-id() { element-with-id("party1", doc($d:LATE || "/t1.xml")) };
