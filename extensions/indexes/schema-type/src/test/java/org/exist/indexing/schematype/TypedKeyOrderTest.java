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

import org.exist.xquery.ValueComparison;
import org.exist.xquery.XPathException;
import org.exist.xquery.value.AtomicValue;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.UntypedAtomicValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The keys of the typed-value table sort as their values: for any two values of a family, the unsigned byte order of
 * their keys is their XQuery order (equal values, equal keys), and no key is a proper prefix of another.
 */
public class TypedKeyOrderTest {

    @Test
    public void decimalKeysSortNumerically() throws XPathException {
        assertTrue(compare(key("9.5"), key("10")) < 0);
        assertTrue(compare(key("-1"), key("0")) < 0);
        assertOrdered(Type.DECIMAL, "-123456789012345678901234567890.5", "-1000", "-999.999", "-10", "-9.5", "-1", "-0.5",
                "-0.000000000000000000001", "0", "0.000000000000000000001", "0.00012", "0.0012", "0.1", "0.5", "1",
                "1.0000000000000000000000001", "9", "9.5", "10", "10.01", "99", "100", "1000000",
                "99999999999999999999.99", "123456789012345678901234567890.123456789");
        assertEquals(0, compare(key("10"), key("10.000")));
        assertEquals(0, compare(key("-0"), key("0")));
        assertEquals(0, compare(key("0.50"), key(".5")));
    }

    @Test
    public void integerKeysAreDecimalKeys() throws XPathException {
        assertOrdered(Type.INTEGER, "-12345678901234567890", "-9223372036854775808", "-1", "0", "1", "9", "10",
                "9223372036854775807", "123456789012345678901234567890");
        assertEquals(0, compare(enc(Type.INTEGER, "10"), enc(Type.DECIMAL, "10.0")));
    }

    @Test
    public void doubleKeysSortNumerically() throws XPathException {
        assertOrdered(Type.DOUBLE, "-INF", "-1.7976931348623157E308", "-1e10", "-1.5", "-1", "-4.9E-324", "0",
                "4.9E-324", "1e-300", "0.1", "1", "1.5", "9.5", "10", "1e10", "1.7976931348623157E308", "INF");
        assertEquals(0, compare(enc(Type.DOUBLE, "-0"), enc(Type.DOUBLE, "0")));
        // floats are widened, which is exact
        assertEquals(0, compare(enc(Type.FLOAT, "1.5"), enc(Type.DOUBLE, "1.5")));
        assertOrdered(Type.FLOAT, "-INF", "-3.4028235E38", "-1.5", "0", "1.4E-45", "1.5", "3.4028235E38", "INF");
        // NaN has a key of its own, before every number
        final byte[] nan = enc(Type.DOUBLE, "NaN");
        assertEquals(TypedKeys.DOUBLE_NAN, nan[1]);
        assertTrue(compare(nan, enc(Type.DOUBLE, "-INF")) < 0);
        assertEquals(0, compare(nan, enc(Type.FLOAT, "NaN")));
    }

    @Test
    public void dateKeysSortChronologically() throws XPathException {
        assertOrdered(Type.DATE, "1969-12-31Z", "1970-01-01+14:00", "1970-01-01Z", "1999-12-31-05:00", "2000-01-01Z",
                "2026-01-01+01:00", "2026-01-01Z", "2026-01-01-14:00", "2026-03-02Z");
        // equal instants, equal keys
        assertEquals(0, compare(enc(Type.DATE, "2026-01-01+00:00"), enc(Type.DATE, "2026-01-01Z")));
        // values without timezone sort among themselves, in a range of their own
        assertOrdered(Type.DATE, "0001-01-01", "1600-02-29", "1969-12-31", "1970-01-01", "2026-01-01", "2026-01-02");
        assertEquals(TypedKeys.TZ_LESS, enc(Type.DATE, "2026-01-01")[1]);
        assertEquals(TypedKeys.TZ_AWARE, enc(Type.DATE, "2026-01-01Z")[1]);
    }

    @Test
    public void dateTimeKeysSortChronologically() throws XPathException {
        assertOrdered(Type.DATE_TIME, "1969-12-31T23:59:59.999Z", "1970-01-01T00:00:00Z", "1970-01-01T00:00:00.0001Z",
                "2026-01-01T10:00:00+14:00", "2025-12-31T23:00:00-10:00", "2026-01-01T10:00:00Z",
                "2026-01-01T10:00:00.5Z", "2026-01-01T10:00:01Z", "9999-12-31T23:59:59Z");
        assertEquals(0, compare(enc(Type.DATE_TIME, "2026-01-01T10:00:00+01:00"), enc(Type.DATE_TIME, "2026-01-01T09:00:00Z")));
        assertEquals(0, compare(enc(Type.DATE_TIME, "2026-01-01T10:00:00.500Z"), enc(Type.DATE_TIME, "2026-01-01T10:00:00.5Z")));
        assertOrdered(Type.DATE_TIME, "2026-01-01T00:00:00", "2026-01-01T09:59:59.9", "2026-01-01T10:00:00");
        assertOrdered(Type.TIME, "10:00:00+14:00", "00:00:00Z", "09:59:59Z", "10:00:00Z", "10:00:00-01:00", "23:59:59Z");
        assertOrdered(Type.TIME, "00:00:00", "12:00:00", "23:59:59.5");
    }

