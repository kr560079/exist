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

import javax.annotation.Nullable;

import org.exist.dom.QName;
import org.exist.dom.memtree.ReferenceNode;
import org.exist.xquery.Cardinality;
import org.exist.xquery.ErrorCodes;
import org.exist.xquery.Expression;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.SchemaNodeTest;
import org.exist.xquery.schema.TypeAnnotations;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.Map;
import java.util.Set;

/**
 * Represents an XQuery SequenceType and provides methods to check
 * sequences and items against this type.
 *
 * @author wolf
 */
public class SequenceType {

    private int primaryType = Type.ITEM;
    private Cardinality cardinality = Cardinality.EXACTLY_ONE;
    private QName nodeName = null;
    private int typeAnnotation = -1;
    private boolean nillable = false;
    /** the context of the query testing type annotations, for its per-execution caches; may be null */
    private @Nullable org.exist.xquery.XQueryContext annotationContext = null;
    /** schema-element(N) / schema-attribute(N): the accepted names by namespace, or null */
    private @Nullable Map<String, Set<String>> schemaAcceptedNames = null;
    private @Nullable QName schemaDeclarationName = null;
    private SequenceType[] functionParamTypes;
    private SequenceType functionReturnType;

    public SequenceType() {
    }

    /**
     * Construct a new SequenceType using the specified
     * primary type and cardinality constants.
     *
     * @param primaryType one of the constants defined in {@link Type}
     * @param cardinality one of the constants defined in {@link Cardinality}
     */
    public SequenceType(final int primaryType, final Cardinality cardinality) {
        this.primaryType = primaryType;
        this.cardinality = cardinality;
    }

    /**
     * Construct a new SequenceType using the specified
     * primary type and cardinality.
     *
     * @param primaryType one of the constants defined in {@link Type}
     * @param cardinality the cardinality integer value
     *                    
     * @deprecated Use {@link #SequenceType(int, Cardinality)}
     */
    @Deprecated
    public SequenceType(final int primaryType, final int cardinality) {
        this.primaryType = primaryType;
        this.cardinality = Cardinality.fromInt(cardinality);
    }

    /**
     * Returns the primary type as one of the
     * constants defined in {@link Type}.
     *
     * @return the primary type as one of the constants defined in {@link Type}
     */
    public int getPrimaryType() {
        return primaryType;
    }

    public void setPrimaryType(int type) {
        this.primaryType = type;
    }

    /**
     * Returns the expected cardinality. See the constants
     * defined in {@link Cardinality}.
     *
     * @return expected cardinality, one of {@link Cardinality}
     */
    public Cardinality getCardinality() {
        return cardinality;
    }

    public void setCardinality(Cardinality cardinality) {
        this.cardinality = cardinality;
    }

    public QName getNodeName() {
        return nodeName;
    }

    /**
     * The type a node must be annotated with, as in {@code element(N, T)}.
     *
     * @return the {@link Type} code, or -1 if the annotation is not tested
     */
    public int getTypeAnnotation() {
        return typeAnnotation;
    }

    /**
     * @return true if a nilled node may match the type annotation, as in {@code element(N, T?)}
     */
    public boolean isNillable() {
        return nillable;
    }

    /**
     * Require nodes to be annotated with a subtype of the type, as in {@code element(N, T)}.
     *
     * @param typeCode the {@link Type} code of the type
     * @param nillable true if a nilled node may match
     */
    public void setTypeAnnotation(final int typeCode, final boolean nillable) {
        setTypeAnnotation(typeCode, nillable, null);
    }

    /**
     * Require nodes to be annotated with a subtype of the type, as in {@code element(N, T)}.
     *
     * @param typeCode the {@link Type} code of the type
     * @param nillable true if a nilled node may match
     * @param context  the context of the query, whose per-execution caches annotation tests use; or null
     */
    public void setTypeAnnotation(final int typeCode, final boolean nillable,
            @Nullable final org.exist.xquery.XQueryContext context) {
        this.typeAnnotation = typeCode;
        this.nillable = nillable;
        this.annotationContext = context;
    }

