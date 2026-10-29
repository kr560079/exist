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
declare variable $i:HIDDEN := "/db/schema-type-test-hidden";
declare variable $i:HIDDEN_CONFIG := "/db/system/config/db/schema-type-test-hidden";
declare variable $i:LIB := "/db/schema-type-test-lib";

(: the hint is relative: it resolves against the module's load path :)
declare variable $i:LIB_MODULE := '
module namespace l="urn:test:schema-type:lib";
import schema namespace t="urn:example:trade" at "../schema-type-test/trade.xsd";
declare function l:swaps($d as node()) as xs:integer { count($d//element(*, t:Swap)) };
';

(: no import schema: the schema imports of an importing module do not apply here :)
declare variable $i:LIB2_MODULE := '
module namespace l2="urn:test:schema-type:lib2";
declare namespace t="urn:example:trade";
declare function l2:f() { 1 instance of t:NonNegativeDecimal };
';

declare %test:setUp function i:setUp() {
    fx:setUp(),
    (: a schema only its owner (the admin) may read, configured on a collection only the admin may read :)
    let $private := xmldb:create-collection("/db", "schema-type-test-private")
    let $stored := fx:store-fixture($i:PRIVATE, "secret.xsd")
    let $chmod := sm:chmod(xs:anyURI($i:PRIVATE || "/secret.xsd"), "rw-------")
    let $hidden := xmldb:create-collection("/db", "schema-type-test-hidden")
    let $hidden-chmod := sm:chmod(xs:anyURI($i:HIDDEN), "rwx------")
    let $hidden-config := xmldb:create-collection("/db/system/config/db", "schema-type-test-hidden")
    return
        xmldb:store($i:HIDDEN_CONFIG, "collection.xconf",
            <collection xmlns="http://exist-db.org/collection-config/1.0">
                <index><schema-type href="xmldb:exist://{$i:PRIVATE}/secret.xsd"/></index>
            </collection>),
    (: library modules :)
    let $lib := xmldb:create-collection("/db", "schema-type-test-lib")
    return (
        xmldb:store($i:LIB, "lib.xqm", $i:LIB_MODULE, "application/xquery"),
        xmldb:store($i:LIB, "lib2.xqm", $i:LIB2_MODULE, "application/xquery")
    )
};

declare %test:tearDown function i:tearDown() {
    fx:tearDown(),
    for $collection in ($i:HIDDEN_CONFIG, $i:HIDDEN, $i:PRIVATE, $i:LIB)
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

declare %test:assertEquals(2) function i:relative-hint-against-declared-base-uri() {
    util:eval('declare base-uri "xmldb:exist:///db/schema-type-test/";
        import schema namespace t="urn:example:trade" at "trade.xsd";
        count(doc("/db/schema-type-test/trade-1.xml")//element(*, t:Swap))')
};

declare %test:assertEquals(1) function i:imported-type-in-function-parameter-element-test() {
    util:eval('import schema namespace t="urn:example:trade" at "xmldb:exist:///db/schema-type-test/trade.xsd";
        declare function local:f($e as element(*, t:Swap)*) { count($e) };
        local:f(doc("/db/schema-type-test/trade-1.xml")//t:swap)')
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
    let $owner := util:eval('import schema namespace s="urn:example:secret" at "xmldb:exist:///db/schema-type-test-private/secret.xsd"; 1')
    return
        if ($owner eq 1) then
            system:as-user("guest", "guest",
                util:eval('import schema namespace s="urn:example:secret" at "xmldb:exist:///db/schema-type-test-private/secret.xsd"; 1'))
        else
            -1
};

declare %test:assertEquals(1) function i:configured-schema-on-readable-collection() {
    (: the admin may read the collection configured with the schema: an import without a hint finds it :)
    util:eval('import schema namespace s="urn:example:secret"; 1')
};

declare %test:assertError("XQST0059") function i:cached-schema-not-used-without-hint() {
    (: the schema is cached (the admin imported it) and configured on a collection the guest may not read :)
    let $owner := util:eval('import schema namespace s="urn:example:secret" at "xmldb:exist:///db/schema-type-test-private/secret.xsd"; 1')
    return
        if ($owner eq 1) then
            system:as-user("guest", "guest", util:eval('import schema namespace s="urn:example:secret"; 1'))
        else
            -1
};

declare %test:assertTrue function i:two-configured-versions-ambiguous() {
    let $collection := xmldb:create-collection("/db", "schema-type-test-amb")
    let $config-collection := xmldb:create-collection("/db/system/config/db", "schema-type-test-amb")
    let $config := xmldb:store("/db/system/config/db/schema-type-test-amb", "collection.xconf",
        <collection xmlns="http://exist-db.org/collection-config/1.0">
            <index><schema-type href="xmldb:exist:///db/schema-type-test-v2/trade.xsd"/></index>
        </collection>)
    let $result :=
        try {
            util:eval('import schema namespace t="urn:example:trade"; 1')
        } catch * {
            string($err:code) || " " || $err:description
        }
    let $cleanup := (xmldb:remove("/db/system/config/db/schema-type-test-amb"), xmldb:remove("/db/schema-type-test-amb"))
    return
        contains($result, "XQST0059") and contains($result, "ambiguous")
            and contains($result, "/db/schema-type-test-amb") and contains($result, "/db/schema-type-test")
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

declare %test:assertError("XPST0051") function i:main-module-import-not-visible-to-library() {
    util:eval('import schema namespace t="urn:example:trade" at "xmldb:exist:///db/schema-type-test/trade.xsd";
        import module namespace l2="urn:test:schema-type:lib2" at "xmldb:exist:///db/schema-type-test-lib/lib2.xqm";
        l2:f()')
};

(: --- pooled (compiled and reused) queries --- :)

declare %private function i:store-query($name as xs:string, $query as xs:string) as xs:anyURI {
    let $path := xmldb:store($i:LIB, $name, $query, "application/xquery")
    let $chmod := sm:chmod(xs:anyURI($path), "rwxr-xr-x")
    return
        xs:anyURI("xmldb:exist://" || $path)
};

declare %test:assertError("XQST0059") function i:pooled-query-no-hint-reauthorized() {
    (: the admin's compiled query goes to the pool; reusing it must not let the guest use the secret schema :)
    let $query := i:store-query("secret-no-hint.xq", 'import schema namespace s="urn:example:secret"; 1')
    let $admin := (util:eval($query, true()), util:eval($query, true()))
    return
        if ($admin = 1) then
            system:as-user("guest", "guest", util:eval($query, true()))
        else
            -1
};

declare %test:assertError("XQST0059") function i:pooled-query-hint-reauthorized() {
    let $query := i:store-query("secret-hint.xq",
        'import schema namespace s="urn:example:secret" at "xmldb:exist:///db/schema-type-test-private/secret.xsd"; 1')
    let $admin := (util:eval($query, true()), util:eval($query, true()))
    return
        if ($admin = 1) then
            system:as-user("guest", "guest", util:eval($query, true()))
        else
            -1
};

declare %test:assertEquals(1) function i:pooled-query-still-reused-by-authorized-user() {
    (: a refused borrow hands the entry back: the admin keeps using it :)
    let $query := i:store-query("secret-reuse.xq", 'import schema namespace s="urn:example:secret"; 1')
    let $admin := util:eval($query, true())
    let $guest := try { system:as-user("guest", "guest", util:eval($query, true())) } catch * { "refused" }
    return
        if ($guest = "refused") then util:eval($query, true()) else -1
};

declare %test:assertError("XPST0008") function i:pooled-query-sees-changed-schema() {
    (: the pooled query compiled against the first version of the schema; the second no longer defines m:Old :)
    let $v1 := xmldb:store($i:LIB, "mutable.xsd",
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:example:mutable">
            <xs:simpleType name="Old"><xs:restriction base="xs:integer"/></xs:simpleType>
        </xs:schema>)
    let $query := i:store-query("mutable.xq",
        'import schema namespace m="urn:example:mutable" at "xmldb:exist:///db/schema-type-test-lib/mutable.xsd"; 1 instance of m:Old')
    let $first := util:eval($query, true())
    let $v2 := xmldb:store($i:LIB, "mutable.xsd",
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:example:mutable">
            <xs:simpleType name="New"><xs:restriction base="xs:integer"/></xs:simpleType>
        </xs:schema>)
    return
        if ($first = false()) then util:eval($query, true()) else "unexpected"
};

declare %private function i:cfgver-schema($type as xs:string) as element() {
    <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:example:cfgver">
        <xs:simpleType name="{$type}"><xs:restriction base="xs:integer"/></xs:simpleType>
    </xs:schema>
};

declare %private function i:cfgver-configure($name as xs:string, $schema as xs:string) {
    let $collection := xmldb:create-collection("/db", $name)
    let $config-collection := xmldb:create-collection("/db/system/config/db", $name)
    return
        xmldb:store("/db/system/config/db/" || $name, "collection.xconf",
            <collection xmlns="http://exist-db.org/collection-config/1.0">
                <index><schema-type href="xmldb:exist:///db/schema-type-test-cfgver/{$schema}"/></index>
            </collection>)
};

(:~ pools a no-hint import of the configured schema (version 1 defines c:Old), changes the configurations, runs again :)
declare %private function i:cfgver-run($change as function(*)) as item()* {
    let $schemas := (
        xmldb:create-collection("/db", "schema-type-test-cfgver"),
        xmldb:store("/db/schema-type-test-cfgver", "v1.xsd", i:cfgver-schema("Old")),
        xmldb:store("/db/schema-type-test-cfgver", "v2.xsd", i:cfgver-schema("New"))
    )
    let $a := i:cfgver-configure("schema-type-test-cfg-a", "v1.xsd")
    let $query := i:store-query("cfgver.xq", 'import schema namespace c="urn:example:cfgver"; 1 instance of c:Old')
    let $result :=
        try {
            let $pooled := (util:eval($query, true()), util:eval($query, true()))
            let $changed := $change()
            return
                if ($pooled = false()) then util:eval($query, true()) else "unexpected"
        } catch * {
            string($err:code) || " " || $err:description
        }
    let $cleanup :=
        for $c in ("/db/system/config/db/schema-type-test-cfg-a", "/db/system/config/db/schema-type-test-cfg-b",
                "/db/schema-type-test-cfg-a", "/db/schema-type-test-cfg-b", "/db/schema-type-test-cfgver")
        where xmldb:collection-available($c)
        return xmldb:remove($c)
    return
        $result
};

declare %test:assertTrue function i:pooled-configured-import-sees-changed-configuration() {
    (: the collection is reconfigured with version 2, which has no c:Old: the pooled query is recompiled :)
    let $result := i:cfgver-run(function() { i:cfgver-configure("schema-type-test-cfg-a", "v2.xsd") })
    return
        contains($result, "XPST0008")
};

declare %test:assertTrue function i:pooled-configured-import-sees-new-ambiguity() {
    (: a second collection configures version 2: the namespace is now ambiguous :)
    let $result := i:cfgver-run(function() { i:cfgver-configure("schema-type-test-cfg-b", "v2.xsd") })
    return
        contains($result, "XQST0059") and contains($result, "ambiguous")
};

declare %test:assertTrue function i:configured-schema-unavailable-message() {
    (: the only configuration of the namespace names a schema that is gone :)
    let $collection := xmldb:create-collection("/db", "schema-type-test-gone")
    let $schema := xmldb:store("/db/schema-type-test-gone", "gone.xsd",
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:example:gone">
            <xs:element name="g" type="xs:string"/>
        </xs:schema>)
    let $config-collection := xmldb:create-collection("/db/system/config/db", "schema-type-test-gone")
    let $config := xmldb:store("/db/system/config/db/schema-type-test-gone", "collection.xconf",
        <collection xmlns="http://exist-db.org/collection-config/1.0">
            <index><schema-type href="xmldb:exist:///db/schema-type-test-gone/gone.xsd"/></index>
        </collection>)
    let $removed := xmldb:remove("/db/schema-type-test-gone", "gone.xsd")
    let $result :=
        try {
            util:eval('import schema namespace g="urn:example:gone"; 1')
        } catch * {
            string($err:code) || " " || $err:description
        }
    let $cleanup := (xmldb:remove("/db/system/config/db/schema-type-test-gone"), xmldb:remove("/db/schema-type-test-gone"))
    return
        contains($result, "XQST0059") and contains($result, "Configured schema unavailable")
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
