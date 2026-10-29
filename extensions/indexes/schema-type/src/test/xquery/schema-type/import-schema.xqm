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
 : import schema puts the types and global declarations of a schema into the static context, so that queries can
 : name them; schema documents are loaded through the schema registry, with its access rules.
 :)
module namespace i="http://exist-db.org/xquery/test/schema-type/import-schema";

import module namespace fx="http://exist-db.org/xquery/test/schema-type/fixtures" at "fixtures.xqm";

declare namespace test="http://exist-db.org/xquery/xqsuite";
declare namespace t="urn:example:trade";

declare variable $i:doc := doc('/db/schema-type-test/trade-1.xml');

declare variable $i:PRIVATE := "/db/schema-type-test-private";
declare variable $i:LIB := "/db/schema-type-test-lib";

declare variable $i:LIB_MODULE := '
module namespace l="urn:test:schema-type:lib";
import schema namespace t="urn:example:trade" at "xmldb:exist:///db/schema-type-test/trade.xsd";
declare function l:swaps($d as node()) as xs:integer { count($d//element(*, t:Swap)) };
';

declare %test:setUp function i:setUp() {
    fx:setUp(),
    (: a copy of the schema only its owner (the admin) may read :)
    let $private := xmldb:create-collection("/db", "schema-type-test-private")
    for $name in ("trade-common.xsd", "trade.xsd")
    let $stored := fx:store-fixture($i:PRIVATE, $name)
    return
        sm:chmod(xs:anyURI($i:PRIVATE || "/" || $name), "rw-------"),
    (: a library module importing the schema :)
    let $lib := xmldb:create-collection("/db", "schema-type-test-lib")
    return
        xmldb:store($i:LIB, "lib.xqm", $i:LIB_MODULE, "application/xquery")
};

declare %test:tearDown function i:tearDown() {
    fx:tearDown(),
    for $collection in ($i:PRIVATE, $i:LIB)
    where xmldb:collection-available($collection)
    return
        xmldb:remove($collection)
};

(: --- importing --- :)

declare %test:assertTrue function i:import-and-name-type() {
    util:eval('import schema namespace t="urn:example:trade" at "xmldb:exist:///db/schema-type-test/trade.xsd"; doc("/db/schema-type-test/trade-1.xml")//t:notional instance of element(*)')
};

declare %test:assertEquals(2) function i:imported-type-in-element-test() {
    util:eval('import schema namespace t="urn:example:trade" at "xmldb:exist:///db/schema-type-test/trade.xsd"; count(doc("/db/schema-type-test/trade-1.xml")//element(*, t:Swap))')
};

declare %test:assertTrue function i:imported-atomic-type-in-instance-of() {
    util:eval('import schema namespace t="urn:example:trade" at "xmldb:exist:///db/schema-type-test/trade.xsd"; data(doc("/db/schema-type-test/trade-1.xml")//t:notional/t:amount) instance of t:NonNegativeDecimal')
};

declare %test:assertTrue function i:imported-type-in-function-signature() {
    util:eval('import schema namespace t="urn:example:trade" at "xmldb:exist:///db/schema-type-test/trade.xsd";
        declare function local:f($a as t:NonNegativeDecimal) as t:NonNegativeDecimal { $a };
        local:f(data(doc("/db/schema-type-test/trade-1.xml")//t:notional/t:amount)) instance of t:NonNegativeDecimal')
};

declare %test:assertTrue function i:import-without-hint-uses-configured() {
    util:eval('import schema namespace t="urn:example:trade"; true()')
};

declare %test:assertEquals(2) function i:import-without-hint-prefers-configured-version() {
    (: once a second version of the namespace was imported, an import without a hint still finds the configured one :)
    let $v2 := util:eval('import schema namespace t="urn:example:trade" at "xmldb:exist:///db/schema-type-test-v2/trade.xsd"; 1')
    return
        if ($v2 eq 1) then
            util:eval('import schema namespace t="urn:example:trade"; count(doc("/db/schema-type-test/trade-1.xml")//element(*, t:Swap))')
        else
            -1
};

declare %test:assertEquals(1) function i:default-element-namespace() {
    util:eval('import schema default element namespace "urn:example:trade" at "xmldb:exist:///db/schema-type-test/trade.xsd"; count(doc("/db/schema-type-test/trade-1.xml")/trades)')
};

declare %test:assertEquals(2) function i:first-resolvable-hint() {
    util:eval('import schema namespace t="urn:example:trade" at "xmldb:exist:///db/no-such-schema.xsd", "xmldb:exist:///db/schema-type-test/trade.xsd"; count(doc("/db/schema-type-test/trade-1.xml")//element(*, t:Swap))')
};

(: --- static errors --- :)

declare %test:assertError("XQST0059") function i:unknown-namespace() {
    util:eval('import schema namespace x="urn:nope"; 1')
};

declare %test:assertError("XQST0059") function i:unresolvable-hint() {
    util:eval('import schema namespace t="urn:example:trade" at "xmldb:exist:///db/no-such-schema.xsd"; 1')
};

declare %test:assertError("XQST0059") function i:remote-hint-refused() {
    (: the server does not allow remote schema access: no fetch is attempted :)
    util:eval('import schema namespace t="urn:example:trade" at "http://localhost:1/trade.xsd"; 1')
};

declare %test:assertError("XQST0059") function i:file-hint-refused() {
    util:eval('import schema namespace t="urn:example:trade" at "file:///etc/hosts"; 1')
};

declare %test:assertError("XQST0059") function i:unreadable-schema-refused() {
    (: the owner may import the schema; another user may not, even once it is cached :)
    let $owner := util:eval('import schema namespace t="urn:example:trade" at "xmldb:exist:///db/schema-type-test-private/trade.xsd"; 1')
    return
        system:as-user("guest", "guest",
            util:eval('import schema namespace t="urn:example:trade" at "xmldb:exist:///db/schema-type-test-private/trade.xsd"; 1'))
};

declare %test:assertEquals(1) function i:readable-schema-imported-by-other-user() {
    system:as-user("guest", "guest",
        util:eval('import schema namespace t="urn:example:trade" at "xmldb:exist:///db/schema-type-test/trade.xsd"; 1'))
};

declare %test:assertError("XQST0058") function i:duplicate() {
    util:eval('import schema namespace t="urn:example:trade"; import schema namespace u="urn:example:trade"; 1')
};

declare %test:assertError("XQST0057") function i:prefix-empty-ns() {
    util:eval('import schema namespace e=""; 1')
};

declare %test:assertError("XPST0008") function i:undeclared-type() {
    util:eval('import schema namespace t="urn:example:trade"; 1 instance of t:Nope')
};

declare %test:assertError("XPST0008") function i:undeclared-type-in-element-test() {
    util:eval('import schema namespace t="urn:example:trade"; doc("/db/schema-type-test/trade-1.xml")//element(*, t:Nope)')
};

declare %test:assertError("XPST0051") function i:type-of-namespace-not-imported() {
    (: unchanged: without import schema, a name in the namespace is not a known type :)
    util:eval('declare namespace t="urn:example:trade"; 1 instance of t:NonNegativeDecimal')
};

(: --- library modules --- :)

declare %test:assertEquals(2) function i:library-module-imports-schema() {
    util:eval('import module namespace l="urn:test:schema-type:lib" at "xmldb:exist:///db/schema-type-test-lib/lib.xqm"; l:swaps(doc("/db/schema-type-test/trade-1.xml"))')
};

declare %test:assertError("XPST0051") function i:library-module-import-not-visible-to-importer() {
    util:eval('import module namespace l="urn:test:schema-type:lib" at "xmldb:exist:///db/schema-type-test-lib/lib.xqm";
        declare namespace t="urn:example:trade"; 1 instance of t:NonNegativeDecimal')
};

(: --- type identity (spec 7.6) --- :)

declare %test:assertError("EXSA0001") function i:schema-version-mismatch() {
    util:eval('import schema namespace t="urn:example:trade" at "xmldb:exist:///db/schema-type-test-v2/trade.xsd"; count(doc("/db/schema-type-test/trade-1.xml")//element(*, t:Swap))')
};

(: --- typed steps over stored nodes --- :)

declare %test:assertEquals(2) function i:descendant-step-built-in-type() {
    (: a descendant step tests stored DOM nodes, not proxies: their annotations count (t:NonNegativeDecimal derives from xs:decimal) :)
    count($i:doc//element(*, xs:decimal))
};

declare %test:assertEquals(3) function i:descendant-step-imported-abstract-base() {
    util:eval('import schema namespace t="urn:example:trade" at "xmldb:exist:///db/schema-type-test/trade.xsd"; count(doc("/db/schema-type-test/trade-1.xml")//element(*, t:Product))')
};
