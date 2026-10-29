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

import org.exist.xquery.ErrorCodes;
import org.exist.xquery.Expression;
import org.exist.xquery.XPathException;
import org.exist.xquery.value.AtomicValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.UserDefinedType;
import org.exist.xquery.value.UserTypeRegistry;

import javax.annotation.Nullable;

/**
 * Casts to user-defined simple types (XQuery 3.1 §3.14.2, Functions and Operators 3.1 §19): the value is cast to
 * the built-in ancestor of the type, its lexical form is checked against the facets of the type, and the result is
 * annotated with the type.
 */
public final class UserTypeCasts {

    private UserTypeCasts() {
    }

    /**
     * @param typeCode a type code
     * @return false for a user-defined type which is not a simple (atomic, list or union) type, such as a complex
     *     type; true for every other type
     */
    public static boolean isSimpleTarget(final int typeCode) {
        final UserDefinedType type = UserTypeRegistry.get(typeCode);
        return type == null || switch (type.variety()) {
            case ATOMIC, LIST, UNION -> true;
            default -> false;
        };
    }

    /**
     * Cast an atomic value to a user-defined simple type.
     *
     * @param value        the value to cast
     * @param userTypeCode the code of a user-defined atomic, list or union type
     * @param ctx          the expression for error reporting, or null
     * @return the cast value: one atomic value, or the items of the value for a list type
     * @throws XPathException {@code FORG0001} if the value violates the facets of the type or matches no member of a
     *     union; {@code XPTY0004} if there is no cast from the type of the value (for a list type: if the value is
     *     not an {@code xs:string} or {@code xs:untypedAtomic}); {@code XQST0052} if the type is not a simple type
     */
    public static Sequence cast(final AtomicValue value, final int userTypeCode, @Nullable final Expression ctx)
            throws XPathException {
        final UserDefinedType type = UserTypeRegistry.get(userTypeCode);
        if (type == null) {
            return value.convertTo(userTypeCode);
        }
        final AtomicValue source = value.unwrapUserType();
        return switch (type.variety()) {
            case ATOMIC -> castAtomic(source, type, ctx);
            case LIST -> castList(source, type, ctx);
            case UNION -> castUnion(source, type, ctx);
            default -> throw new XPathException(ctx, ErrorCodes.XQST0052,
                    Type.getTypeName(userTypeCode) + " is not a simple type");
        };
    }

    private static Sequence castAtomic(final AtomicValue source, final UserDefinedType type,
            @Nullable final Expression ctx) throws XPathException {
        int builtIn = type.baseCode();
        while (UserTypeRegistry.isUserType(builtIn)) {
            builtIn = UserTypeRegistry.get(builtIn).baseCode();
        }
        final String lexical = lexicalOf(source, builtIn, type, ctx);
        if (!ValueChecker.validate(type.definition(), lexical)) {
            throw new XPathException(ctx, ErrorCodes.FORG0001,
                    "'" + lexical + "' is not valid for " + Type.getTypeName(type.code()));
        }
        return TypedValues.fromLexical(lexical, type.code(), ctx);
    }

    /** the lexical form checked against the facets: the source itself if a string, else its canonical form in the built-in type */
    private static String lexicalOf(final AtomicValue source, final int builtIn, final UserDefinedType type,
            @Nullable final Expression ctx) throws XPathException {
        final int sourceType = source.getType();
        if (isStringLike(sourceType)) {
            return source.getStringValue();
        }
        if (!Type.isCastable(sourceType, builtIn)) {
            throw new XPathException(ctx, ErrorCodes.XPTY0004, "Cannot cast " + Type.getTypeName(sourceType)
                    + " to " + Type.getTypeName(type.code()));
        }
        return source.convertTo(builtIn).getStringValue();
    }

    private static boolean isStringLike(final int type) {
        return type == Type.UNTYPED_ATOMIC || type == Type.STRING;
    }

    private static Sequence castList(final AtomicValue source, final UserDefinedType type,
            @Nullable final Expression ctx) throws XPathException {
        if (!isStringLike(source.getType())) {
            throw new XPathException(ctx, ErrorCodes.XPTY0004, "Cannot cast " + Type.getTypeName(source.getType())
                    + " to the list type " + Type.getTypeName(type.code()) + ": only xs:string and xs:untypedAtomic can be cast");
        }
        final String lexical = source.getStringValue();
        if (!ValueChecker.validate(type.definition(), lexical)) {
            throw new XPathException(ctx, ErrorCodes.FORG0001,
                    "'" + lexical + "' is not valid for " + Type.getTypeName(type.code()));
        }
        return TypedValues.fromLexical(lexical, type.code(), ctx);
    }

    private static Sequence castUnion(final AtomicValue source, final UserDefinedType type,
            @Nullable final Expression ctx) throws XPathException {
        for (final int member : type.memberCodes()) {
            try {
                if (UserTypeRegistry.isUserType(member)) {
                    return cast(source, member, ctx);
                }
                if (Type.isCastable(source.getType(), member)) {
                    return source.convertTo(member);
                }
            } catch (final XPathException e) {
                // try the next member
            }
        }
        throw new XPathException(ctx, ErrorCodes.FORG0001, "'" + source.getStringValue()
                + "' cannot be cast to any member of " + Type.getTypeName(type.code()));
    }
}
