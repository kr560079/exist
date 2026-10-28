/*
 * eXist-db Open Source Native XML Database
 * Copyright (C) 2001 The eXist-db Authors
 *
 * info@exist-db.org
 * http://www.exist-db.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.exist.indexing.schematype;

import org.apache.xerces.impl.xs.XMLSchemaLoader;
import org.apache.xerces.xs.XSComplexTypeDefinition;
import org.apache.xerces.xs.XSModel;
import org.exist.dom.QName;
import org.exist.indexing.schematype.TypeAssigner.Assignment;
import org.exist.numbering.NodeId;
import org.exist.storage.ElementValue;
import org.exist.xquery.schema.CompiledSchema;
import org.exist.xquery.schema.SchemaKey;
import org.exist.xquery.schema.ValueChecker;
import org.exist.xquery.value.Type;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Node;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.annotation.Nullable;
import javax.xml.parsers.SAXParserFactory;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.exist.indexing.schematype.TypeAssigner.Status.ANNOTATED;
import static org.exist.indexing.schematype.TypeAssigner.Status.UNASSIGNABLE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure tests of {@link TypeAssigner}: documents are parsed with SAX and fed to the assigner through a small
 * adapter which numbers nodes like eXist's DLN scheme (attributes first, then element children).
 */
public class TypeAssignerTest {

    private static final String NS = "urn:example:trade";
    private static final String MNS = "urn:example:misc";
    private static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String FIXTURES = "/org/exist/xquery/schema/fixtures/";

    private static final String TRADES_OPEN = "<t:trades xmlns:t='" + NS + "' xmlns:xsi='" + XSI + "'>";
    private static final String TRADES_CLOSE = "</t:trades>";

    private static final String MISC_XSD = """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:m="urn:example:misc"
                       targetNamespace="urn:example:misc" elementFormDefault="qualified">
                <xs:complexType name="Base">
                    <xs:sequence><xs:element name="a" type="xs:string" minOccurs="0"/></xs:sequence>
                </xs:complexType>
                <xs:complexType name="Derived">
                    <xs:complexContent><xs:extension base="m:Base"/></xs:complexContent>
                </xs:complexType>
                <xs:complexType name="AbstractT" abstract="true"/>
                <xs:element name="known" type="xs:int"/>
                <xs:element name="root">
                    <xs:complexType>
                        <xs:sequence>
                            <xs:element name="blocked" type="m:Base" block="extension" minOccurs="0"/>
                            <xs:element name="open" type="m:Base" minOccurs="0" maxOccurs="unbounded"/>
                            <xs:element name="abs" type="m:AbstractT" minOccurs="0"/>
                            <xs:element name="lax" minOccurs="0">
                                <xs:complexType><xs:sequence>
                                    <xs:any processContents="lax" minOccurs="0" maxOccurs="unbounded"/>
                                </xs:sequence></xs:complexType>
                            </xs:element>
                            <xs:element name="strict" minOccurs="0">
                                <xs:complexType><xs:sequence>
                                    <xs:any processContents="strict" minOccurs="0" maxOccurs="unbounded"/>
                                </xs:sequence></xs:complexType>
                            </xs:element>
                            <xs:element name="attrs" minOccurs="0">
                                <xs:complexType>
                                    <xs:attribute name="n" type="xs:int"/>
                                    <xs:anyAttribute namespace="##other" processContents="skip"/>
                                </xs:complexType>
                            </xs:element>
                        </xs:sequence>
                    </xs:complexType>
                </xs:element>
            </xs:schema>
            """;

    private static XSModel model;
    private static CompiledSchema schema;
    private static ValueChecker checker;
    private static CompiledSchema misc;

    /** assignments for trade-1.xml, by path */
    private static Map<String, Assignment> trade1;
    /** assignments for bad.xml, by path */
    private static Map<String, Assignment> bad;

