# eXist-db schema-type prod test: build and deploy cheatsheet

Nov 3, 2026 · @Ram Kalapatapu

## What this tests

Build `feature/schema-aware-xquery` at commit `ffd3d5bae6`, load your XSD and a copy of your production XML into a new server, and see how many documents get fully typed.

- **Use a copy of the data, on its own server.** This build has not been tried against an existing production database, and its query engine is stricter for every collection.
- **Loading does not validate.** The index assigns types without fully validating each document, so `typed` means types were assigned, not that the document is schema-valid.
- **A good first result:** every file stores, most documents report `typed`, and any `partial` documents point to paths you can explain.

## Build

The build needs JDK 21 or later, Maven and one extra library, and took about a minute on an 18-core Mac. The schema-type index is on by default in this build, so there is nothing to enable.

**1. Make the WebDAV library available (once per machine).** It is only published to a registry that needs a token, so a plain build fails with a 401 on `jackrabbit-webdav`. Pick one:

- **With a GitHub token (the documented route):** create a token with the `read:packages` scope and add it to `~/.m2/settings.xml`, as shown in `BUILD.md` under "GitHub Packages".
- **Without a token:** build the library from its public source.

```bash
git clone --depth 1 --branch v2.22.3-jakarta-ee10 \
  https://github.com/eXist-db/jackrabbit-webdav-jakarta.git
(cd jackrabbit-webdav-jakarta && mvn install -DskipTests)
```

**2. Build the distribution at the tested commit.**

```bash
git clone https://github.com/kr560079/exist.git
cd exist
git checkout ffd3d5bae6

export JAVA_HOME=/path/to/jdk
mvn -T1.5C clean package -pl exist-distribution -am \
  -DskipTests -Ddependency-check.skip=true -Ddocker=false \
  -Drevision=7.0.0-SNAPSHOT -P '!mac-dmg-on-mac'
```

On Linux, use `-P '!mac-dmg-on-unix'` instead.

**Result:** `exist-distribution/target/exist-distribution-7.0.0-SNAPSHOT-unix.tar.bz2`, about 160 MB. A `-win.zip` is built beside it.

**How far this was tested:** neither option in step 1 was run. I built and tested with WebDAV removed from a throwaway copy, so a build that includes WebDAV is still unproven. The rest of the code is the same.

## Deploy and start

Unpack the archive into a new folder and leave any existing eXist-db install alone. The database lives in `data/` inside that folder, so two installs do not share data.

```bash
mkdir -p /opt/exist-test
tar xjf exist-distribution-7.0.0-SNAPSHOT-unix.tar.bz2 -C /opt/exist-test
cd /opt/exist-test/exist-distribution-7.0.0-SNAPSHOT

export JAVA_HOME=/path/to/jdk
JAVA_OPTS='-Xmx8g -Djetty.http.port=8099' nohup bin/startup.sh > startup.log 2>&1 &
```

Set `-Xmx` to suit the machine; the 8 GB here is only an example. `jetty.http.port` avoids a clash if something already uses port 8080, which is the default. If another eXist-db runs on the machine, also set `-Djetty.ssl.port`, as it uses 8443 by default.

**Check it is up and set a password.** A new install has an `admin` user with an empty password.

```bash
curl -s -o /dev/null -w '%{http_code}\n' -u admin: http://localhost:8099/exist/rest/db   # expect 200

curl -s -u admin: -G http://localhost:8099/exist/rest/db --data-urlencode '_wrap=no' \
  --data-urlencode '_query=sm:passwd("admin", "CHOOSE-A-PASSWORD")'
```

**Stop it:**

```bash
bin/shutdown.sh -u admin -p 'CHOOSE-A-PASSWORD' -l xmldb:exist://localhost:8099/exist/xmlrpc
```

**Start again from nothing:** stop the server, then delete the unpacked folder and unpack the archive again. Nothing outside that folder changes. Logs are in `logs/`, mainly `logs/exist.log`.

## Load the XSD and XML

Use `load_prod_test.py`, which stores the XSDs, then the configuration, then the XML, so nothing needs a reindex. It is a single file that needs only Python 3.9 or later. It is in this folder; copy it to the test server if you load from there.

**Start with a sample, then load the rest.** Set the password once, and leave `--xsd-dir` out only if your XSD includes no other files.

```bash
export EXIST_PASSWORD='CHOOSE-A-PASSWORD'
URL=http://localhost:8099/exist

# 1. Try 100 files and read the report
python3 load_prod_test.py --url $URL \
  --xsd /data/schemas/main.xsd --xsd-dir /data/schemas \
  --xml-dir /data/xml --limit 100

# 2. If that looks right, load everything else
python3 load_prod_test.py --url $URL --xml-dir /data/xml --append --parallel 8
```

Subfolders of `--xml-dir` become subcollections. By default the files go to `/db/prod-test` and the XSDs to `/db/schemas/prod-test`.

| Option | Use it to |
| --- | --- |
| `--on-unassignable reject` | Refuse a file with a node that cannot be typed, instead of storing it as `partial`. Use a new `--collection` to compare. |
| `--typed-values xs:decimal,xs:date` | Index values of these built-in types to speed up comparisons. This costs extra load time. |
| `--xconf my.xconf` | Use your own `collection.xconf`, for example with your production indexes. It must name the stored XSD: `xmldb:exist:///db/schemas/prod-test/main.xsd`. |
| `--no-schema --collection /db/prod-test-plain` | Load with no schema, as a timing baseline. |
| `--append` | Continue after an interruption. Files already stored are skipped, and the schema and configuration stay as they are. |
| `--report-only` | Load nothing and print the report again. |
| `--pattern '*.XML'`, `--limit N`, `--timeout S` | Match other file names, cap the number of files, or allow longer per file. Patterns ignore case. |
| `--allow-non-local` | Load into a server other than localhost. Without it the script refuses. Plain `http` sends the password and your data unencrypted. |