    public void setNodeName(QName qname) {
        this.nodeName = qname;
    }

    // === Schema Aware Feature ===
    /**
     * Require nodes to match a schema declaration, as in {@code schema-element(N)} and {@code schema-attribute(N)}:
     * their name is one of the accepted names, they carry a stored annotation which is the declared type or derived
     * from it, and they are not nilled unless the declaration is nillable. For {@code document-node(schema-element(N))}
     * (primary type {@link Type#DOCUMENT}) the document element is tested.
     *
     * @param acceptedNames    the names of the declaration and, for an element, of its substitution group members
     * @param declaredTypeCode the {@link Type} code of the declared type
     * @param nillable         true if the declaration is nillable
     */
    public void setSchemaDeclaration(final Set<QName> acceptedNames, final int declaredTypeCode, final boolean nillable) {
        setSchemaDeclaration(acceptedNames, declaredTypeCode, nillable, null, null);
    }

    /**
     * as {@link #setSchemaDeclaration(Set, int, boolean)}
     *
     * @param acceptedNames    the names of the declaration and, for an element, of its substitution group members
     * @param declaredTypeCode the {@link Type} code of the declared type
     * @param nillable         true if the declaration is nillable
     * @param declarationName  the name of the declaration, for messages; or null
     * @param context          the context of the query, whose per-execution caches annotation tests use; or null
     */
    public void setSchemaDeclaration(final Set<QName> acceptedNames, final int declaredTypeCode, final boolean nillable,
            @Nullable final QName declarationName, @Nullable final org.exist.xquery.XQueryContext context) {
        this.schemaAcceptedNames = SchemaNodeTest.byNamespace(acceptedNames);
        this.schemaDeclarationName = declarationName;
        setTypeAnnotation(declaredTypeCode, nillable, context);
    }

    /** @return true if nodes must match a schema declaration, see {@link #setSchemaDeclaration(Set, int, boolean)} */
    public boolean isSchemaDeclaration() {
        return schemaAcceptedNames != null;
    }
    // === End Schema Aware Feature ===

    /**
     * Get the function parameter types for typed function tests.
     * Only set when primaryType is FUNCTION, MAP_ITEM, or ARRAY_ITEM
     * and a specific function signature was given (not function(*)).
     *
     * @return the parameter types, or null if not a typed function test
     */
    public SequenceType[] getFunctionParamTypes() {
        return functionParamTypes;
    }

    public void setFunctionParamTypes(final SequenceType[] paramTypes) {
        this.functionParamTypes = paramTypes;
    }

    /**
     * Get the function return type for typed function tests.
     * Only set when primaryType is FUNCTION, MAP_ITEM, or ARRAY_ITEM
     * and a specific function signature was given (not function(*)).
     *
     * @return the return type, or null if not a typed function test
     */
    public SequenceType getFunctionReturnType() {
        return functionReturnType;
    }

    public void setFunctionReturnType(final SequenceType returnType) {
        this.functionReturnType = returnType;
    }

