#!/usr/bin/env python3
"""Load an XSD and many XML files into a test eXist-db server, then report how they were typed.

Order: schema documents, then collection.xconf, then the XML files, so that nothing needs a reindex.
Needs Python 3.9+ and nothing else. The password comes from EXIST_PASSWORD or a prompt, never the command line.

  load_prod_test.py --xsd schemas/main.xsd --xsd-dir schemas --xml-dir data --limit 100
  load_prod_test.py --xsd schemas/main.xsd --xsd-dir schemas --xml-dir data
  load_prod_test.py --xml-dir data --append              # continue an interrupted load
  load_prod_test.py --report-only                        # report on what is already loaded
  load_prod_test.py --xml-dir data --no-schema --collection /db/prod-test-plain   # baseline for timing
"""
import argparse
import base64
import csv
import fnmatch
import getpass
import html
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from xml.sax.saxutils import quoteattr

CONFIG_ROOT = "/db/system/config"
CONNECTION_FAILURE = 0  # recorded as the HTTP status of a file whose request never got an answer
MAX_CONSECUTIVE_CONNECTION_FAILURES = 20
MAX_EXPLAINED_FAILURES = 25  # the REST PUT gives no reason for a 400: this many failed files are retried by a query to get it
MAX_EXPLAINED_BYTES = 20 * 1048576
RESULT_FIELDS = ["file", "db_path", "http_status", "seconds", "bytes", "error"]


class Server:
    def __init__(self, url, user, password):
        self.rest = url.rstrip("/") + "/rest"
        token = base64.b64encode(f"{user}:{password}".encode()).decode()
        self.headers = {"Authorization": "Basic " + token}

    def _send(self, method, path, data, content_type, timeout):
        headers = dict(self.headers)
        if content_type:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(
            self.rest + urllib.parse.quote(path, safe="/"), data=data, headers=headers, method=method)
        if hasattr(data, "read"):
            request.add_header("Content-Length", str(os.fstat(data.fileno()).st_size))
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.status, response.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "replace")

    def put_file(self, db_path, file_path, timeout):
        with open(file_path, "rb") as f:
            return self._send("PUT", db_path, f, "application/xml", timeout)

    def put_text(self, db_path, text):
        return self._send("PUT", db_path, text.encode("utf-8"), "application/xml", 300)

    def why_store_fails(self, db_path, data):
        """A REST PUT that fails says only 400; xmldb:store raises the reason. Returns None if it stores this time."""
        collection, name = db_path.rsplit("/", 1)
        # xmldb: functions take URIs, as the REST path was
        collection = urllib.parse.quote(collection, safe="/")
        name = urllib.parse.quote(name, safe="")
        relative = collection[len("/db/"):]
        encoded = base64.b64encode(data).decode()
        try:
            self.query(f"(xmldb:create-collection('/db', {xq_string(relative)}), "
                       f"xmldb:store({xq_string(collection)}, {xq_string(name)}, "
                       f"util:base64-decode('{encoded}'), 'application/xml'))[last()]")
            return None
        except RuntimeError as e:
            return re.sub(r"^.*?HTTP \d+: (exerr:ERROR )?(XMLDB reported an exception while storing document: )?"
                          r"(The XML parser reported a problem: )?", "", str(e))

    def query(self, xquery, timeout=3600):
        """Run a query that returns one string, and return that string."""
        if "]]>" in xquery:
            raise ValueError("a query must not contain ]]>")
        body = ('<query xmlns="http://exist.sourceforge.net/NS/exist" wrap="no" start="1" max="1">'
                f"<text><![CDATA[{xquery}]]></text></query>")
        status, text = self._send("POST", "/db", body.encode("utf-8"), "application/xml", timeout)
        if status != 200:
            hint = " (wrong user or password?)" if status == 401 else ""
            raise RuntimeError(f"query failed with HTTP {status}{hint}: {short_error(text)}")
        return text.strip()


def short_error(body):
    """The useful part of an error page: the error code, if there is one, and the message."""
    match = re.search(r'<pre class="message">(.*?)</pre>', body, re.S) or re.search(r"<message>(.*?)</message>", body, re.S)
    text = match.group(1) if match else re.sub(r"<[^>]+>", " ", body)
    text = re.sub(r"\s*\[at line \d+, column \d+, source: [^\]]*\]\s*$", "", html.unescape(text).strip())
    return " ".join(text.split())[:500]


def xq_string(value):
    return "'" + value.replace("&", "&amp;").replace("'", "''") + "'"


