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

import org.exist.dom.QName;
import org.exist.xquery.value.Type;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ValueCheckerTest {

    private static final String NS = "urn:example:trade";

    private static CompiledSchema schema;
    private static ValueChecker checker;
    private static int nnd;
    private static int currency;
    private static int dateList;
    private static int dateOrDuration;
    private static int adjustableDate;

    @BeforeAll
    static void load() {
        schema = CompiledSchema.compile(new SchemaKey("value-checker-test"), CompiledSchemaTest.loadModel("trade.xsd"));
        checker = new ValueChecker(schema);
        nnd = code("NonNegativeDecimal");
        currency = code("CurrencyCode");
        dateList = code("DateList");
        dateOrDuration = code("DateOrDuration");
        adjustableDate = code("AdjustableDate");
    }

    private static int code(final String local) {
        return schema.typeCode(schema.namedType(new QName(local, NS)).orElseThrow());
    }

    @Test
    public void facetsChecked() {
        assertFalse(checker.isValid(nnd, "-1"));
        assertTrue(checker.isValid(nnd, "0"));
        assertFalse(checker.isValid(currency, "usd"));
        assertTrue(checker.isValid(currency, "USD"));
        assertTrue(checker.isValid(currency, " USD "));
    }

    @Test
    public void primitiveParseChecked() {
        assertFalse(checker.isValid(nnd, "abc"));
        assertTrue(checker.isValid(Type.INTEGER, "12"));
        assertFalse(checker.isValid(Type.INTEGER, "1.5"));
        assertTrue(checker.isValid(Type.DATE, "2026-01-01"));
        assertFalse(checker.isValid(Type.DATE, "2026-13-01"));
        assertTrue(checker.isValid(Type.STRING, " anything "));
    }

    @Test
    public void listsAndUnionsChecked() {
        assertTrue(checker.isValid(dateList, "2026-01-01 2026-02-01"));
        assertFalse(checker.isValid(dateList, "2026-01-01 nope"));
        assertTrue(checker.isValid(dateOrDuration, "P1D"));
        assertTrue(checker.isValid(dateOrDuration, "2026-01-01"));
        assertFalse(checker.isValid(dateOrDuration, "x"));
    }

    @Test
    public void simpleContentChecked() {
        assertTrue(checker.isValid(adjustableDate, "2026-01-01"));
        assertFalse(checker.isValid(adjustableDate, "tomorrow"));
    }

    @Test
    public void cachedResultIsStable() {
        for (int i = 0; i < 3; i++) {
            assertFalse(checker.isValid(nnd, "-1"));
            assertTrue(checker.isValid(nnd, "1"));
        }
    }
}
