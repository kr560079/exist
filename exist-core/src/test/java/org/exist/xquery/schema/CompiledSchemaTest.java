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
package org.exist.xquery.schema;

import org.apache.xerces.impl.xs.XMLSchemaLoader;
import org.apache.xerces.xs.*;
import org.exist.dom.QName;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.UserDefinedType;
import org.exist.xquery.value.UserTypeRegistry;
import org.exist.xquery.value.Variety;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.net.URL;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.apache.xerces.xs.XSWildcard.PC_LAX;
import static org.apache.xerces.xs.XSWildcard.PC_SKIP;
import static org.exist.xquery.schema.ChildResolution.Kind.DECLARED;
import static org.exist.xquery.schema.ChildResolution.Kind.NOT_ALLOWED;
import static org.exist.xquery.schema.ChildResolution.Kind.WILDCARD;
import static org.junit.jupiter.api.Assertions.*;

public class CompiledSchemaTest {

    private static final String NS = "urn:example:trade";
    private static final String UNS = "urn:example:unqualified";
    private static final String FIXTURES = "fixtures/";

    private static XSModel model;
    private static CompiledSchema schema;
    private static XSComplexTypeDefinition tradeType;

    @BeforeAll
    static void load() {
        model = loadModel("trade.xsd");
        schema = CompiledSchema.compile(new SchemaKey("test"), model);
        tradeType = (XSComplexTypeDefinition) model.getElementDeclaration("trade", NS).getTypeDefinition();
    }

    static XSModel loadModel(final String name) {
        final URL url = CompiledSchemaTest.class.getResource(FIXTURES + name);
        assertNotNull(url, "missing fixture " + name);
        final XSModel m = new XMLSchemaLoader().loadURI(url.toString());
        assertNotNull(m, "could not load " + name);
        return m;
    }

    // --- helpers ---

    private static XSElementDeclaration childOf(final XSComplexTypeDefinition parent, final String local) {
        final ChildResolution r = schema.resolveChild(parent, new QName(local, NS));
        assertEquals(DECLARED, r.kind(), local);
        assertNotNull(r.decl(), local);
        return r.decl();
    }

    private static XSComplexTypeDefinition tradeChildType(final String local) {
        return (XSComplexTypeDefinition) childOf(tradeType, local).getTypeDefinition();
    }

    private static XSTypeDefinition typeOfChild(final String parent, final String child) {
        return childOf(tradeChildType(parent), child).getTypeDefinition();
    }

    private static ChildResolution resolve(final String tradeChild, final QName child) {
        return schema.resolveChild(tradeChildType(tradeChild), child);
    }

    private static XSTypeDefinition named(final String local) {
        return model.getTypeDefinition(local, NS);
    }

    private static int code(final String local) {
        return schema.typeCode(named(local));
    }

    private static XSElementDeclaration head(final String local) {
        return model.getElementDeclaration(local, NS);
    }

    private static Set<String> names(final Set<XSElementDeclaration> decls) {
        return decls.stream().map(XSObject::getName).collect(Collectors.toSet());
    }

    // --- fixtures ---

    @Test
    public void fixturesValidateAsExpected() throws Exception {
        final SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        final Validator validator = factory.newSchema(CompiledSchemaTest.class.getResource(FIXTURES + "trade.xsd")).newValidator();
        validator.validate(new StreamSource(CompiledSchemaTest.class.getResource(FIXTURES + "trade-1.xml").toString()));
        assertThrows(SAXException.class, () ->
                validator.validate(new StreamSource(CompiledSchemaTest.class.getResource(FIXTURES + "bad.xml").toString())));
    }

    // --- global lookups ---