def check_collection_path(path):
    if not re.fullmatch(r"/db(/[^/]+)+", path) or path.split("/")[2] in ("system", "apps"):
        sys.exit(f"Refusing collection {path}: use a new test collection under /db, not /db, /db/system or /db/apps.")


def find_files(root, pattern):
    found = []
    for folder, subfolders, names in os.walk(root):
        subfolders.sort()
        found += [Path(folder) / n for n in sorted(names) if fnmatch.fnmatch(n.lower(), pattern.lower())]
    return found


def build_xconf(args, href):
    typed = [t.strip() for t in args.typed_values.split(",") if t.strip()] if args.typed_values else []
    typed_xml = ""
    if typed:
        rows = "".join(f"      <type name={quoteattr(t)}/>\n" for t in typed)
        typed_xml = f"    <typed-values>\n{rows}    </typed-values>\n"
    return ('<collection xmlns="http://exist-db.org/collection-config/1.0">\n'
            '  <index xmlns:xs="http://www.w3.org/2001/XMLSchema">\n'
            f'    <schema-type href={quoteattr(href)} on-unassignable="{args.on_unassignable}" '
            f'check-values="{args.check_values}"/>\n'
            f"{typed_xml}  </index>\n</collection>\n")


def load_schema(server, args):
    """Store the schema documents and the configuration that names them."""
    xsd = Path(args.xsd).resolve()
    if not xsd.is_file():
        sys.exit(f"No such XSD: {xsd}")
    base = Path(args.xsd_dir).resolve() if args.xsd_dir else xsd.parent
    if base not in xsd.parents:
        sys.exit(f"The XSD {xsd} is not inside --xsd-dir {base}")
    files = find_files(base, "*.xsd") if args.xsd_dir else [xsd]
    for file in files:
        try:
            ET.parse(file)
        except ET.ParseError as e:
            sys.exit(f"{file} is not well-formed XML: {e}")
    target = ET.parse(xsd).getroot().get("targetNamespace", "(no namespace)")
    print(f"Schema: {xsd.name}, target namespace {target}, {len(files)} file(s) to {args.schema_collection}")
    for file in files:
        db_path = f"{args.schema_collection}/{file.relative_to(base).as_posix()}"
        status, body = server.put_file(db_path, file, args.timeout)
        if status not in (200, 201):
            sys.exit(f"Could not store {file}: HTTP {status}: {short_error(body)}")

    if args.xconf:
        xconf = Path(args.xconf).read_text(encoding="utf-8")
    else:
        href = f"xmldb:exist://{args.schema_collection}/{xsd.relative_to(base).as_posix()}"
        xconf = build_xconf(args, href)
    status, body = server.put_text(f"{CONFIG_ROOT}{args.collection}/collection.xconf", xconf)
    if status not in (200, 201):
        why = server.why_store_fails(f"{CONFIG_ROOT}{args.collection}/collection.xconf", xconf.encode("utf-8"))
        sys.exit(f"The server rejected the configuration, usually because the schema does not compile.\n"
                 f"HTTP {status}: {why or short_error(body)}")
    print(f"Configuration stored at {CONFIG_ROOT}{args.collection}/collection.xconf")