    @BeforeAll
    static void load() throws Exception {
        final URL url = TypeAssignerTest.class.getResource(FIXTURES + "trade.xsd");
        assertNotNull(url);
        model = new XMLSchemaLoader().loadURI(url.toString());
        assertNotNull(model);
        schema = CompiledSchema.compile(new SchemaKey("test-assigner"), model);
        checker = new ValueChecker(schema);

        final Path miscXsd = Files.createTempFile("type-assigner-misc", ".xsd");
        miscXsd.toFile().deleteOnExit();
        Files.writeString(miscXsd, MISC_XSD, StandardCharsets.UTF_8);
        final XSModel miscModel = new XMLSchemaLoader().loadURI(miscXsd.toUri().toString());
        assertNotNull(miscModel);
        misc = CompiledSchema.compile(new SchemaKey("test-assigner-misc"), miscModel);

        trade1 = byPath(runResource("trade-1.xml", schema, checker));
        bad = byPath(runResource("bad.xml", schema, checker));
    }

    // --- harness ---

    /** Feeds SAX events to a {@link TypeAssigner}, draining after every event. */
    private static final class Adapter extends DefaultHandler {
        private final TypeAssigner assigner;
        private final List<Assignment> out = new ArrayList<>();
        private final Deque<NodeId> ids = new ArrayDeque<>();
        private final Deque<NodeId[]> lastChild = new ArrayDeque<>();
        private final Deque<Map<String, String>> scopes = new ArrayDeque<>();
        private final Map<String, String> pendingNs = new HashMap<>();

        Adapter(final TypeAssigner assigner) {
            this.assigner = assigner;
        }

        private NodeId nextChild() {
            if (ids.isEmpty()) {
                return NodeId.ROOT_NODE;
            }
            final NodeId[] last = lastChild.peek();
            last[0] = last[0] == null ? ids.peek().newChild() : last[0].nextSibling();
            return last[0];
        }

        @Override
        public void startPrefixMapping(final String prefix, final String uri) {
            pendingNs.put(prefix, uri);
        }

        @Override
        public void startElement(final String uri, final String localName, final String qName, final Attributes atts) {
            final NodeId id = nextChild();
            final Map<String, String> scope = new HashMap<>(scopes.isEmpty() ? Map.of() : scopes.peek());
            scope.putAll(pendingNs);
            pendingNs.clear();
            scopes.push(scope);
            ids.push(id);
            lastChild.push(new NodeId[1]);
            assigner.startElement(id, new QName(localName, uri, prefixOf(qName)), scope::get);
            drain();
            for (int i = 0; i < atts.getLength(); i++) {
                final QName name = new QName(atts.getLocalName(i), atts.getURI(i), prefixOf(atts.getQName(i)),
                        ElementValue.ATTRIBUTE);
                assigner.attribute(nextChild(), name, atts.getValue(i));
                drain();
            }
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) {
            assigner.characters(new String(ch, start, length));
            drain();
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) {
            assigner.endElement();
            drain();
            ids.pop();
            lastChild.pop();
            scopes.pop();
        }

        private void drain() {
            out.addAll(assigner.drain());
        }

        private static @Nullable String prefixOf(final String qName) {
            final int colon = qName.indexOf(':');
            return colon < 0 ? null : qName.substring(0, colon);
        }
    }

    private static List<Assignment> run(final InputSource source, final CompiledSchema s, @Nullable final ValueChecker c)
            throws Exception {
        final SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        final Adapter adapter = new Adapter(new TypeAssigner(s, c));
        factory.newSAXParser().parse(source, adapter);
        return adapter.out;
    }

    private static List<Assignment> runResource(final String name, final CompiledSchema s, @Nullable final ValueChecker c)
            throws Exception {
        final URL url = TypeAssignerTest.class.getResource(FIXTURES + name);
        assertNotNull(url, name);
        return run(new InputSource(url.toString()), s, c);
    }

    private static Map<String, Assignment> runXml(final String xml, final CompiledSchema s, @Nullable final ValueChecker c)
            throws Exception {
        return byPath(run(new InputSource(new StringReader(xml)), s, c));
    }

    /** run a fragment wrapped in {@code <t:trades><t:trade>…</t:trade></t:trades>} against trade.xsd */
    private static Map<String, Assignment> trade(final String tradeContent) throws Exception {
        return trade(tradeContent, checker);
    }

    private static Map<String, Assignment> trade(final String tradeContent, @Nullable final ValueChecker c) throws Exception {
        return runXml(TRADES_OPEN + "<t:trade>" + tradeContent + "</t:trade>" + TRADES_CLOSE, schema, c);
    }

    private static Map<String, Assignment> misc(final String rootContent) throws Exception {
        return runXml("<m:root xmlns:m='" + MNS + "' xmlns:xsi='" + XSI + "'>" + rootContent + "</m:root>", misc, null);
    }