**Safeguards:** the script never deletes anything. It refuses an existing collection unless you pass `--append`, and refuses `/db`, `/db/system` and `/db/apps`.

**Results** go in `--out`, by default `load-results/`:

- `results.csv`: every file, with its HTTP status, time and error
- `documents.csv`: every document, with its status and the paths that could not be typed
- `untyped-paths.csv`: those paths with positions removed, most common first

The exit code is 0 only if every file stored. The first 25 failures per run include the server's reason; for the rest, look in `logs/exist.log`.

## Check the results

Read the script's report first: it shows how many documents were typed and which nodes were not. This is real output from the test run, where one file had a deliberately invalid element.

```
Documents in /db/prod-test: 2001
  typed             2000  100.0%
  partial              1    0.0%

Root element and its assigned type:
       2001  t:trades  ->  (anonymous)

Most common nodes that could not be typed (positions removed):
          1  /t:trades/t:trade/t:bogus
             e.g. /db/prod-test/bad.xml
```

| Status | Meaning | What to do |
| --- | --- | --- |
| `typed` | Every node got a type. | Nothing. |
| `partial` | Some subtrees have no type. The rest of the document is typed. | Check `untyped-paths.csv`. A path listed thousands of times usually points at the XSD, not the data. |
| `stale` | The document was indexed with another schema or configuration. | Reindex the collection. |
| `unavailable` | The schema could not be loaded. | Check that the XSDs are stored and readable. |
| `untyped` | The collection has no schema. | Expected only for a `--no-schema` load. |

**Try typed queries on your own data.** Replace the namespace, the type and the element names with yours. In this test the result was `6003 | 2001 | NonNegativeDecimal | true`.

```bash
Q() { curl -s -u "admin:$EXIST_PASSWORD" -G $URL/rest/db --data-urlencode '_wrap=no' --data-urlencode "_query=$1"; echo; }

Q 'import schema namespace t="urn:example:trade";
let $c := collection("/db/prod-test")
return string-join((
  string(count($c//element(*, t:Product))),     (: nodes of a schema type, or one derived from it :)
  string(count($c//t:amount[. > 1000])),        (: compared as numbers, not strings :)
  string(($c//t:amount)[1] ! fn:node-type-annotation(.)?name),   (: the assigned type :)
  string(data(($c//t:amount)[1]) instance of xs:decimal)         (: the typed value :)
), " | ")'
```

**Inspect one document:**

```bash
Q 'import module namespace schema="http://exist-db.org/xquery/schema-type";
let $d := doc("/db/prod-test/some-file.xml")
return schema:status($d)?status || " " || string-join(schema:unassignable($d), ", ")'
```

**Reindex after changing the XSD or the configuration:** `Q 'string(xmldb:reindex("/db/prod-test"))'`. Allow time on a large collection, because stores into it wait while it runs.

**Compare load time:** run the same files with `--no-schema` into another collection and compare the `docs/s` lines. On the FpML benchmark the schema index added about 55%; your documents will differ.

## Troubleshooting

The error code tells you where to look; the script prints it for failed files, and `logs/exist.log` has the rest.

| You see | Cause | Fix |
| --- | --- | --- |
| `401` on `jackrabbit-webdav` during the build | The token-gated library is missing. | Do step 1 of Build. After adding a token, build with `-U`, as Maven remembers the failure. |
| `The server rejected the configuration` | The XSD does not compile, or an included file was not stored. The message gives the Xerces reason. | Fix the XSD, or pass `--xsd-dir` so that included files are stored too. Includes over http(s) are off by default. |
| `XQST0059` on `import schema` | No readable configured collection or catalog has a schema for that namespace, or several versions do. | Check the namespace, or add a hint: `import schema namespace t="..." at "xmldb:exist:///db/schemas/prod-test/main.xsd";` |
| `EXSA0001` | The query imports a different schema version from the one the documents were typed with. | Import the configured version, or reindex with the new one. |
| `EXSA0002` | The collection's schema cannot be loaded. | Check the stored XSDs and their permissions. |
| `EXSA0003` | The documents are stale: stored before the configuration, or the schema changed. | Reindex the collection. |
| `EXSA0004` | A node cannot be typed, in `reject` mode. The message gives the node's path. | Fix the file or the XSD, or use the default `untyped` mode. |
| `fatal error at (line,col)` | The file is not well-formed XML. | Fix the file. |
| `FOTY0012` | A query atomized an element that has only child elements. | Query a child element instead. |

**Existing queries can behave differently.** Try the queries you run in production, because these changes apply as noted:

| Change | Applies to | Symptom |
| --- | --- | --- |
| `amount = "1000"` compares a decimal with a string | Schema-configured collections | `XPTY0004`. Use `amount = 1000` or `string(amount) = "1000"`. |
| `element(N, T)` and `attribute(N, T)` really test the type | All collections | Fewer matches, or `XPTY0004` in a function signature or `let` type. |
| A function declared to take or return one atomic value gets several, or none for a nilled element | All collections | `XPTY0004`, where it used to pass silently. |
| `import schema` with no schema to be found | All collections | `XQST0059`, where it used to be `XQST0009`. |

The full list is in `extensions/indexes/schema-type/README.md`, under "Compatibility and release notes".