def load_xml(server, args, out):
    root = Path(args.xml_dir).resolve()
    if not root.is_dir():
        sys.exit(f"No such directory: {root}")
    files = find_files(root, args.pattern)
    results_path = out / "results.csv"
    done = set()
    if args.append and results_path.exists():
        with open(results_path, newline="", encoding="utf-8") as f:
            done = {row["file"] for row in csv.DictReader(f) if row["http_status"] in ("200", "201")}
    todo = [f for f in files if f.relative_to(root).as_posix() not in done]
    if args.limit:
        todo = todo[:args.limit]
    if not todo:
        print(f"Nothing to load: {len(files)} file(s) found matching {args.pattern}, {len(done)} already stored.")
        return True
    total_bytes = sum(f.stat().st_size for f in todo)
    print(f"Loading {len(todo)} file(s), {total_bytes / 1048576:.1f} MB, {args.parallel} at a time, into {args.collection}")

    new_file = not results_path.exists() or not args.append
    results = open(results_path, "a" if args.append else "w", newline="", encoding="utf-8")
    writer = csv.DictWriter(results, RESULT_FIELDS)
    if new_file:
        writer.writeheader()

    stop = threading.Event()
    explained = iter(range(MAX_EXPLAINED_FAILURES))

    def store(file):
        if stop.is_set():
            return None
        relative = file.relative_to(root).as_posix()
        started = time.monotonic()
        try:
            status, body = server.put_file(f"{args.collection}/{relative}", file, args.timeout)
            error = "" if status in (200, 201) else short_error(body)
            if status == 400 and not error:
                if file.stat().st_size > MAX_EXPLAINED_BYTES or next(explained, None) is None:
                    error = "no reason given: see logs/exist.log on the server"
                else:
                    error = server.why_store_fails(f"{args.collection}/{relative}", file.read_bytes())
                    if error is None:
                        status, error = 201, ""
        except OSError as e:
            status, error = CONNECTION_FAILURE, str(e)[:500]
        return {"file": relative, "db_path": f"{args.collection}/{relative}", "http_status": status,
                "seconds": f"{time.monotonic() - started:.3f}", "bytes": file.stat().st_size, "error": error}

    started = time.monotonic()
    last_print = started
    stored = failed = lost = 0
    errors = Counter()
    shown = []
    with ThreadPoolExecutor(args.parallel) as pool:
        futures = [pool.submit(store, f) for f in todo]
        try:
            for future in as_completed(futures):
                row = future.result()
                if row is None:
                    continue
                writer.writerow(row)
                results.flush()
                if row["http_status"] in (200, 201):
                    stored += 1
                    lost = 0
                else:
                    failed += 1
                    code = re.search(r"\b(EXSA\d{4}|[XF][A-Z]{3}\d{4})\b", row["error"])
                    if code:
                        errors[code.group(1)] += 1
                    elif "fatal error" in row["error"]:
                        errors["not well-formed XML"] += 1
                    else:
                        errors[f"HTTP {row['http_status']}"] += 1
                    if len(shown) < 5:
                        shown.append(f"  {row['file']}: {row['error'][:300]}")
                    lost = lost + 1 if row["http_status"] == CONNECTION_FAILURE else 0
                    if lost >= MAX_CONSECUTIVE_CONNECTION_FAILURES:
                        stop.set()
                        print("Stopping: the server has stopped answering. Check it, then rerun with --append.")
                now = time.monotonic()
                if now - last_print >= 10:
                    last_print = now
                    print(f"  {stored + failed}/{len(todo)} done, {failed} failed, {stored / (now - started):.1f} docs/s", flush=True)
        except KeyboardInterrupt:
            stop.set()
            print("\nInterrupted: finishing the files in progress. Rerun with --append to continue.")
    results.close()

    elapsed = time.monotonic() - started
    print(f"Stored {stored} of {len(todo)} in {elapsed:.1f} s ({stored / elapsed:.1f} docs/s, "
          f"{total_bytes / 1048576 / elapsed:.1f} MB/s). Failed: {failed}.")
    for code, count in errors.most_common():
        print(f"  {code}: {count}")
    print("\n".join(shown))
    if failed:
        print(f"Each failure and its message is in {results_path}")
    return failed == 0 and not stop.is_set()


REPORT_QUERY = """
import module namespace schema = "http://exist-db.org/xquery/schema-type";
declare variable $c := %s;
let $rows :=
    for $d in collection($c)
    let $status := schema:status($d)?status
    let $paths := if ($status = 'partial') then schema:unassignable($d) else ()
    return map { 'uri': document-uri($d), 'status': $status, 'paths': array { $paths },
                 'root': string(node-name($d/*)),
                 'type': if ($status = ('typed', 'partial')) then
                     let $name := fn:node-type-annotation($d/*)?name
                     return if (exists($name)) then string($name) else '(anonymous)'
                     else '' }
return serialize(array { $rows }, map { 'method': 'json' })
"""