    private static Map<String, Assignment> byPath(final List<Assignment> assignments) {
        final Map<String, Assignment> map = new LinkedHashMap<>();
        for (final Assignment a : assignments) {
            assertNull(map.put(a.path(), a), "duplicate assignment for " + a.path());
        }
        return map;
    }

    private static final Pattern STEP = Pattern.compile("/(@?[^/\\[]+)(\\[\\d+])?");

    /**
     * Expand the test shorthand: {@code /t:trade/…} stands for {@code /t:trades[1]/t:trade[1]/…}, {@code /m:…}
     * for {@code /m:root[1]/m:…}, and steps without a position get {@code [1]}.
     */
    private static String expand(final String shortPath) {
        String p = shortPath;
        if (p.startsWith("/t:trade/") || p.equals("/t:trade")) {
            p = "/t:trades" + p;
        } else if (p.startsWith("/m:") && !p.startsWith("/m:root")) {
            p = "/m:root" + p;
        }
        final StringBuilder sb = new StringBuilder();
        final Matcher m = STEP.matcher(p);
        while (m.find()) {
            sb.append('/').append(m.group(1));
            if (!m.group(1).startsWith("@")) {
                sb.append(m.group(2) != null ? m.group(2) : "[1]");
            }
        }
        return sb.toString();
    }

    private static Assignment at(final Map<String, Assignment> doc, final String shortPath) {
        final String path = expand(shortPath);
        final Assignment a = doc.get(path);
        assertNotNull(a, "no assignment for " + path + " in " + doc.keySet());
        return a;
    }

    private static Assignment at(final String shortPath) {
        return at(trade1, shortPath);
    }

    private static void assertType(final Map<String, Assignment> doc, final String shortPath, final int typeCode) {
        final Assignment a = at(doc, shortPath);
        assertEquals(ANNOTATED, a.status(), a.path() + ": " + a.reason());
        assertEquals(typeCode, a.typeCode(), a.path() + " has " + Type.getTypeName(a.typeCode()));
    }

    private static void assertType(final String shortPath, final int typeCode) {
        assertType(trade1, shortPath, typeCode);
    }

    private static Assignment assertStatus(final Map<String, Assignment> doc, final String shortPath, final TypeAssigner.Status status) {
        final Assignment a = at(doc, shortPath);
        assertEquals(status, a.status(), a.path());
        return a;
    }

    private static void assertUnassignable(final Map<String, Assignment> doc, final String shortPath, final String reason) {
        final Assignment a = assertStatus(doc, shortPath, UNASSIGNABLE);
        assertEquals(reason, a.reason(), a.path());
    }

    private static void assertNone(final Map<String, Assignment> doc, final String shortPath) {
        final String path = expand(shortPath);
        assertFalse(doc.containsKey(path), "unexpected assignment for " + path);
    }

    private static void assertNoAssignmentsBelow(final Map<String, Assignment> doc, final String shortPath) {
        final String path = expand(shortPath);
        for (final String p : doc.keySet()) {
            assertFalse(p.startsWith(path + "/"), "unexpected assignment below " + path + ": " + p);
        }
    }

    private static int code(final String local) {
        return schema.typeCode(model.getTypeDefinition(local, NS));
    }

    // --- element types ---

    @Test
    public void rootAndTradesAnnotated() {
        assertStatus(trade1, "/t:trades", ANNOTATED);
        assertStatus(trade1, "/t:trades/t:trade[1]", ANNOTATED);
        assertStatus(trade1, "/t:trades/t:trade[2]", ANNOTATED);
        assertStatus(trade1, "/t:trades/t:trade[3]", ANNOTATED);
        assertEquals(schema.typeCode(model.getElementDeclaration("trade", NS).getTypeDefinition()),
                at("/t:trade").typeCode());
    }

    @Test
    public void validDocumentHasNoUnassignableNodes() {
        for (final Assignment a : trade1.values()) {
            assertEquals(ANNOTATED, a.status(), a.path() + ": " + a.reason());
            assertNull(a.reason(), a.path());
        }
    }