    @Test
    public void globalLookups() {
        assertEquals(Set.of(NS), schema.targetNamespaces());
        assertSame(head("trade"), schema.globalElement(new QName("trade", NS)).orElseThrow());
        assertTrue(schema.globalElement(new QName("amount", NS)).isEmpty());
        assertTrue(schema.globalElement(new QName("trade", "")).isEmpty());
        assertSame(named("VanillaSwap"), schema.namedType(new QName("VanillaSwap", NS)).orElseThrow());
        // included from trade-common.xsd
        assertSame(named("CurrencyCode"), schema.namedType(new QName("CurrencyCode", NS)).orElseThrow());
        assertTrue(schema.globalAttribute(new QName("id", "")).isEmpty());
        assertEquals(new SchemaKey("test"), schema.key());
    }

    // --- child resolution ---

    @Test
    public void sameLocalNameDifferentParentsHaveDifferentTypes() {
        assertEquals("NonNegativeDecimal", typeOfChild("notional", "amount").getName());
        assertEquals(Type.DECIMAL, schema.typeCode(typeOfChild("fee", "amount")));
    }

    @Test
    public void localChildResolvesToItsDeclaration() {
        final ChildResolution r = schema.resolveChild(tradeType, new QName("tradeId", NS));
        assertEquals(DECLARED, r.kind());
        assertTrue(r.decl().getNillable());
        assertEquals(Type.STRING, schema.typeCode(r.decl().getTypeDefinition()));
        assertEquals(NOT_ALLOWED, schema.resolveChild(tradeType, new QName("bogus", NS)).kind());
        // qualified schema: an unqualified name does not match
        assertEquals(NOT_ALLOWED, schema.resolveChild(tradeType, new QName("tradeId", "")).kind());
    }

    @Test
    public void substitutionChainResolvesUnderAbstractHead() {
        final ChildResolution r = schema.resolveChild(tradeType, new QName("vanillaSwap", NS));
        assertEquals(DECLARED, r.kind());
        assertEquals("VanillaSwap", r.decl().getTypeDefinition().getName());
        assertEquals("Swap", schema.resolveChild(tradeType, new QName("swap", NS)).decl().getTypeDefinition().getName());
        assertEquals("Fra", schema.resolveChild(tradeType, new QName("fra", NS)).decl().getTypeDefinition().getName());
    }

    @Test
    public void abstractHeadItselfNotAllowedAsChild() {
        assertEquals(NOT_ALLOWED, schema.resolveChild(tradeType, new QName("product", NS)).kind());
    }

    @Test
    public void blockedSubstitutionNotAllowed() {
        final XSComplexTypeDefinition legs = (XSComplexTypeDefinition) childOf(
                (XSComplexTypeDefinition) named("Fra"), "legs").getTypeDefinition();
        assertEquals(DECLARED, schema.resolveChild(legs, new QName("blockedSwap", NS)).kind());
        // blockedSwapMember is in blockedSwap's substitution group, but blockedSwap blocks substitution
        assertEquals(NOT_ALLOWED, schema.resolveChild(legs, new QName("blockedSwapMember", NS)).kind());
        assertEquals(NOT_ALLOWED, schema.resolveChild(legs, new QName("vanillaSwap", NS)).kind());
        assertEquals(Set.of("blockedSwap"), names(schema.substitutionMembers(head("blockedSwap"))));
    }

    @Test
    public void laxAndSkipWildcards() {
        final QName foreign = new QName("foo", "urn:example:foreign");
        final ChildResolution lax = resolve("extension", foreign);
        assertEquals(WILDCARD, lax.kind());
        assertEquals(PC_LAX, lax.processContents());
        assertNull(lax.decl());
        final ChildResolution skip = resolve("opaque", foreign);
        assertEquals(WILDCARD, skip.kind());
        assertEquals(PC_SKIP, skip.processContents());
        assertNull(skip.decl());
        // ##other excludes the target namespace
        assertEquals(NOT_ALLOWED, resolve("extension", new QName("tradeId", NS)).kind());
        // ##any includes the target namespace; skip never looks up declarations
        final ChildResolution skipOwn = resolve("opaque", new QName("trade", NS));
        assertEquals(WILDCARD, skipOwn.kind());
        assertNull(skipOwn.decl());
    }

