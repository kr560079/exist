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
package org.exist.xquery.functions.fn;

import io.lacuna.bifurcan.IMap;
import org.exist.Namespaces;
import org.exist.dom.QName;
import org.exist.xquery.*;
import org.exist.xquery.functions.map.MapType;
import org.exist.xquery.schema.TypeAnnotations;
import org.exist.xquery.value.*;

import javax.annotation.Nullable;

import static org.exist.xquery.FunctionDSL.*;
import static org.exist.xquery.functions.fn.FnModule.functionSignature;
import static org.exist.xquery.functions.map.MapType.newLinearMap;

/**
 * {@code fn:node-type-annotation} and {@code fn:atomic-type-annotation}: the schema type of a node's annotation or of
 * an atomic value, as a schema-type record (the subset of the XPath and XQuery Functions 4.0 record this
 * implementation supports):
 *
 * <ul>
 *   <li>{@code name} - the {@code xs:QName} of the type, the empty sequence for an anonymous type</li>
 *   <li>{@code is-simple} - whether it is a simple type</li>
 *   <li>{@code variety} - {@code atomic}, {@code list}, {@code union}, {@code empty}, {@code simple} (a complex type
 *       with simple content), {@code element-only} or {@code mixed}</li>
 *   <li>{@code base-type} - a zero-arity function returning the record of the base type, the empty sequence for
 *       {@code xs:anyType}</li>
 *   <li>{@code primitive-type} - a zero-arity function returning the record of the primitive built-in type; present
 *       for atomic types only</li>
 * </ul>
 *
 * <p>Nodes which no schema assessed have the annotation {@code xs:untyped} (elements) or {@code xs:untypedAtomic}
 * (attributes).</p>
 */
public class FunTypeAnnotation extends BasicFunction {

    // --- Schema Aware ---
    private static final String NODE_NAME = "node-type-annotation";
    private static final String ATOMIC_NAME = "atomic-type-annotation";

    private static final String RECORD_DESCRIPTION = "A schema-type record: the entries name (xs:QName?, empty for an "
            + "anonymous type), is-simple (xs:boolean), variety (xs:string), base-type (function() as map(*)?) and, "
            + "for atomic types, primitive-type (function() as map(*)).";

    public static final FunctionSignature FS_NODE_TYPE_ANNOTATION = functionSignature(
            NODE_NAME,
            "Returns the type annotation of an element or attribute node: xs:untyped (elements) or xs:untypedAtomic "
                    + "(attributes) if no schema assessed the node.",
            returns(Type.MAP_ITEM, RECORD_DESCRIPTION),
            param("node", Type.NODE, "The element or attribute node"));

    public static final FunctionSignature FS_ATOMIC_TYPE_ANNOTATION = functionSignature(
            ATOMIC_NAME,
            "Returns the type of an atomic value.",
            returns(Type.MAP_ITEM, RECORD_DESCRIPTION),
            param("value", Type.ANY_ATOMIC_TYPE, "The atomic value"));
    // --- End Schema Aware ---

