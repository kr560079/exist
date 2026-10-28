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

import org.apache.xerces.xs.XSModel;
import org.exist.EXistException;
import org.exist.dom.QName;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.test.ExistEmbeddedServer;
import org.exist.xquery.CompiledXQuery;
import org.exist.xquery.ErrorCodes;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQuery;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.value.AtomicValue;
import org.exist.xquery.value.BooleanValue;
import org.exist.xquery.value.DecimalValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.UserDefinedAtomicValue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class TypedValuesTest {

    private static final String NS = "urn:example:trade";

    @RegisterExtension
    static final ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(true, true);

    private static CompiledSchema schema;
    private static int nnd;
    private static int currency;
    private static int dateList;
    private static int dateOrDuration;
    private static int productCode;
    private static int adjustableDate;

    @BeforeAll
    static void load() {
        final XSModel model = CompiledSchemaTest.loadModel("trade.xsd");
        schema = CompiledSchema.compile(new SchemaKey("typed-values-test"), model);
        nnd = code("NonNegativeDecimal");
        currency = code("CurrencyCode");
        dateList = code("DateList");
        dateOrDuration = code("DateOrDuration");
        productCode = code("Product");
        adjustableDate = code("AdjustableDate");
    }

    static int code(final String local) {
        return schema.typeCode(schema.namedType(new QName(local, NS)).orElseThrow());
    }

    private static int typeOf(final Sequence seq) {
        assertEquals(1, seq.getItemCount());
        return seq.itemAt(0).getType();
    }

    @Test
    public void userAtomicKeepsTypeAndUnwraps() throws XPathException {
        final AtomicValue v = (AtomicValue) TypedValues.fromLexical("5.0", nnd, null).itemAt(0);
        assertEquals(nnd, v.getType());
        assertEquals(Type.DECIMAL, v.unwrapUserType().getType());
    }

    @Test
    public void userAtomicDerivedFromStringKeepsType() throws XPathException {
        final AtomicValue v = (AtomicValue) TypedValues.fromLexical("USD", currency, null).itemAt(0);
        assertEquals(currency, v.getType());
        assertEquals(Type.TOKEN, v.unwrapUserType().getType());
        assertEquals("USD", v.getStringValue());
    }

    @Test
    public void builtInAtomicCastsFromUntyped() throws XPathException {
        assertEquals(Type.INTEGER, typeOf(TypedValues.fromLexical(" 42 ", Type.INTEGER, null)));
        assertEquals(Type.DATE, typeOf(TypedValues.fromLexical("2026-01-01", Type.DATE, null)));
    }

    @Test
    public void listSplitsOnWhitespace() throws XPathException {
        final Sequence seq = TypedValues.fromLexical(" 2026-01-01  2026-02-01 ", dateList, null);
        assertEquals(2, seq.getItemCount());
        assertEquals(Type.DATE, seq.itemAt(0).getType());
        assertEquals("2026-02-01", seq.itemAt(1).getStringValue());
    }

    @Test
    public void emptyListIsEmptySequence() throws XPathException {
        assertTrue(TypedValues.fromLexical("   ", dateList, null).isEmpty());
    }

    @Test
    public void unionPicksFirstAcceptingMember() throws XPathException {
        assertEquals(Type.DURATION, typeOf(TypedValues.fromLexical("P1D", dateOrDuration, null)));
        assertEquals(Type.DATE, typeOf(TypedValues.fromLexical("2026-01-01", dateOrDuration, null)));
    }

    @Test
    public void unionWithNoAcceptingMemberFails() {
        final XPathException e = assertThrows(XPathException.class,
                () -> TypedValues.fromLexical("not-a-date", dateOrDuration, null));
        assertEquals(ErrorCodes.FORG0001, e.getErrorCode());
    }

    @Test
    public void simpleContentUsesSimpleType() throws XPathException {
        assertEquals(Type.DATE, typeOf(TypedValues.fromLexical("2026-03-01", adjustableDate, null)));
    }

    @Test
    public void elementOnlyRaisesFOTY0012() {
        final XPathException e = assertThrows(XPathException.class,
                () -> TypedValues.fromLexical("x", productCode, null));
        assertEquals(ErrorCodes.FOTY0012, e.getErrorCode());
    }

    @Test
    public void untypedAndAnyTypeAreUntypedAtomic() throws XPathException {
        assertEquals(Type.UNTYPED_ATOMIC, typeOf(TypedValues.fromLexical("x", Type.UNTYPED, null)));
        assertEquals(Type.UNTYPED_ATOMIC, typeOf(TypedValues.fromLexical("x", Type.ANY_TYPE, null)));
        assertEquals(Type.UNTYPED_ATOMIC, typeOf(TypedValues.fromLexical("x", Type.UNTYPED_ATOMIC, null)));
    }

    // Review Focus 4
    @Test
    public void collapseWhitespaceForDecimal() throws XPathException {
        assertEquals("100.5", TypedValues.fromLexical(" 100.50 ", nnd, null).getStringValue());
        assertTrue(new ValueChecker(schema).isValid(nnd, " 100.50 "));
    }

    @Test
    public void stringPreservesWhitespace() throws XPathException {
        assertEquals(" a ", TypedValues.fromLexical(" a ", Type.STRING, null).getStringValue());
    }

    // Review Focus 1
    @Test
    public void arithmeticOnUserTypeYieldsDecimal() throws Exception {
        final String prolog = "declare variable $v external; ";
        for (final String expr : new String[]{"$v * 2", "abs($v)", "round($v)", "sum(($v, $v))"}) {
            assertEquals(Type.DECIMAL, typeOf(evalWithUserValue(prolog + expr)), expr);
        }
        assertEquals("10", evalWithUserValue(prolog + "$v * 2").getStringValue());

        // every operation yields what it yields for the xs:decimal base value
        for (final String expr : new String[]{
                "$v * 2", "$v + $v", "$v - 1", "$v div 2", "$v idiv 2", "$v mod 2", "-$v", "+$v",
                "abs($v)", "round($v)", "round($v, 1)", "round-half-to-even($v)", "floor($v)", "ceiling($v)",
                "sum(($v, $v))", "avg(($v, $v))", "min(($v, 7))", "max(($v, 1))",
                "declare function local:f($x as xs:decimal) { $x * 2 }; local:f($v)"}) {
            final Sequence result = evalWithUserValue(prolog + expr);
            final Sequence expected = eval(prolog + expr, new DecimalValue(new BigDecimal("5")));
            assertEquals(1, result.getItemCount(), expr);
            assertEquals(expected.itemAt(0).getType(), result.itemAt(0).getType(), expr);
            assertEquals(expected.getStringValue(), result.getStringValue(), expr);
        }
    }

    @Test
    public void comparisonsAndFunctionsOnUserType() throws Exception {
        final String prolog = "declare variable $v external; ";
        for (final String expr : new String[]{
                "$v eq 5", "$v = 5", "5 = $v", "$v lt 6.0", "$v = xs:untypedAtomic('5')", "$v = $v",
                "$v instance of xs:decimal", "string($v) = '5'", "format-number($v, '0.0') = '5.0'",
                "number($v) = 5e0", "xs:decimal($v) eq 5", "$v cast as xs:double eq 5e0",
                "deep-equal($v, 5.0)", "count(distinct-values(($v, 5.0))) = 1", "boolean($v)",
                "(1 to 10)[$v] eq 5", "count(1 to xs:integer($v)) eq 5", "index-of((1, 5, 7), $v) eq 2",
                "sort((9, $v, 1))[2] eq 5", "(for $x in (9, $v, 1) order by $x return $x)[2] eq 5",
                "map { $v : 'a' }(5) eq 'a'", "map { 5 : 'a' }($v) eq 'a'",
                "count(subsequence((1, 2, 3, 4, 5, 6), $v)) eq 2", "substring('abcdefg', $v) eq 'efg'",
                "string-join(($v, 'x')) eq '5x'", "$v castable as xs:integer", "sum(($v, 1.5)) eq 6.5",
                "Q{http://www.w3.org/2005/xpath-functions/math}sqrt($v) gt 2",
                "count(for $x in ($v, 5.0) group by $k := $x return $k) eq 1"}) {
            final Sequence result = evalWithUserValue(prolog + expr);
            assertEquals(1, result.getItemCount(), expr);
            assertEquals(BooleanValue.TRUE, result.itemAt(0), expr);
        }
    }

    private static Sequence evalWithUserValue(final String query)
            throws EXistException, XPathException, PermissionDeniedException {
        return eval(query, new UserDefinedAtomicValue(null, nnd, new DecimalValue(new BigDecimal("5"))));
    }

    private static Sequence eval(final String query, final AtomicValue v)
            throws EXistException, XPathException, PermissionDeniedException {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        final XQuery xquery = pool.getXQueryService();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            final XQueryContext context = new XQueryContext(pool);
            context.declareVariable("v", v);
            final CompiledXQuery compiled = xquery.compile(context, query);
            return xquery.execute(broker, compiled, null);
        }
    }
}
