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
 : Shared test data of the schema-type XQSuite modules.
 :
 : fx:setUp() stores trade.xsd and trade-common.xsd, a collection.xconf configuring the schema type index
 : (with typed values for xs:decimal, xs:date and t:NonNegativeDecimal), trade-1.xml and bad.xml into
 : /db/schema-type-test, and trade-1.xml alone (no configuration) as /db/plain/t1.xml. fx:tearDown() removes it all.
 : Each test module calls them from its own %test:setUp and %test:tearDown functions.
 :
 : The fixture files are read from exist-core's test resources (the single copy shared with the Java tests),
 : relative to this module's directory, which is the working directory of the test JVM.
 :)
module namespace fx="http://exist-db.org/xquery/test/schema-type/fixtures";

declare variable $fx:COLLECTION_NAME := "schema-type-test";
declare variable $fx:COLLECTION := "/db/" || $fx:COLLECTION_NAME;
declare variable $fx:CONFIG_COLLECTION := "/db/system/config/db/" || $fx:COLLECTION_NAME;
declare variable $fx:PLAIN_COLLECTION := "/db/plain";

(:~ The directory of the fixture files, relative to the working directory of the test JVM. :)
declare variable $fx:FIXTURES := "../../../exist-core/src/test/resources/org/exist/xquery/schema/fixtures";

declare variable $fx:XCONF :=
    <collection xmlns="http://exist-db.org/collection-config/1.0">
        <index xmlns:t="urn:example:trade" xmlns:xs="http://www.w3.org/2001/XMLSchema">
            <schema-type href="xmldb:exist://{$fx:COLLECTION}/trade.xsd"/>
            <typed-values>
                <type name="xs:decimal"/>
                <type name="xs:date"/>
                <type name="t:NonNegativeDecimal"/>
            </typed-values>
        </index>
    </collection>;

(:~ Store a fixture file into a collection. :)
declare function fx:store-fixture($collection as xs:string, $name as xs:string) as xs:string* {
    xmldb:store-files-from-pattern($collection, $fx:FIXTURES, $name, "application/xml")
};

declare function fx:setUp() {
    (: the schema first, then the configuration (which compiles it), then the documents :)
    let $collection := xmldb:create-collection("/db", $fx:COLLECTION_NAME)
    let $schemas := (fx:store-fixture($fx:COLLECTION, "trade-common.xsd"), fx:store-fixture($fx:COLLECTION, "trade.xsd"))
    let $config-collection := xmldb:create-collection("/db/system/config/db", $fx:COLLECTION_NAME)
    let $config := xmldb:store($fx:CONFIG_COLLECTION, "collection.xconf", $fx:XCONF)
    let $docs := (fx:store-fixture($fx:COLLECTION, "trade-1.xml"), fx:store-fixture($fx:COLLECTION, "bad.xml"))
    let $plain := xmldb:create-collection("/db", "plain")
    return
        xmldb:store($fx:PLAIN_COLLECTION, "t1.xml", doc($fx:COLLECTION || "/trade-1.xml"))
};

declare function fx:tearDown() {
    for $collection in ($fx:COLLECTION, $fx:PLAIN_COLLECTION, $fx:CONFIG_COLLECTION)
    where xmldb:collection-available($collection)
    return
        xmldb:remove($collection)
};