    public FunTypeAnnotation(final XQueryContext context, final FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public Sequence eval(final Sequence[] args, final Sequence contextSequence) throws XPathException {
        final Item item = args[0].itemAt(0);
        if (isSignature(FS_ATOMIC_TYPE_ANNOTATION)) {
            return typeRecord(context, ((AtomicValue) item).getType());
        }
        if (!(item instanceof NodeValue node)) {
            throw new XPathException(this, ErrorCodes.XPTY0004, "The argument of fn:node-type-annotation is not a node");
        }
        int kind = node.getType();
        if (kind == Type.NODE) {
            kind = Type.fromDomNodeType(node.getNode().getNodeType());
        }
        if (kind != Type.ELEMENT && kind != Type.ATTRIBUTE) {
            throw new XPathException(this, ErrorCodes.XPTY0004,
                    "The argument of fn:node-type-annotation is not an element or attribute node");
        }
        return typeRecord(context, TypeAnnotations.annotationOf(context, node).typeCode());
    }

    private boolean isSignature(final FunctionSignature other) {
        return getSignature().getName().equals(other.getName());
    }

    /**
     * The schema-type record of a type.
     *
     * @param context the context of the query
     * @param code    the {@link Type} code of the type, built-in or user-defined
     * @return the record
     */
    static MapType typeRecord(final XQueryContext context, final int code) {
        final UserDefinedType user = UserTypeRegistry.get(code);
        final QName name;
        final boolean simple;
        final String variety;
        final int base;
        final int primitive;
        if (user != null) {
            name = user.name();
            simple = user.variety().isSimple();
            variety = user.variety().label();
            base = user.baseCode() > 0 && user.baseCode() != Type.ITEM ? user.baseCode() : -1;
            primitive = user.variety() == Variety.ATOMIC ? user.primitiveCode() : 0;
        } else {
            name = builtInName(code);
            switch (code) {
                case Type.ANY_TYPE, Type.UNTYPED -> {
                    simple = false;
                    variety = "mixed";
                }
                case Type.ANY_SIMPLE_TYPE -> {
                    simple = true;
                    variety = "simple";
                }
                case Type.IDREFS, Type.NMTOKENS, Type.ENTITIES -> {
                    simple = true;
                    variety = "list";
                }
                case Type.NUMERIC, Type.ERROR -> {
                    simple = true;
                    variety = "union";
                }
                default -> {
                    simple = true;
                    variety = "atomic";
                }
            }
            base = builtInBase(code);
            primitive = "atomic".equals(variety) ? builtInPrimitive(code) : 0;
        }

        final IMap<AtomicValue, Sequence> map = newLinearMap(null);
        map.put(new StringValue("name"), name == null ? Sequence.EMPTY_SEQUENCE : new QNameValue(context, name));
        map.put(new StringValue("is-simple"), BooleanValue.valueOf(simple));
        map.put(new StringValue("variety"), new StringValue(variety));
        map.put(new StringValue("base-type"), reference(context, new TypeRecordFunction(context, base, false)));
        if (primitive > 0) {
            map.put(new StringValue("primitive-type"), reference(context, new TypeRecordFunction(context, primitive, true)));
        }
        return new MapType(context, map.forked(), Type.STRING);
    }

    private static FunctionReference reference(final XQueryContext context, final UserDefinedFunction function) {
        return new FunctionReference(new FunctionCall(context, function));
    }

    private static @Nullable QName builtInName(final int code) {
        final String name = Type.getTypeName(code);
        if (name == null) {
            return null;
        }
        final int colon = name.indexOf(':');
        if (colon < 0) {
            return new QName(name, Namespaces.SCHEMA_NS, "xs");
        }
        return new QName(name.substring(colon + 1), Namespaces.SCHEMA_NS, "xs");
    }

    private static int builtInBase(final int code) {
        switch (code) {
            case Type.ANY_TYPE:
                return -1;
            case Type.UNTYPED:
            case Type.ANY_SIMPLE_TYPE:
                return Type.ANY_TYPE;
            case Type.ANY_ATOMIC_TYPE:
            case Type.IDREFS:
            case Type.NMTOKENS:
            case Type.ENTITIES:
            case Type.NUMERIC:
            case Type.ERROR:
                return Type.ANY_SIMPLE_TYPE;
            default:
                try {
                    final int superType = Type.getSuperType(code);
                    return superType == Type.ITEM || superType == Type.NODE || superType == code ? -1 : superType;
                } catch (final IllegalArgumentException e) {
                    return -1;
                }
        }
    }

    private static int builtInPrimitive(final int code) {
        if (code == Type.ANY_ATOMIC_TYPE) {
            return 0;
        }
        try {
            return Type.primitiveTypeOf(code);
        } catch (final IllegalArgumentException e) {
            return 0;
        }
    }

    /** The zero-arity function item returning the record of the base or of the primitive type. */
    private static final class TypeRecordFunction extends UserDefinedFunction {
        private final int code;

        TypeRecordFunction(final XQueryContext context, final int code, final boolean exactlyOne) {
            super(context, functionSignature(
                    exactlyOne ? "primitive-type" : "base-type",
                    "Returns the schema-type record of the " + (exactlyOne ? "primitive" : "base") + " type.",
                    exactlyOne ? returns(Type.MAP_ITEM) : returnsOpt(Type.MAP_ITEM)));
            this.code = code;
        }

        @Override
        public Sequence eval(final Sequence contextSequence, final Item contextItem) throws XPathException {
            return code <= 0 ? Sequence.EMPTY_SEQUENCE : typeRecord(context, code);
        }

        @Override
        public void accept(final ExpressionVisitor visitor) {
            if (visited) {
                return;
            }
            visited = true;
        }
    }
}