    @Test
    public void unqualifiedLocalElement() {
        final XSModel um = loadModel("unqualified.xsd");
        final CompiledSchema us = CompiledSchema.compile(new SchemaKey("test-unqualified"), um);
        final XSComplexTypeDefinition rootType = (XSComplexTypeDefinition) um.getElementDeclaration("root", UNS).getTypeDefinition();
        final ChildResolution r = us.resolveChild(rootType, new QName("item", ""));
        assertEquals(DECLARED, r.kind());
        assertEquals(Type.INTEGER, us.typeCode(r.decl().getTypeDefinition()));
        assertEquals(NOT_ALLOWED, us.resolveChild(rootType, new QName("item", UNS)).kind());
        assertEquals(Set.of(UNS), us.targetNamespaces());
    }

    @Test
    public void simpleAndEmptyContentAllowNoChildren() {
        assertEquals(NOT_ALLOWED, schema.resolveChild((XSComplexTypeDefinition) named("AdjustableDate"), new QName("x", NS)).kind());
        assertEquals(NOT_ALLOWED, resolve("party", new QName("x", NS)).kind());
    }

    @Test
    public void concurrentResolutionIsConsistent() throws Exception {
        final CompiledSchema fresh = CompiledSchema.compile(new SchemaKey("test"), model);
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            final List<Callable<String>> tasks = IntStream.range(0, 64).<Callable<String>>mapToObj(i -> () ->
                    fresh.resolveChild(tradeType, new QName("vanillaSwap", NS)).decl().getName()).toList();
            for (final Future<String> f : pool.invokeAll(tasks)) {
                assertEquals("vanillaSwap", f.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // --- attributes ---

    @Test
    public void attributeUses() {
        final XSAttributeDeclaration id = schema.resolveAttribute(tradeChildType("party"), new QName("id", "")).orElseThrow();
        assertEquals(Type.ID, schema.typeCode(id.getTypeDefinition()));
        final XSAttributeDeclaration href = schema.resolveAttribute(tradeChildType("partyReference"), new QName("href", "")).orElseThrow();
        assertEquals(Type.IDREF, schema.typeCode(href.getTypeDefinition()));
        assertTrue(schema.resolveAttribute(tradeChildType("party"), new QName("href", "")).isEmpty());
        // inherited attribute use
        assertTrue(schema.resolveAttribute((XSComplexTypeDefinition) named("VanillaSwap"), new QName("id", "")).isPresent());
        assertTrue(schema.resolveAttribute((XSComplexTypeDefinition) named("AdjustableDate"), new QName("id", "")).isPresent());
    }

    // --- substitution groups ---

    @Test
    public void substitutionMembersTransitive() {
        assertEquals(Set.of("swap", "fra", "vanillaSwap"), names(schema.substitutionMembers(head("product"))));
        assertEquals(Set.of("swap", "vanillaSwap"), names(schema.substitutionMembers(head("swap"))));
        assertEquals(Set.of("trade"), names(schema.substitutionMembers(head("trade"))));
    }

    // --- type codes ---

    @Test
    public void builtInTypeCodes() {
        assertEquals(Type.ANY_TYPE, schema.typeCode(model.getTypeDefinition("anyType", XMLConstants.W3C_XML_SCHEMA_NS_URI)));
        assertEquals(Type.ANY_SIMPLE_TYPE, schema.typeCode(model.getTypeDefinition("anySimpleType", XMLConstants.W3C_XML_SCHEMA_NS_URI)));
        assertEquals(Type.DATE, schema.typeCode(model.getTypeDefinition("date", XMLConstants.W3C_XML_SCHEMA_NS_URI)));
        assertEquals(Type.NMTOKENS, schema.typeCode(model.getTypeDefinition("NMTOKENS", XMLConstants.W3C_XML_SCHEMA_NS_URI)));
    }

    @Test
    public void derivedTypeCodesAreSubtypes() {
        assertTrue(UserTypeRegistry.isUserType(code("VanillaSwap")));
        assertTrue(Type.subTypeOf(code("VanillaSwap"), code("Product")));
        assertTrue(Type.subTypeOf(code("VanillaSwap"), code("Swap")));
        assertTrue(Type.subTypeOf(code("Fra"), code("Product")));
        assertFalse(Type.subTypeOf(code("Fra"), code("Swap")));
        assertFalse(Type.subTypeOf(code("Product"), code("Swap")));
        assertTrue(Type.subTypeOf(code("Product"), Type.ANY_TYPE));
        assertEquals(code("VanillaSwap"), code("VanillaSwap"));
        assertEquals("Q{" + NS + "}VanillaSwap", Type.getTypeName(code("VanillaSwap")));
    }

    @Test
    public void simpleUserTypes() {
        final UserDefinedType nnd = UserTypeRegistry.get(code("NonNegativeDecimal"));
        assertEquals(Variety.ATOMIC, nnd.variety());
        assertEquals(Type.DECIMAL, nnd.baseCode());
        assertEquals(Type.DECIMAL, Type.primitiveTypeOf(nnd.code()));
        assertTrue(Type.subTypeOf(nnd.code(), Type.DECIMAL));
        assertTrue(Type.subTypeOf(code("CurrencyCode"), Type.TOKEN));
        assertEquals(Type.STRING, Type.primitiveTypeOf(code("CurrencyCode")));

        final UserDefinedType list = UserTypeRegistry.get(code("DateList"));
        assertEquals(Variety.LIST, list.variety());
        assertArrayEquals(new int[]{Type.DATE}, list.memberCodes());

        final UserDefinedType union = UserTypeRegistry.get(code("DateOrDuration"));
        assertEquals(Variety.UNION, union.variety());
        assertArrayEquals(new int[]{Type.DATE, Type.DURATION}, union.memberCodes());
        assertTrue(Type.subTypeOf(Type.DATE, union.code()));
        assertFalse(Type.subTypeOf(Type.STRING, union.code()));
    }

    @Test
    public void complexUserTypes() {
        final UserDefinedType adj = UserTypeRegistry.get(code("AdjustableDate"));
        assertEquals(Variety.COMPLEX_SIMPLE_CONTENT, adj.variety());
        assertArrayEquals(new int[]{Type.DATE}, adj.memberCodes());
        assertEquals(Type.DATE, adj.primitiveCode());
        assertEquals(Variety.COMPLEX_ELEMENT_ONLY, UserTypeRegistry.get(code("Product")).variety());
        assertEquals(Variety.COMPLEX_MIXED, UserTypeRegistry.get(schema.typeCode(tradeChildType("description"))).variety());
        assertEquals(Variety.COMPLEX_EMPTY, UserTypeRegistry.get(schema.typeCode(tradeChildType("party"))).variety());
        assertSame(named("AdjustableDate"), adj.definition());
        assertEquals(new SchemaKey("test"), adj.schema());
    }

    @Test
    public void anonymousTypesGetDistinctStableIds() {
        final int notional = schema.typeCode(tradeChildType("notional"));
        final int fee = schema.typeCode(tradeChildType("fee"));
        assertNotEquals(notional, fee);
        final UserDefinedType n = UserTypeRegistry.get(notional);
        assertNull(n.name());
        assertTrue(n.anonymousId().startsWith("#anon:"), n.anonymousId());
        assertEquals(n.anonymousId(), Type.getTypeName(notional));
        // a second compile of the same schema (same key) yields the same codes
        final CompiledSchema again = CompiledSchema.compile(new SchemaKey("test"), model);
        assertEquals(notional, again.typeCode(tradeChildType("notional")));
        assertEquals(code("VanillaSwap"), again.typeCode(named("VanillaSwap")));
    }
}