    @Test
    public void durationKeysSortByLength() throws XPathException {
        assertOrdered(Type.DAY_TIME_DURATION, "-P10D", "-P1D", "-PT1H", "-PT1S", "PT0S", "PT0.001S", "PT1S", "PT59M",
                "PT1H", "P1D", "P1DT1S", "P100D");
        assertEquals(0, compare(enc(Type.DAY_TIME_DURATION, "PT24H"), enc(Type.DAY_TIME_DURATION, "P1D")));
        assertOrdered(Type.YEAR_MONTH_DURATION, "-P2Y", "-P13M", "-P1Y", "-P1M", "P0M", "P1M", "P11M", "P1Y", "P1Y1M", "P100Y");
        assertEquals(0, compare(enc(Type.YEAR_MONTH_DURATION, "P12M"), enc(Type.YEAR_MONTH_DURATION, "P1Y")));
    }

    @Test
    public void stringKeysSortByCodepoint() throws XPathException {
        assertOrdered(Type.STRING, "", " ", "A", "Banana", "Z", "a", "aa", "apple", "apple pie", "b", "banana", "z", "é",
                "中", "�", "😀");
        assertOrdered(Type.TOKEN, "EUR", "GBP", "USD");
        // strings longer than the limit are truncated: they order with the others, but share a key
        final String a = "a".repeat(TypedKeys.MAX_STRING_BYTES);
        assertTrue(TypedKeys.isTruncatedString(a + "b"));
        assertEquals(0, compare(enc(Type.STRING, a + "b"), enc(Type.STRING, a + "c")));
        assertTrue(compare(enc(Type.STRING, a.substring(1)), enc(Type.STRING, a + "b")) < 0);
        assertTrue(compare(enc(Type.STRING, a + "b"), enc(Type.STRING, "b")) < 0);
        // a multi-byte character at the limit is not cut
        final byte[] cut = enc(Type.STRING, "a".repeat(TypedKeys.MAX_STRING_BYTES - 1) + "é");
        assertEquals(1 + TypedKeys.MAX_STRING_BYTES - 1 + 1, cut.length);
    }

    @Test
    public void familiesNeverOverlap() throws XPathException {
        final byte[][] keys = {
                enc(Type.DECIMAL, "1"), enc(Type.DOUBLE, "1"), enc(Type.STRING, "1"), enc(Type.ANY_URI, "1"),
                enc(Type.DATE, "2026-01-01"), enc(Type.DATE_TIME, "2026-01-01T00:00:00"), enc(Type.TIME, "00:00:00"),
                enc(Type.DAY_TIME_DURATION, "PT1S"), enc(Type.YEAR_MONTH_DURATION, "P1M")
        };
        for (int i = 0; i < keys.length; i++) {
            for (int j = 0; j < keys.length; j++) {
                if (i != j) {
                    assertNotEquals(keys[i][0], keys[j][0]);
                }
            }
        }
        assertNull(TypedKeys.encode(atomic(Type.BOOLEAN, "true")));
        assertNull(TypedKeys.encode(atomic(Type.DURATION, "P1Y1D")));
        assertNull(TypedKeys.encode(atomic(Type.G_YEAR, "2026")));
    }

    // --- helpers ---

    /**
     * Assert that the keys of the values, given in increasing order, increase, and that no key is a proper prefix
     * of another; also that the XQuery order of the values agrees.
     */
    private static void assertOrdered(final int type, final String... lexicals) throws XPathException {
        final List<byte[]> keys = new ArrayList<>();
        final List<AtomicValue> values = new ArrayList<>();
        for (final String lexical : lexicals) {
            values.add(atomic(type, lexical));
            keys.add(enc(type, lexical));
        }
        for (int i = 0; i < keys.size(); i++) {
            for (int j = 0; j < keys.size(); j++) {
                final int expected = Integer.signum(ValueComparison.compareAtomic(null, values.get(i), values.get(j)));
                assertEquals(Integer.signum(Integer.compare(i, j)), expected,
                        "the values are not in order: " + lexicals[i] + ", " + lexicals[j]);
                assertEquals(expected, Integer.signum(compare(keys.get(i), keys.get(j))),
                        "keys of " + lexicals[i] + " and " + lexicals[j]);
                if (i != j) {
                    final byte[] a = keys.get(i);
                    final byte[] b = keys.get(j);
                    assertFalse(a.length < b.length && Arrays.equals(a, 0, a.length, b, 0, a.length),
                            "the key of " + lexicals[i] + " is a prefix of the key of " + lexicals[j]);
                }
            }
        }
    }

    private static AtomicValue atomic(final int type, final String lexical) throws XPathException {
        return new UntypedAtomicValue(lexical).convertTo(type);
    }

    private static byte[] enc(final int type, final String lexical) throws XPathException {
        final byte[] key = TypedKeys.encode(atomic(type, lexical));
        assertNotNull(key, "no key for " + lexical);
        return key;
    }

    private static byte[] key(final String decimal) throws XPathException {
        return enc(Type.DECIMAL, decimal);
    }

    private static int compare(final byte[] a, final byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }
}
