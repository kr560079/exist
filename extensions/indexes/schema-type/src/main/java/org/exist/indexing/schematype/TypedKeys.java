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

import org.exist.xquery.XPathException;
import org.exist.xquery.value.AbstractDateTimeValue;
import org.exist.xquery.value.AtomicValue;
import org.exist.xquery.value.DoubleValue;
import org.exist.xquery.value.DurationValue;
import org.exist.xquery.value.FloatValue;
import org.exist.xquery.value.Type;

import javax.annotation.Nullable;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.Duration;
import javax.xml.datatype.XMLGregorianCalendar;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The order-preserving keys of typed values in the typed-value table ({@code 0x04}) of the schema type index.
 *
 * <p>A key starts with the <em>family</em> of the value: the values of one family are ordered among themselves and
 * never compared with values of another family, whose keys therefore never overlap. Within a family the unsigned
 * byte order of the keys is the XQuery order of the values (equal values have equal keys), and no key is a proper
 * prefix of another, so that a key followed by a document id still sorts by value.</p>
 *
 * <table>
 *   <caption>families</caption>
 *   <tr><th>family</th><th>values</th><th>key after the family byte</th></tr>
 *   <tr><td>{@link #DECIMAL}</td><td>xs:decimal and its subtypes (xs:integer, ...)</td><td>{@linkplain #writeNumber number}</td></tr>
 *   <tr><td>{@link #DOUBLE}</td><td>xs:double, xs:float (widened, which is exact)</td><td>{@code 0x00} for NaN, else
 *       {@code 0x01} and the 8 bytes of the IEEE bits made sortable (-0 is written as +0)</td></tr>
 *   <tr><td>{@link #STRING}</td><td>xs:string and its subtypes</td><td>UTF-8 (codepoint order), at most
 *       {@link #MAX_STRING_BYTES} bytes, then {@code 0x00}; longer strings are truncated (their keys order with
 *       the other keys, but are not unique)</td></tr>
 *   <tr><td>{@link #ANY_URI}</td><td>xs:anyURI</td><td>as for strings</td></tr>
 *   <tr><td>{@link #DATE}, {@link #DATE_TIME}, {@link #TIME}</td><td>xs:date, xs:dateTime (and xs:dateTimeStamp),
 *       xs:time</td><td>{@link #TZ_AWARE} and the number of seconds since 1970-01-01T00:00:00Z of the (starting)
 *       instant, or {@link #TZ_LESS} and the same number for the local value read as UTC; xs:time is taken on
 *       1972-12-31</td></tr>
 *   <tr><td>{@link #DAY_TIME_DURATION}</td><td>xs:dayTimeDuration</td><td>number of seconds</td></tr>
 *   <tr><td>{@link #YEAR_MONTH_DURATION}</td><td>xs:yearMonthDuration</td><td>number of months</td></tr>
 * </table>
 *
 * <p>A number is {@code 0x01} (negative), {@code 0x02} (zero) or {@code 0x03} (positive); for a non-zero number
 * with magnitude {@code 0.d1d2...dn × 10^e} ({@code d1 != 0}, no trailing zeros) follow the exponent {@code e} as
 * 4 bytes big-endian with the sign bit flipped, the digits in pairs (one byte {@code 1 + 10·d + d'} per pair, the
 * last pair padded with 0) and a {@code 0x00} terminator; for a negative number those bytes are complemented.</p>
 *
 * <p>Values of other types (xs:boolean, xs:duration, the Gregorian types, binary types, xs:QName, ...) have no key.</p>
 */
final class TypedKeys {

    static final byte DECIMAL = 0x10;
    static final byte DOUBLE = 0x11;
    static final byte STRING = 0x20;
    static final byte ANY_URI = 0x21;
    static final byte DATE = 0x30;
    static final byte DATE_TIME = 0x31;
    static final byte TIME = 0x32;
    static final byte DAY_TIME_DURATION = 0x40;
    static final byte YEAR_MONTH_DURATION = 0x41;

    /**
     * The key of a node of a listed type whose typed value has no key (an invalid lexical value, a value without
     * encoding or one too large): the index cannot answer comparisons over that type in the node's document.
     */
    static final byte UNINDEXABLE = (byte) 0xFF;

    /** no family: the type has no key encoding */
    static final int NO_FAMILY = -1;

    /** the second byte of a date/time key: the value has no timezone */
    static final byte TZ_LESS = 0x00;
    /** the second byte of a date/time key: the value has a timezone */
    static final byte TZ_AWARE = 0x01;

    /** the second byte of a double key: NaN, which no comparison but {@code !=} matches */
    static final byte DOUBLE_NAN = 0x00;
    /** the second byte of a double key: a number or an infinity */
    static final byte DOUBLE_NUMBER = 0x01;

    /** strings are truncated to this many UTF-8 bytes */
    static final int MAX_STRING_BYTES = 256;
    /** a key longer than this is not written (the node is {@link #UNINDEXABLE}) */
    static final int MAX_KEY_BYTES = 512;

    private static final byte NEGATIVE = 0x01;
    private static final byte ZERO = 0x02;
    private static final byte POSITIVE = 0x03;

    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400);

    private TypedKeys() {
    }

    /**
     * @param builtInType a built-in atomic {@link Type} code
     * @return the family of the values of the type, or {@link #NO_FAMILY}
     */
    static int familyOf(final int builtInType) {
        try {
            if (Type.subTypeOf(builtInType, Type.DECIMAL)) {
                return DECIMAL;
            }
            if (builtInType == Type.DOUBLE || builtInType == Type.FLOAT) {
                return DOUBLE;
            }
            if (Type.subTypeOf(builtInType, Type.STRING)) {
                return STRING;
            }
            if (builtInType == Type.ANY_URI) {
                return ANY_URI;
            }
            if (builtInType == Type.DATE) {
                return DATE;
            }
            if (Type.subTypeOf(builtInType, Type.DATE_TIME)) {
                return DATE_TIME;
            }
            if (builtInType == Type.TIME) {
                return TIME;
            }
            if (builtInType == Type.DAY_TIME_DURATION) {
                return DAY_TIME_DURATION;
            }
            if (builtInType == Type.YEAR_MONTH_DURATION) {
                return YEAR_MONTH_DURATION;
            }
        } catch (final IllegalArgumentException e) {
            // not a built-in type
        }
        return NO_FAMILY;
    }

    /**
     * @return the family of an atomic value (a value of a user-defined type by its built-in base), or
     *         {@link #NO_FAMILY}
     */
    static int familyOf(final AtomicValue value) {
        return familyOf(value.unwrapUserType().getType());
    }

    /**
     * The key of an atomic value.
     *
     * @param value the value; a value of a user-defined type is encoded as its built-in base value
     * @return the key, or null if the value has no key
     */
    static @Nullable byte[] encode(final AtomicValue value) {
        final AtomicValue v = value.unwrapUserType();
        final int family = familyOf(v.getType());
        if (family == NO_FAMILY) {
            return null;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(16);
        out.write(family);
        try {
            switch (family) {
                case DECIMAL -> {
                    if (!writeNumber(out, new BigDecimal(v.getStringValue()))) {
                        return null;
                    }
                }
                case DOUBLE -> writeDouble(out, v instanceof FloatValue f ? f.getValue() : ((DoubleValue) v).getValue());
                case STRING, ANY_URI -> writeString(out, v.getStringValue());
                case DATE, DATE_TIME, TIME -> {
                    if (!writeDateTime(out, ((AbstractDateTimeValue) v).getTrimmedCalendar(), family)) {
                        return null;
                    }
                }
                case DAY_TIME_DURATION -> {
                    if (!writeNumber(out, seconds(((DurationValue) v).getCanonicalDuration()))) {
                        return null;
                    }
                }
                case YEAR_MONTH_DURATION -> {
                    if (!writeNumber(out, new BigDecimal(months(((DurationValue) v).getCanonicalDuration())))) {
                        return null;
                    }
                }
                default -> {
                    return null;
                }
            }
        } catch (final XPathException | RuntimeException e) {
            return null;
        }
        final byte[] key = out.toByteArray();
        return key.length > MAX_KEY_BYTES ? null : key;
    }

    /**
     * The key of a decimal-family number.
     *
     * @return the key, or null if it is too large
     */
    static @Nullable byte[] decimalKey(final BigDecimal value) {
        return numberKey(DECIMAL, value);
    }

    /** the key of a double-family number */
    static byte[] doubleKey(final double value) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(10);
        out.write(DOUBLE);
        writeDouble(out, value);
        return out.toByteArray();
    }

    /** the key of a number of a family whose values are written as numbers (or of a date/time subfamily prefix) */
    static @Nullable byte[] numberKey(final byte[] prefix, final BigDecimal value) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(16);
        out.writeBytes(prefix);
        if (!writeNumber(out, value)) {
            return null;
        }
        final byte[] key = out.toByteArray();
        return key.length > MAX_KEY_BYTES ? null : key;
    }

    static @Nullable byte[] numberKey(final byte family, final BigDecimal value) {
        return numberKey(new byte[] {family}, value);
    }

    /**
     * Write a number, see the class description.
     *
     * @return false if the exponent does not fit
     */
    static boolean writeNumber(final ByteArrayOutputStream out, final BigDecimal value) {
        final int sign = value.signum();
        if (sign == 0) {
            out.write(ZERO);
            return true;
        }
        final BigDecimal abs = value.abs().stripTrailingZeros();
        final String digits = abs.unscaledValue().toString();
        final long exponent = (long) digits.length() - abs.scale();
        if (exponent > Integer.MAX_VALUE || exponent < Integer.MIN_VALUE) {
            return false;
        }
        final byte[] magnitude = new byte[4 + (digits.length() + 1) / 2 + 1];
        final int e = (int) exponent ^ Integer.MIN_VALUE;
        magnitude[0] = (byte) (e >>> 24);
        magnitude[1] = (byte) (e >>> 16);
        magnitude[2] = (byte) (e >>> 8);
        magnitude[3] = (byte) e;
        int pos = 4;
        for (int i = 0; i < digits.length(); i += 2) {
            final int high = digits.charAt(i) - '0';
            final int low = i + 1 < digits.length() ? digits.charAt(i + 1) - '0' : 0;
            magnitude[pos++] = (byte) (1 + high * 10 + low);
        }
        magnitude[pos] = 0;
        if (sign > 0) {
            out.write(POSITIVE);
        } else {
            out.write(NEGATIVE);
            for (int i = 0; i < magnitude.length; i++) {
                magnitude[i] = (byte) ~magnitude[i];
            }
        }
        out.writeBytes(magnitude);
        return true;
    }

    private static void writeDouble(final ByteArrayOutputStream out, final double value) {
        if (Double.isNaN(value)) {
            out.write(DOUBLE_NAN);
            return;
        }
        out.write(DOUBLE_NUMBER);
        long bits = Double.doubleToLongBits(value == 0.0d ? 0.0d : value);
        bits = bits < 0 ? ~bits : bits ^ Long.MIN_VALUE;
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (bits >>> shift));
        }
    }

    private static void writeString(final ByteArrayOutputStream out, final String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > MAX_STRING_BYTES) {
            int end = MAX_STRING_BYTES;
            // do not cut a character: back up to the start of a UTF-8 sequence
            while (end > 0 && (utf8[end] & 0xC0) == 0x80) {
                end--;
            }
            utf8 = Arrays.copyOf(utf8, end);
        }
        out.writeBytes(utf8);
        out.write(0);
    }

    /** whether the key of a string was truncated (it then stands for every string with its prefix) */
    static boolean isTruncatedString(final String value) {
        return value.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_BYTES;
    }

    private static boolean writeDateTime(final ByteArrayOutputStream out, final XMLGregorianCalendar cal, final int family) {
        final boolean hasTimezone = cal.getTimezone() != DatatypeConstants.FIELD_UNDEFINED;
        out.write(hasTimezone ? TZ_AWARE : TZ_LESS);
        final BigDecimal seconds = seconds(cal, family);
        return seconds != null && writeNumber(out, seconds);
    }

    /**
     * @return the seconds since 1970-01-01T00:00:00Z of the instant of a date, dateTime or time (a value without
     *         timezone read as UTC; a time on 1972-12-31), or null if the year is out of range
     */
    static @Nullable BigDecimal seconds(final XMLGregorianCalendar cal, final int family) {
        final long year;
        final int month;
        final int day;
        if (family == TIME) {
            year = 1972;
            month = 12;
            day = 31;
        } else {
            final BigInteger eonAndYear = cal.getEonAndYear();
            if (eonAndYear == null || eonAndYear.bitLength() > 40) {
                return null;
            }
            year = eonAndYear.longValue();
            month = cal.getMonth();
            day = cal.getDay();
        }
        BigDecimal seconds = BigDecimal.valueOf(daysFromCivil(year, month, day)).multiply(SECONDS_PER_DAY);
        if (family != DATE) {
            seconds = seconds.add(BigDecimal.valueOf(field(cal.getHour()) * 3600L + field(cal.getMinute()) * 60L
                    + field(cal.getSecond())));
            if (cal.getFractionalSecond() != null) {
                seconds = seconds.add(cal.getFractionalSecond());
            }
        }
        if (cal.getTimezone() != DatatypeConstants.FIELD_UNDEFINED) {
            seconds = seconds.subtract(BigDecimal.valueOf(cal.getTimezone() * 60L));
        }
        return seconds;
    }

    private static int field(final int value) {
        return value == DatatypeConstants.FIELD_UNDEFINED ? 0 : value;
    }

    /** the number of days from 1970-01-01 to a date of the proleptic Gregorian calendar (H. Hinnant's algorithm) */
    static long daysFromCivil(final long year, final int month, final int day) {
        final long y = month <= 2 ? year - 1 : year;
        final long era = Math.floorDiv(y, 400);
        final long yoe = y - era * 400;
        final long doy = (153L * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
        final long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146_097 + doe - 719_468;
    }

    /** the signed number of seconds of a day-time duration */
    static BigDecimal seconds(final Duration d) {
        BigDecimal s = BigDecimal.ZERO;
        s = s.add(number(d.getField(DatatypeConstants.DAYS)).multiply(SECONDS_PER_DAY));
        s = s.add(number(d.getField(DatatypeConstants.HOURS)).multiply(BigDecimal.valueOf(3600)));
        s = s.add(number(d.getField(DatatypeConstants.MINUTES)).multiply(BigDecimal.valueOf(60)));
        s = s.add(number(d.getField(DatatypeConstants.SECONDS)));
        return d.getSign() < 0 ? s.negate() : s;
    }

    /** the signed number of months of a year-month duration */
    static BigInteger months(final Duration d) {
        final BigInteger m = number(d.getField(DatatypeConstants.YEARS)).toBigInteger().multiply(BigInteger.valueOf(12))
                .add(number(d.getField(DatatypeConstants.MONTHS)).toBigInteger());
        return d.getSign() < 0 ? m.negate() : m;
    }

    private static BigDecimal number(@Nullable final Number n) {
        if (n == null) {
            return BigDecimal.ZERO;
        }
        if (n instanceof BigDecimal b) {
            return b;
        }
        if (n instanceof BigInteger b) {
            return new BigDecimal(b);
        }
        return new BigDecimal(n.toString());
    }

    /**
     * @return the smallest key greater than every key starting with {@code prefix}, or null if there is none
     */
    static @Nullable byte[] successor(final byte[] prefix) {
        for (int i = prefix.length - 1; i >= 0; i--) {
            if (prefix[i] != (byte) 0xFF) {
                final byte[] r = Arrays.copyOf(prefix, i + 1);
                r[i]++;
                return r;
            }
        }
        return null;
    }
}