    @Test
    public void localSameNameElementsTypedByParent() {
        assertType("/t:trade/t:notional/t:amount", code("NonNegativeDecimal"));
        assertType("/t:trade/t:fee/t:amount", Type.DECIMAL);
        assertType("/t:trade/t:notional/t:currency", code("CurrencyCode"));
    }

    @Test
    public void listType() {
        assertType("/t:trade/t:fixingDates", code("DateList"));
    }

    @Test
    public void mixedContent() {
        final XSComplexTypeDefinition tradeType =
                (XSComplexTypeDefinition) model.getElementDeclaration("trade", NS).getTypeDefinition();
        final int description = schema.typeCode(
                schema.resolveChild(tradeType, new QName("description", NS)).decl().getTypeDefinition());
        assertType("/t:trade/t:description", description);
    }

    @Test
    public void substitutionMemberGetsMemberType() {
        assertType("/t:trades/t:trade[1]/t:swap", code("Swap"));
        assertType("/t:trades/t:trade[2]/t:fra", code("Fra"));
        assertType("/t:trades/t:trade[3]/t:vanillaSwap", code("VanillaSwap"));
    }

    @Test
    public void blockedSubstitutionIsUnassignable() throws Exception {
        final Map<String, Assignment> doc = trade("<t:fra><t:legs><t:blockedSwap/><t:blockedSwapMember/></t:legs></t:fra>");
        assertType(doc, "/t:trade/t:fra/t:legs/t:blockedSwap", code("Swap"));
        assertUnassignable(doc, "/t:trade/t:fra/t:legs/t:blockedSwapMember", "element t:blockedSwapMember not allowed here");
    }

    @Test
    public void simpleContentWithAttribute() {
        assertType("/t:trade/t:tradeDate", code("AdjustableDate"));
    }

    @Test
    public void nodeKindsAndIds() {
        final Assignment root = at("/t:trades");
        assertEquals(Node.ELEMENT_NODE, root.nodeKind());
        assertEquals(NodeId.ROOT_NODE, root.nodeId());
        final Assignment trade = at("/t:trade");
        assertEquals(NodeId.ROOT_NODE.newChild(), trade.nodeId());
        final Assignment attr = at("/t:trade/t:party/@id");
        assertEquals(Node.ATTRIBUTE_NODE, attr.nodeKind());
        assertTrue(attr.nodeId().isChildOf(at("/t:trade/t:party").nodeId()));
    }

    @Test
    public void assignmentsInDocumentOrder() throws Exception {
        final List<Assignment> list = runResource("trade-1.xml", schema, checker);
        for (int i = 1; i < list.size(); i++) {
            assertTrue(list.get(i - 1).nodeId().compareTo(list.get(i).nodeId()) < 0,
                    list.get(i - 1).path() + " before " + list.get(i).path());
        }
    }

    @Test
    public void deferredElementDrainedAfterItsEnd() {
        final TypeAssigner assigner = new TypeAssigner(schema, checker);
        final java.util.function.Function<String, String> ns = p -> "t".equals(p) ? NS : null;
        final NodeId root = NodeId.ROOT_NODE;
        final NodeId trade = root.newChild();
        final NodeId tradeDate = trade.newChild();
        assigner.startElement(root, new QName("trades", NS, "t"), ns);
        assigner.startElement(trade, new QName("trade", NS, "t"), ns);
        assigner.startElement(tradeDate, new QName("tradeDate", NS, "t"), ns);
        assigner.characters("2026-");
        // trades and trade are complete; tradeDate awaits its value check
        assertEquals(List.of("/t:trades[1]", "/t:trades[1]/t:trade[1]"),
                assigner.drain().stream().map(Assignment::path).toList());
        assigner.characters("03-02");
        assertEquals(List.of(), assigner.drain());
        assigner.endElement();
        final List<Assignment> drained = assigner.drain();
        assertEquals(1, drained.size());
        assertEquals(tradeDate, drained.get(0).nodeId());
        assertEquals(ANNOTATED, drained.get(0).status());
        assertEquals(code("AdjustableDate"), drained.get(0).typeCode());
        assigner.endElement();
        assigner.endElement();
        assertEquals(List.of(), assigner.drain());
    }

    // --- xsi:type ---

