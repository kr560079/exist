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
 : (with typed values for xs:decimal, xs:date and t:NonNegativeDecimal), trade-1.xml, trade-2.xml and bad.xml into
 : /db/schema-type-test, and trade-1.xml alone (no configuration) as /db/plain/t1.xml. It also stores trade-v2.xsd, a
 : second version of the schema (same target namespace, one extra optional element), as trade.xsd together with
 : trade-common.xsd into /db/schema-type-test-v2, which no collection is configured with. fx:tearDown() removes it all.
 : Each test module calls them from its own %test:setUp and %test:tearDown functions.
 :
 : The fixture files are read from exist-core's test resources (the single copy shared with the Java tests), from
 : the directory in the system property schema-type.fixtures.dir which this module's pom sets for surefire.
 :
 : The module also has the helpers of the optimizer tests (in this module's namespace, prefix fx): fx:same($prolog,
 : $expr) and fx:same($query) evaluate a query optimized and unoptimized and compare the items; fx:same-error the
 : error both raise; fx:uses-index($query, $index) whether evaluating it reports use of an index type (system:trace).
 :)
module namespace fx="http://exist-db.org/xquery/test/schema-type/fixtures";

declare namespace prof="http://exist-db.org/xquery/profiling";

declare variable $fx:COLLECTION_NAME := "schema-type-test";
declare variable $fx:COLLECTION := "/db/" || $fx:COLLECTION_NAME;
declare variable $fx:CONFIG_COLLECTION := "/db/system/config/db/" || $fx:COLLECTION_NAME;
declare variable $fx:PLAIN_COLLECTION := "/db/plain";
declare variable $fx:V2_COLLECTION_NAME := "schema-type-test-v2";
declare variable $fx:V2_COLLECTION := "/db/" || $fx:V2_COLLECTION_NAME;

(:~ The directory of the fixture files (an absolute path, set by the pom for surefire). :)
declare variable $fx:FIXTURES as xs:string :=
    let $dir := util:system-property("schema-type.fixtures.dir")
    return
        if (exists($dir)) then $dir
        else error(xs:QName("fx:no-fixtures"), "system property schema-type.fixtures.dir is not set");

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
    let $docs := (
        fx:store-fixture($fx:COLLECTION, "trade-1.xml"),
        fx:store-fixture($fx:COLLECTION, "trade-2.xml"),
        fx:store-fixture($fx:COLLECTION, "bad.xml")
    )
    let $v2 := xmldb:create-collection("/db", $fx:V2_COLLECTION_NAME)
    let $v2-schemas := (
        fx:store-fixture($fx:V2_COLLECTION, "trade-common.xsd"),
        fx:store-fixture($fx:V2_COLLECTION, "trade-v2.xsd"),
        xmldb:rename($fx:V2_COLLECTION, "trade-v2.xsd", "trade.xsd")
    )
    let $plain := xmldb:create-collection("/db", "plain")
    return
        xmldb:store($fx:PLAIN_COLLECTION, "t1.xml", doc($fx:COLLECTION || "/trade-1.xml"))
};

declare function fx:tearDown() {
    for $collection in ($fx:COLLECTION, $fx:PLAIN_COLLECTION, $fx:CONFIG_COLLECTION, $fx:V2_COLLECTION)
    where xmldb:collection-available($collection)
    return
        xmldb:remove($collection)
};

(: --- differential optimizer helpers (shared by the optimizer test modules) ---
 : The functions are in this module's namespace, http://exist-db.org/xquery/test/schema-type/fixtures (prefix fx by
 : convention). A query is evaluated with util:eval, once as compiled (optimized) and once with the optimizer disabled
 : by `declare option exist:optimize "enable=no";`, which goes after the prolog's imports.
 :)

(:~ evaluate a query; a dynamic error gives the string "error:" followed by its code :)
declare %private function fx:run($query as xs:string) {
    try {
        util:eval($query)
    } catch * {
        "error:" || string($err:code)
    }
};

declare %private function fx:identical($a as item()*, $b as item()*) as xs:boolean {
    count($a) eq count($b)
    and deep-equal($a, $b)
    and (every $i in 1 to count($a) satisfies
            (if ($a[$i] instance of node() and $b[$i] instance of node()) then $a[$i] is $b[$i] else true()))
};

declare %private function fx:is-error($result as item()*) as xs:boolean {
    count($result) eq 1 and $result instance of xs:string and starts-with($result, "error:")
};

(:~
 : true if a query gives the same items (the same nodes, in the same order) optimized and unoptimized, and does not
 : raise an error
 :
 : @param $prolog the prolog of the query: imports and declarations
 : @param $expr   the query body
 :)
declare function fx:same($prolog as xs:string, $expr as xs:string) as xs:boolean {
    let $optimized := fx:run($prolog || $expr)
    let $unoptimized := fx:run($prolog || ' declare option exist:optimize "enable=no"; ' || $expr)
    return
        not(fx:is-error($optimized)) and fx:identical($optimized, $unoptimized)
};

(:~ as fx:same#2, for a query without prolog :)
declare function fx:same($query as xs:string) as xs:boolean {
    fx:same('', $query)
};

(:~ the code of the error a query raises both optimized and unoptimized, or "different" / "no error" :)
declare function fx:same-error($prolog as xs:string, $expr as xs:string) as xs:string {
    let $optimized := fx:run($prolog || $expr)
    let $unoptimized := fx:run($prolog || ' declare option exist:optimize "enable=no"; ' || $expr)
    return
        if (not(fx:is-error($optimized))) then "no error"
        else if ($optimized ne $unoptimized) then "different"
        else substring-after($optimized, "error:")
};

(:~
 : true if evaluating a query reports use of an index of the type (as in the type attribute of the index elements
 : of system:trace(), e.g. "schema-type" or "range")
 :)
declare function fx:uses-index($query as xs:string, $index as xs:string) as xs:boolean {
    let $cleared := system:clear-trace()
    let $enabled := if (empty($cleared)) then system:enable-tracing(true(), false()) else ()
    let $count := if (empty($enabled)) then count(fx:run($query)) else -1
    let $trace := if ($count ge 0) then system:trace() else ()
    let $disabled := if (exists($trace)) then system:enable-tracing(false()) else ()
    return
        empty($disabled) and exists($trace//prof:index[@type = $index])
};
