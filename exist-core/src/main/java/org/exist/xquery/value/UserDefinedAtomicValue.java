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
package org.exist.xquery.value;

import com.ibm.icu.text.Collator;
import org.exist.EXistException;
import org.exist.xquery.Constants.Comparison;
import org.exist.xquery.Expression;
import org.exist.xquery.XPathException;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * An atomic value of a user-defined (schema) atomic type. It reports the user type code from
 * {@link #getType()} and delegates everything else to a value of the nearest built-in ancestor type.
 *
 * <p>Code that casts an atomic value to a concrete value class (e.g. {@link NumericValue}) must call
 * {@link #unwrapUserType()} first, so that arithmetic and built-in functions operate on, and return
 * values of, the built-in type.</p>
 */
public final class UserDefinedAtomicValue extends AtomicValue {

    private final int userTypeCode;
    private final AtomicValue baseValue;

    /**
     * @param ctx          the expression from which the value derives, or null
     * @param userTypeCode the code of the user-defined type, registered in {@link UserTypeRegistry}
     * @param baseValue    the value of a built-in type
     */
    public UserDefinedAtomicValue(@Nullable final Expression ctx, final int userTypeCode, final AtomicValue baseValue) {
        super(ctx);
        this.userTypeCode = userTypeCode;
        this.baseValue = Objects.requireNonNull(baseValue).unwrapUserType();
    }

    @Override
    public int getType() {
        return userTypeCode;
    }

    @Override
    public AtomicValue unwrapUserType() {
        return baseValue;
    }

    @Override
    public String getStringValue() throws XPathException {
        return baseValue.getStringValue();
    }

    @Override
    public AtomicValue convertTo(final int requiredType) throws XPathException {
        if (requiredType == userTypeCode || requiredType == Type.ANY_ATOMIC_TYPE || requiredType == Type.ITEM
                || (UserTypeRegistry.isUserType(requiredType) && Type.subTypeOf(userTypeCode, requiredType))) {
            return this;
        }
        return baseValue.convertTo(requiredType);
    }

    @Override
    public boolean effectiveBooleanValue() throws XPathException {
        return baseValue.effectiveBooleanValue();
    }

    @Override
    public boolean compareTo(final Collator collator, final Comparison operator, final AtomicValue other)
            throws XPathException {
        return baseValue.compareTo(collator, operator, other.unwrapUserType());
    }

    @Override
    public int compareTo(final Collator collator, final AtomicValue other) throws XPathException {
        return baseValue.compareTo(collator, other.unwrapUserType());
    }

    @Override
    public AtomicValue max(final Collator collator, final AtomicValue other) throws XPathException {
        return baseValue.max(collator, other.unwrapUserType());
    }

    @Override
    public AtomicValue min(final Collator collator, final AtomicValue other) throws XPathException {
        return baseValue.min(collator, other.unwrapUserType());
    }

    @Override
    public boolean startsWith(final Collator collator, final AtomicValue other) throws XPathException {
        return baseValue.startsWith(collator, other.unwrapUserType());
    }

    @Override
    public boolean endsWith(final Collator collator, final AtomicValue other) throws XPathException {
        return baseValue.endsWith(collator, other.unwrapUserType());
    }

    @Override
    public boolean contains(final Collator collator, final AtomicValue other) throws XPathException {
        return baseValue.contains(collator, other.unwrapUserType());
    }

    @Override
    public AtomicValue promote(final AtomicValue otherValue) throws XPathException {
        return baseValue.promote(otherValue.unwrapUserType());
    }

    @Override
    public int conversionPreference(final Class<?> javaClass) {
        return baseValue.conversionPreference(javaClass);
    }

    @Override
    public <T> T toJavaObject(final Class<T> target) throws XPathException {
        return baseValue.toJavaObject(target);
    }

    @Override
    public byte[] serializeValue(final int offset) throws EXistException {
        return baseValue.serializeValue(offset);
    }

    @Override
    public int compareTo(final Object other) {
        return baseValue.compareTo(other instanceof AtomicValue atomic ? atomic.unwrapUserType() : other);
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        return obj instanceof AtomicValue atomic && baseValue.equals(atomic.unwrapUserType());
    }

    @Override
    public int hashCode() {
        return baseValue.hashCode();
    }
}