    @Test
    public void xsiTypeWithAncestorPrefix() throws Exception {
        // xmlns:t is declared only on the root element
        final Map<String, Assignment> doc = trade("<t:swap xsi:type='t:VanillaSwap'/>");
        assertType(doc, "/t:trade/t:swap", code("VanillaSwap"));
        assertNone(doc, "/t:trade/t:swap/@xsi:type");
        // a prefix declared on the element itself
        final Map<String, Assignment> local = trade("<t:swap xmlns:u='" + NS + "' xsi:type='u:VanillaSwap'/>");
        assertType(local, "/t:trade/t:swap", code("VanillaSwap"));
        // the abstract head never appears in an instance, even with an xsi:type
        assertUnassignable(trade("<t:product xsi:type='t:Swap'/>"), "/t:trade/t:product", "element t:product not allowed here");
    }

    @Test
    public void xsiTypeWithDefaultNamespace() throws Exception {
        final Map<String, Assignment> doc = trade("<swap xmlns='" + NS + "' xsi:type='VanillaSwap'/>");
        assertType(doc, "/t:trade/swap", code("VanillaSwap"));
    }

    @Test
    public void invalidXsiTypeDerivationIsUnassignable() throws Exception {
        final Map<String, Assignment> doc = trade("<t:swap xsi:type='t:Fra'><t:productType>x</t:productType></t:swap>");
        assertUnassignable(doc, "/t:trade/t:swap",
                "xsi:type t:Fra is not validly derived from Q{urn:example:trade}Swap");
        assertNoAssignmentsBelow(doc, "/t:trade/t:swap");
    }

    @Test
    public void unknownXsiTypeIsUnassignable() throws Exception {
        assertUnassignable(trade("<t:swap xsi:type='t:Nope'/>"), "/t:trade/t:swap", "unknown xsi:type t:Nope");
        assertUnassignable(trade("<t:swap xsi:type='zz:VanillaSwap'/>"), "/t:trade/t:swap", "unknown xsi:type zz:VanillaSwap");
    }

    @Test
    public void xsiTypeBlockedByDeclaration() throws Exception {
        final Map<String, Assignment> doc = misc("<m:blocked xsi:type='m:Derived'/><m:open xsi:type='m:Derived'/><m:open/>");
        assertUnassignable(doc, "/m:blocked", "xsi:type m:Derived is not validly derived from Q{urn:example:misc}Base");
        assertType(doc, "/m:open[1]", misc.typeCode(misc.namedType(new QName("Derived", MNS)).orElseThrow()));
        assertType(doc, "/m:open[2]", misc.typeCode(misc.namedType(new QName("Base", MNS)).orElseThrow()));
    }

    @Test
    public void abstractTypeIsUnassignable() throws Exception {
        assertUnassignable(misc("<m:abs/>"), "/m:abs", "abstract type Q{urn:example:misc}AbstractT");
    }

    @Test
    public void xsiTypeBuiltInRestriction() throws Exception {
        final Map<String, Assignment> doc = trade("<t:fee><t:amount xmlns:xs='http://www.w3.org/2001/XMLSchema' "
                + "xsi:type='xs:integer'>5</t:amount></t:fee>");
        assertType(doc, "/t:trade/t:fee/t:amount", Type.INTEGER);
    }

    // --- xsi:nil ---

    @Test
    public void nilledNillableElement() {
        final Assignment a = at("/t:trade/t:tradeId");
        assertTrue(a.nilled());
        assertEquals(Type.STRING, a.typeCode());
        assertEquals(ANNOTATED, a.status());
        assertNone(trade1, "/t:trade/t:tradeId/@xsi:nil");
        assertFalse(at("/t:trade/t:tradeDate").nilled());
    }

    @Test
    public void nilOnNonNillableIsUnassignable() throws Exception {
        assertUnassignable(trade("<t:tradeDate xsi:nil='true'/>"), "/t:trade/t:tradeDate",
                "xsi:nil on non-nillable element t:tradeDate");
        // xsi:nil="false" is harmless
        assertType(trade("<t:tradeDate xsi:nil='false'>2026-03-02</t:tradeDate>"), "/t:trade/t:tradeDate", code("AdjustableDate"));
    }

    @Test
    public void nilledElementWithContentIsUnassignable() throws Exception {
        assertUnassignable(trade("<t:tradeId xsi:nil='true'>abc</t:tradeId>"), "/t:trade/t:tradeId",
                "nilled element t:tradeId has content");
    }

    // --- wildcards ---