def report(server, args, out):
    rows = json.loads(server.query(REPORT_QUERY % xq_string(args.collection)))
    statuses = Counter(r["status"] for r in rows)
    print(f"\nDocuments in {args.collection}: {len(rows)}")
    if not rows:
        return
    for status, count in statuses.most_common():
        print(f"  {status:12} {count:>9}  {100 * count / len(rows):5.1f}%")

    roots = Counter((r["root"], r["type"]) for r in rows)
    print("\nRoot element and its assigned type:")
    for (root, type_name), count in roots.most_common(20):
        print(f"  {count:>9}  {root}  ->  {type_name or '(none)'}")

    paths = Counter()
    example = {}
    for row in rows:
        for path in row["paths"]:
            general = re.sub(r"\[\d+\]", "", path)
            paths[general] += 1
            example.setdefault(general, row["uri"])
    if paths:
        print("\nMost common nodes that could not be typed (positions removed):")
        for path, count in paths.most_common(20):
            print(f"  {count:>9}  {path}\n             e.g. {example[path]}")

    with open(out / "documents.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["document", "status", "root", "root_type", "untyped_paths"])
        for row in rows:
            writer.writerow([row["uri"], row["status"], row["root"], row["type"], " ".join(row["paths"])])
    with open(out / "untyped-paths.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["count", "path", "example_document"])
        for path, count in paths.most_common():
            writer.writerow([count, path, example[path]])
    print(f"\nPer-document results: {out / 'documents.csv'}\nUntyped paths: {out / 'untyped-paths.csv'}")


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--xsd", help="the root XSD")
    p.add_argument("--xsd-dir", help="store every *.xsd under this folder, keeping its layout (needed if the XSD includes others)")
    p.add_argument("--xml-dir", help="folder of XML files; subfolders become subcollections")
    p.add_argument("--pattern", default="*.xml", help="file name pattern (default %(default)s)")
    p.add_argument("--collection", default="/db/prod-test", help="target collection (default %(default)s)")
    p.add_argument("--schema-collection", help="where the XSDs go (default /db/schemas/<collection name>)")
    p.add_argument("--xconf", help="use this collection.xconf as is, e.g. with your own indexes; it must name the stored XSD")
    p.add_argument("--on-unassignable", choices=["untyped", "reject"], default="untyped",
                   help="untyped keeps a node that does not fit and flags the document; reject refuses the file (default %(default)s)")
    p.add_argument("--check-values", choices=["true", "false"], default="true", help="check values against their types (default %(default)s)")
    p.add_argument("--typed-values", help="comma-separated xs: types to index for comparisons, e.g. xs:decimal,xs:date")
    p.add_argument("--no-schema", action="store_true", help="load with no schema, as a timing baseline")
    p.add_argument("--limit", type=int, help="load at most this many files")
    p.add_argument("--parallel", type=int, default=4, help="files stored at once (default %(default)s)")
    p.add_argument("--timeout", type=int, default=600, help="seconds allowed per file (default %(default)s)")
    p.add_argument("--append", action="store_true", help="add to an existing collection, skipping files already stored; schema and configuration are left alone")
    p.add_argument("--report-only", action="store_true", help="load nothing; report on the collection")
    p.add_argument("--no-report", action="store_true", help="skip the report after loading")
    p.add_argument("--url", default="http://localhost:8080/exist", help="server (default %(default)s)")
    p.add_argument("--allow-non-local", action="store_true", help="allow a server other than localhost")
    p.add_argument("--user", default="admin", help="user (default %(default)s)")
    p.add_argument("--out", default="load-results", help="folder for result files (default %(default)s)")
    args = p.parse_args()
    if not args.report_only and not args.xml_dir:
        p.error("--xml-dir is required unless --report-only is given")
    if not (args.report_only or args.append or args.no_schema or args.xsd):
        p.error("--xsd is required, unless --append, --report-only or --no-schema is given")
    if args.no_schema and args.xsd:
        p.error("--no-schema and --xsd cannot be combined")
    args.collection = args.collection.rstrip("/")
    args.schema_collection = (args.schema_collection or f"/db/schemas/{args.collection.rsplit('/', 1)[-1]}").rstrip("/")
    return args


def main():
    args = parse_args()
    check_collection_path(args.collection)
    check_collection_path(args.schema_collection)
    host = urllib.parse.urlparse(args.url).hostname
    if host not in ("localhost", "127.0.0.1", "::1") and not args.allow_non_local:
        sys.exit(f"Refusing server {host}: this loads test data. Pass --allow-non-local if that is really the test server.")
    password = os.environ.get("EXIST_PASSWORD")
    if password is None:
        password = getpass.getpass(f"Password for {args.user} (empty if none): ")

    server = Server(args.url, args.user, password)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    try:
        version = server.query("system:get-version() || ' / ' || system:get-revision()")
        print(f"Server: {args.url}, eXist-db {version}")
        exists = server.query(f"string(xmldb:collection-available({xq_string(args.collection)}))") == "true"
        if args.report_only:
            if not exists:
                sys.exit(f"Collection {args.collection} does not exist.")
            report(server, args, out)
            return
        if exists and not args.append:
            count = server.query(f"string(count(collection({xq_string(args.collection)})))")
            sys.exit(f"Collection {args.collection} already exists with {count} document(s). "
                     "Use --append to add to it, or a new --collection. This script never deletes anything.")
        if not args.append and args.xsd:
            load_schema(server, args)
        ok = load_xml(server, args, out)
        if not args.no_report and server.query(f"string(xmldb:collection-available({xq_string(args.collection)}))") == "true":
            report(server, args, out)
    except (OSError, RuntimeError) as e:
        sys.exit(f"Cannot talk to the server at {args.url}: {e}")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