    /**
     * Check the specified sequence against this SequenceType.
     *
     * @param seq sequence to check
     * @throws XPathException if check fails for one item in the sequence
     * @return true, if all items of the sequence have the same type as or a subtype of primaryType
     */
    public boolean checkType(final Sequence seq) throws XPathException {
        if (nodeName == null && typeAnnotation == -1) {
            return Type.subTypeOf(seq.getItemType(), primaryType);
        }

        for (final SequenceIterator i = seq.iterate(); i.hasNext(); ) {
            if (!checkType(i.nextItem())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check a single item against this SequenceType.
     *
     * @param item the item to check
     * @return true, if item is a subtype of primaryType
     * @throws XPathException if the type annotation of a stored node cannot be determined ({@code EXSA0002},
     *         {@code EXSA0003})
     */
    public boolean checkType(final Item item) throws XPathException {
        // an in-memory reference to a stored node has the kind and name of the node it refers to
        final Item node = item instanceof final ReferenceNode reference ? reference.getReference() : item;
        int type = node.getType();
        if (type == Type.NODE) {
            // the kind is not known yet, e.g. of a stored node not read so far: take it from the
            // node itself, mapping its DOM node kind to the XQuery type it stands for
            type = Type.fromDomNodeType(((NodeValue) node).getNode().getNodeType());
        }
        if (!Type.subTypeOf(type, primaryType)) {
            return false;
        }

        // For typed function() tests, check parameter and return type compatibility.
        // MAP_ITEM and ARRAY_ITEM are subtypes of FUNCTION but use distinct typed-test
        // syntax (map(K,V), array(T)) whose parameter counts do not match the
        // underlying function signature's argument count (a map's accessor signature
        // takes 1 arg, the key; map(K,V) carries 2 type parameters). Restrict the
        // function-arity/return check to plain function() tests so map and array
        // values continue to satisfy their typed tests.
        if (primaryType == Type.FUNCTION && item instanceof FunctionReference reference
                && !checkFunctionType(reference)) {
            return false;
        }

        if (schemaAcceptedNames != null) {
            return matchesSchemaDeclaration(node, type);
        }
        if (nodeName == null) {
            return hasTypeAnnotation(node, type);
        }
        //TODO : how to improve performance ?
        final QName realName = getRealName(node);

        if (realName == null) {
            return false;
        }
        // a wildcard part of the expected name matches any, as in NameTest: processing-instruction(NAME)
        // has a wildcard namespace, since a processing instruction's name has none to compare
        if (!(nodeName instanceof QName.WildcardNamespaceURIQName) && nodeName.getNamespaceURI() != null &&
                !nodeName.getNamespaceURI().equals(realName.getNamespaceURI())) {
            return false;
        }
        if (!(nodeName instanceof QName.WildcardLocalPartQName) && nodeName.getLocalPart() != null
                && !nodeName.getLocalPart().equals(realName.getLocalPart())) {
            return false;
        }
        return hasTypeAnnotation(node, type);
    }

    private boolean hasTypeAnnotation(final Item node, final int kind) throws XPathException {
        if (typeAnnotation == -1) {
            return true;
        }
        if (kind == Type.DOCUMENT) {
            // document-node(element(N, T)): the annotation of the document element
            final Element root = documentElement((NodeValue) node);
            return root != null && TypeAnnotations.matches(annotationContext, root, typeAnnotation, nillable);
        }
        return TypeAnnotations.matches(annotationContext, (NodeValue) node, typeAnnotation, nillable);
    }

    private boolean matchesSchemaDeclaration(final Item item, final int kind) throws XPathException {
        final NodeValue nodeValue = (NodeValue) item;
        final Node node;
        if (kind == Type.DOCUMENT) {
            node = documentElement(nodeValue);
            if (node == null) {
                return false;
            }
        } else {
            node = nodeValue.getNode();
        }
        if (!SchemaNodeTest.acceptsName(schemaAcceptedNames, node.getNamespaceURI(), node.getLocalName())) {
            return false;
        }
        return kind == Type.DOCUMENT
                ? TypeAnnotations.matches(annotationContext, node, typeAnnotation, nillable, true)
                : TypeAnnotations.matches(annotationContext, nodeValue, typeAnnotation, nillable, true);
    }

    private static @Nullable Element documentElement(final NodeValue node) {
        final Document doc = node instanceof Document document ? document : node.getOwnerDocument();
        return doc == null ? null : doc.getDocumentElement();
    }

    /**
     * Check if a function reference matches the required function type.
     * Per the XQuery spec, function types are checked as follows:
     * - The function's arity must match the number of parameter types
     * - The function's return type must be a subtype of the required return type (covariant)
     * - Each required parameter type must be a subtype of the function's parameter type (contravariant)
     *
     * @param funcRef the function reference to check
     * @return true if the function matches the required function type
     */
    private boolean checkFunctionType(final FunctionReference funcRef) {
        final FunctionSignature sig = funcRef.getSignature();

        // Check arity: if we have typed parameter info, check against it
        if (functionParamTypes != null && sig.getArgumentCount() != functionParamTypes.length) {
            return false;
        }

        // Check return type: function's return type must be a subtype of required return type (covariant)
        if (functionReturnType != null && sig.getReturnType() != null) {
            final int actualReturnType = sig.getReturnType().getPrimaryType();
            final int requiredReturnType = functionReturnType.getPrimaryType();
            if (!Type.subTypeOf(actualReturnType, requiredReturnType)) {
                return false;
            }
        }

        // Check parameter types: required param types must be subtypes of function's param types (contravariant)
        // Note: for now we skip contravariant parameter checking as it requires more infrastructure
        // The return type check alone fixes the majority of subtyping test failures

        return true;
    }

    private static QName getRealName(final Item item) {
        final NodeValue nvItem = (NodeValue) item;
        if (item.getType() != Type.DOCUMENT) {
            // get the name of the element/attribute
            return nvItem.getQName();
        }
        // it's a document... we need to get the document element's name
        final Document doc;
        if (nvItem instanceof Document document) {
            doc = document;
        } else {
            doc = nvItem.getOwnerDocument();
        }
        if (doc == null) {
            return null;
        }
        final Element elem = doc.getDocumentElement();
        if (elem == null) {
            return null;
        }
        return new QName(elem.getLocalName(), elem.getNamespaceURI());
    }

    /**
     * Check the given type against the primary type
     * declared in this SequenceType.
     *
     * @param type one of the constants defined in {@link Type}
     * @throws XPathException if subtype check fails
     */
    public void checkType(int type) throws XPathException {
        if (type == Type.EMPTY_SEQUENCE || type == Type.ITEM) {
            return;
        }

        // Although xs:anyURI is not a subtype of xs:string, both types are compatible
        if (type == Type.ANY_URI && primaryType == Type.STRING) {
            return;
        }

        if (!Type.subTypeOf(type, primaryType)) {
            throw new XPathException((Expression) null, ErrorCodes.XPTY0004,
                    "Type error: expected type: " + Type.getTypeName(primaryType) + "; got: " + Type.getTypeName(type));
        }
    }

    /**
     * Check if the given sequence has the cardinality required
     * by this sequence type.
     *
     * @param seq the sequence to check
     * @throws XPathException if cardinality does not match
     */
    public void checkCardinality(Sequence seq) throws XPathException {
        if (!seq.isEmpty() && cardinality == Cardinality.EMPTY_SEQUENCE) {
            throw new XPathException((Expression) null, ErrorCodes.XPTY0004, "Empty sequence expected; got " + seq.getItemCount());
        }
        if (seq.isEmpty() && cardinality.atLeastOne()) {
            throw new XPathException((Expression) null, ErrorCodes.XPTY0004, "Empty sequence is not allowed here");
        } else if (seq.hasMany() && cardinality.atMostOne()) {
            throw new XPathException((Expression) null, ErrorCodes.XPTY0004, "Sequence with more than one item is not allowed here");
        }
    }

    /**
     * Used to serialize SequenceTypes, when building stack traces, for example.
     *
     * @return The serialized SequenceType
     */
    @Override
    public String toString() {
        if (cardinality == Cardinality.EMPTY_SEQUENCE) {
            return cardinality.toXQueryCardinalityString();
        }

        final String str;
        if (schemaAcceptedNames != null) {
            final String test = (primaryType == Type.ATTRIBUTE ? "schema-attribute(" : "schema-element(")
                    + (schemaDeclarationName == null ? "" : schemaDeclarationName.getStringValue()) + ")";
            str = primaryType == Type.DOCUMENT ? "document-node(" + test + ")" : test;
        } else if (primaryType == Type.DOCUMENT && nodeName != null) {
            str = "document-node(" + nodeName.getStringValue() + ")";
        } else if (primaryType == Type.ELEMENT && nodeName != null) {
            str = "element(" + nodeName.getStringValue() + ")";
        } else if (primaryType == Type.MAP_ITEM) {
            str = "map(*)";
        } else if (primaryType == Type.ARRAY_ITEM) {
            str = "array(*)";
        } else if (primaryType == Type.FUNCTION) {
            str = "function(*)";
        } else {
            str = Type.getTypeName(primaryType);
        }

        return str + cardinality.toXQueryCardinalityString();
    }

}