    @Test
    public void laxWildcardWithKnownGlobalIsTyped() throws Exception {
        final Map<String, Assignment> doc = misc("<m:lax><m:known>5</m:known></m:lax>");
        assertType(doc, "/m:lax/m:known", Type.INT);
    }

    @Test
    public void laxWildcardUnknownEmitsNothing() throws Exception {
        final Map<String, Assignment> doc = misc("<m:lax><m:unknown a='1'><m:known>1</m:known></m:unknown></m:lax>");
        assertStatus(doc, "/m:lax", ANNOTATED);
        assertNone(doc, "/m:lax/m:unknown");
        assertNoAssignmentsBelow(doc, "/m:lax");

        final Map<String, Assignment> trade = trade("<t:extension><x:foo xmlns:x='urn:other'><x:bar/></x:foo></t:extension>");
        assertStatus(trade, "/t:trade/t:extension", ANNOTATED);
        assertNoAssignmentsBelow(trade, "/t:trade/t:extension");
    }

    @Test
    public void skipWildcardSubtreeEmitsNothing() throws Exception {
        final Map<String, Assignment> doc = trade("<t:opaque><t:trade><t:bogus/></t:trade><x:y xmlns:x='urn:other'/></t:opaque>"
                + "<t:fixingDates>2026-01-01</t:fixingDates>");
        assertStatus(doc, "/t:trade/t:opaque", ANNOTATED);
        assertNoAssignmentsBelow(doc, "/t:trade/t:opaque");
        // assignment resumes after the skipped subtree
        assertType(doc, "/t:trade/t:fixingDates", code("DateList"));
    }

    @Test
    public void strictWildcard() throws Exception {
        final Map<String, Assignment> doc = misc("<m:strict><m:known>3</m:known><m:unknown><m:known>1</m:known></m:unknown></m:strict>");
        assertType(doc, "/m:strict/m:known", Type.INT);
        assertUnassignable(doc, "/m:strict/m:unknown", "element m:unknown not allowed here");
        assertNoAssignmentsBelow(doc, "/m:strict/m:unknown");
    }

    // --- unassignable subtrees ---

    @Test
    public void unknownChildMakesSubtreeUnassignable() throws Exception {
        assertUnassignable(bad, "/t:trade/t:bogus", "element t:bogus not allowed here");
        assertNoAssignmentsBelow(bad, "/t:trade/t:bogus");
        // siblings and the rest of the document are unaffected
        assertType(bad, "/t:trade/t:fixingDates", code("DateList"));
        assertType(bad, "/t:trades/t:trade[3]/t:vanillaSwap", code("VanillaSwap"));

        final Map<String, Assignment> doc = trade("<t:bogus id='1'><t:notional><t:amount>1</t:amount></t:notional></t:bogus>");
        assertUnassignable(doc, "/t:trade/t:bogus", "element t:bogus not allowed here");
        assertNoAssignmentsBelow(doc, "/t:trade/t:bogus");
    }

    @Test
    public void unknownRootIsUnassignable() throws Exception {
        final Map<String, Assignment> doc = runXml("<t:nope xmlns:t='" + NS + "'><t:trade/></t:nope>", schema, checker);
        assertUnassignable(doc, "/t:nope", "no global element declaration for t:nope");
        assertEquals(1, doc.size());
    }

    @Test
    public void positionsCountSameNameSiblings() throws Exception {
        final Map<String, Assignment> doc = trade("<t:party id='a'/><t:party id='b'/><t:partyReference href='b'/>");
        assertType(doc, "/t:trade/t:party[1]/@id", Type.ID);
        assertType(doc, "/t:trade/t:party[2]/@id", Type.ID);
        assertType(doc, "/t:trade/t:partyReference[1]/@href", Type.IDREF);
    }

    @Test
    public void elementChildOfSimpleTypeIsUnassignable() throws Exception {
        final Map<String, Assignment> doc = trade("<t:fee><t:amount>5<t:x/></t:amount></t:fee>");
        assertUnassignable(doc, "/t:trade/t:fee/t:amount", "element content not allowed in xs:decimal");
        assertNoAssignmentsBelow(doc, "/t:trade/t:fee/t:amount");
    }

    // --- attributes ---

    @Test
    public void attributesTyped() {
        assertType("/t:trade/t:party[1]/@id", Type.ID);
        assertType("/t:trade/t:partyReference/@href", Type.IDREF);
    }

