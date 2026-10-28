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

import org.apache.xerces.impl.dv.XSSimpleType;
import org.apache.xerces.xs.XSComplexTypeDefinition;
import org.apache.xerces.xs.XSSimpleTypeDefinition;
import org.apache.xerces.xs.XSTypeDefinition;
import org.exist.xquery.ErrorCodes;
import org.exist.xquery.Expression;
import org.exist.xquery.XPathException;
import org.exist.xquery.value.AtomicValue;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.UntypedAtomicValue;
import org.exist.xquery.value.UserDefinedAtomicValue;
import org.exist.xquery.value.UserDefinedType;
import org.exist.xquery.value.UserTypeRegistry;
import org.exist.xquery.value.ValueSequence;

import javax.annotation.Nullable;

/**
 * Constructs the typed value of a node from its lexical value and the code of its type annotation.
 *
 * <p>Facets are not checked: the lexical value is expected to be valid for the type
 * (see {@link ValueChecker}). The whitespace facet of the type is applied.</p>
 */
public final class TypedValues {

    private TypedValues() {
    }

    /**
     * Construct a typed value.
     *
     * @param lexical  the lexical value (string value of the node)
     * @param typeCode the {@link Type} code of the type annotation, built-in or user-defined
     * @param ctx      the expression for error reporting, or null
     * @return the typed value: one atomic value, a sequence for list types, or the empty sequence for
     *         complex types with empty content
     * @throws XPathException {@code FOTY0012} for complex types with element-only content,
     *                        {@code FORG0001} if the lexical value is not valid for the type
     */
    public static Sequence fromLexical(final String lexical, final int typeCode, @Nullable final Expression ctx)
            throws XPathException {
        final UserDefinedType userType = UserTypeRegistry.get(typeCode);
        if (userType == null) {
            return builtIn(lexical, typeCode, ctx);
        }
        return switch (userType.variety()) {
            case ATOMIC -> userAtomic(lexical, userType, ctx);
            case LIST -> list(lexical, userType.memberCodes()[0], ctx);
            case UNION -> union(lexical, userType, ctx);
            case COMPLEX_SIMPLE_CONTENT -> fromLexical(lexical, userType.memberCodes()[0], ctx);
            case COMPLEX_MIXED -> new UntypedAtomicValue(ctx, lexical);
            case COMPLEX_EMPTY -> Sequence.EMPTY_SEQUENCE;
            case COMPLEX_ELEMENT_ONLY -> throw new XPathException(ctx, ErrorCodes.FOTY0012,
                    "A node of type " + Type.getTypeName(typeCode) + " has element-only content and no typed value");
        };
    }

    private static Sequence builtIn(final String lexical, final int typeCode, @Nullable final Expression ctx)
            throws XPathException {
        switch (typeCode) {
            case Type.ANY_TYPE:
            case Type.UNTYPED:
            case Type.ANY_SIMPLE_TYPE:
            case Type.UNTYPED_ATOMIC:
            case Type.ANY_ATOMIC_TYPE:
                return new UntypedAtomicValue(ctx, lexical);
            case Type.NMTOKENS:
                return list(lexical, Type.NMTOKEN, ctx);
            case Type.IDREFS:
                return list(lexical, Type.IDREF, ctx);
            case Type.ENTITIES:
                return list(lexical, Type.ENTITY, ctx);
            default:
                if (!Type.subTypeOf(typeCode, Type.ANY_ATOMIC_TYPE)) {
                    throw new XPathException(ctx, ErrorCodes.XPTY0004,
                            "Type " + Type.getTypeName(typeCode) + " is not a simple type");
                }
                return new UntypedAtomicValue(ctx, lexical).convertTo(typeCode);
        }
    }

    private static AtomicValue userAtomic(final String lexical, final UserDefinedType userType,
            @Nullable final Expression ctx) throws XPathException {
        int builtInCode = userType.baseCode();
        while (UserTypeRegistry.isUserType(builtInCode)) {
            builtInCode = UserTypeRegistry.get(builtInCode).baseCode();
        }
        final String normalized = applyWhitespaceFacet(lexical, userType.definition());
        final AtomicValue base = new UntypedAtomicValue(ctx, normalized).convertTo(builtInCode);
        return new UserDefinedAtomicValue(ctx, userType.code(), base);
    }

    private static Sequence list(final String lexical, final int itemCode, @Nullable final Expression ctx)
            throws XPathException {
        final String collapsed = collapse(lexical);
        if (collapsed.isEmpty()) {
            return Sequence.EMPTY_SEQUENCE;
        }
        final String[] tokens = collapsed.split(" ");
        final ValueSequence result = new ValueSequence(tokens.length);
        for (final String token : tokens) {
            result.addAll(fromLexical(token, itemCode, ctx));
        }
        return result;
    }

    private static Sequence union(final String lexical, final UserDefinedType userType,
            @Nullable final Expression ctx) throws XPathException {
        for (final int member : userType.memberCodes()) {
            final UserDefinedType memberType = UserTypeRegistry.get(member);
            if (memberType != null && !ValueChecker.validate(memberType.definition(), lexical)) {
                // a user member type: its facets must accept the value
                continue;
            }
            try {
                return fromLexical(lexical, member, ctx);
            } catch (final XPathException e) {
                // try the next member
            }
        }
        throw new XPathException(ctx, ErrorCodes.FORG0001,
                "'" + lexical + "' is not valid for any member of " + Type.getTypeName(userType.code()));
    }

    private static String applyWhitespaceFacet(final String lexical, final XSTypeDefinition definition) {
        final XSSimpleTypeDefinition simple = definition instanceof XSComplexTypeDefinition complex
                ? complex.getSimpleType() : (XSSimpleTypeDefinition) definition;
        if (simple instanceof XSSimpleType xercesType) {
            try {
                return switch (xercesType.getWhitespace()) {
                    case XSSimpleType.WS_COLLAPSE -> collapse(lexical);
                    case XSSimpleType.WS_REPLACE -> replace(lexical);
                    default -> lexical;
                };
            } catch (final org.apache.xerces.impl.dv.DatatypeException e) {
                // no whitespace facet (union): keep the value
            }
        }
        return lexical;
    }

    private static boolean isXmlWhitespace(final char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static String replace(final String s) {
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            sb.append(isXmlWhitespace(c) ? ' ' : c);
        }
        return sb.toString();
    }

    static String collapse(final String s) {
        final StringBuilder sb = new StringBuilder(s.length());
        boolean pendingSpace = false;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (isXmlWhitespace(c)) {
                pendingSpace = sb.length() > 0;
            } else {
                if (pendingSpace) {
                    sb.append(' ');
                    pendingSpace = false;
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