    @Test
    public void unknownAttributeIsUnassignableOnly() throws Exception {
        final Map<String, Assignment> doc = trade("<t:party id='p' foo='x'/>");
        assertStatus(doc, "/t:trade/t:party", ANNOTATED);
        assertType(doc, "/t:trade/t:party/@id", Type.ID);
        assertUnassignable(doc, "/t:trade/t:party/@foo", "attribute foo not allowed");
    }

    @Test
    public void invalidAttributeValueIsUnassignableOnly() throws Exception {
        final Map<String, Assignment> doc = trade("<t:party id='1bad'/>");
        assertStatus(doc, "/t:trade/t:party", ANNOTATED);
        assertUnassignable(doc, "/t:trade/t:party/@id", "invalid value for xs:ID");
        // not checked without a checker
        assertType(trade("<t:party id='1bad'/>", null), "/t:trade/t:party/@id", Type.ID);
    }

    @Test
    public void attributeWildcard() throws Exception {
        final Map<String, Assignment> doc = misc("<m:attrs xmlns:o='urn:o' n='1' o:x='y' bad='z'/>");
        assertType(doc, "/m:attrs/@n", Type.INT);
        assertNone(doc, "/m:attrs/@o:x");
        assertUnassignable(doc, "/m:attrs/@bad", "attribute bad not allowed");
    }

    @Test
    public void attributeOnSimpleTypedElementIsUnassignable() throws Exception {
        final Map<String, Assignment> doc = trade("<t:fee><t:amount unit='x'>5</t:amount></t:fee>");
        assertType(doc, "/t:trade/t:fee/t:amount", Type.DECIMAL);
        assertUnassignable(doc, "/t:trade/t:fee/t:amount/@unit", "attribute unit not allowed");
    }

    // --- value checks ---

    @Test
    public void invalidValueUnassignable() throws Exception {
        final Map<String, Assignment> doc = trade("<t:notional><t:amount>-5</t:amount><t:currency>usd</t:currency></t:notional>");
        assertUnassignable(doc, "/t:trade/t:notional/t:amount", "invalid value for Q{urn:example:trade}NonNegativeDecimal");
        assertUnassignable(doc, "/t:trade/t:notional/t:currency", "invalid value for Q{urn:example:trade}CurrencyCode");
        assertStatus(doc, "/t:trade/t:notional", ANNOTATED);
    }

    @Test
    public void invalidSimpleContentDropsAttributes() throws Exception {
        final Map<String, Assignment> doc = trade("<t:tradeDate id='d1'>not-a-date</t:tradeDate>");
        assertUnassignable(doc, "/t:trade/t:tradeDate", "invalid value for Q{urn:example:trade}AdjustableDate");
        assertNone(doc, "/t:trade/t:tradeDate/@id");
    }

    @Test
    public void checkerNullSkipsValueChecks() throws Exception {
        final Map<String, Assignment> doc = trade("<t:notional><t:amount>-5</t:amount><t:currency>usd</t:currency></t:notional>",
                null);
        assertType(doc, "/t:trade/t:notional/t:amount", code("NonNegativeDecimal"));
        assertType(doc, "/t:trade/t:notional/t:currency", code("CurrencyCode"));
    }

    @Test
    public void valueSplitAcrossCharacterEvents() throws Exception {
        final TypeAssigner assigner = new TypeAssigner(schema, checker);
        final java.util.function.Function<String, String> ns = p -> "t".equals(p) ? NS : null;
        final NodeId root = NodeId.ROOT_NODE;
        final NodeId trade = root.newChild();
        final NodeId notional = trade.newChild();
        final NodeId amount = notional.newChild();
        assigner.startElement(root, new QName("trades", NS, "t"), ns);
        assigner.startElement(trade, new QName("trade", NS, "t"), ns);
        assigner.startElement(notional, new QName("notional", NS, "t"), ns);
        assigner.startElement(amount, new QName("amount", NS, "t"), ns);
        assigner.characters("-");
        assigner.characters("5");
        assigner.endElement();
        final List<Assignment> drained = assigner.drain();
        final Assignment last = drained.get(drained.size() - 1);
        assertEquals(amount, last.nodeId());
        assertEquals(UNASSIGNABLE, last.status());
    }
}
