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
package org.exist.xquery;

import org.apache.xerces.xs.XSAttributeDeclaration;
import org.apache.xerces.xs.XSElementDeclaration;
import org.exist.dom.QName;
import org.exist.xquery.schema.CompiledSchema;
import org.exist.xquery.schema.InScopeSchemas;
import org.exist.xquery.value.Type;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLStreamReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@code schema-element(N)} and {@code schema-attribute(N)} (XQuery 3.1 §2.5.5.4, §2.5.5.6): a node matches if
 * its name is one of the accepted names (the name of the declaration of {@code N}, and for an element the
 * substitutable members of its substitution group), its type annotation is the declared type of {@code N} or
 * derived from it, and, unless {@code N} is nillable, it is not nilled. With the kind {@link Type#DOCUMENT} it
 * tests the document element of a document node: {@code document-node(schema-element(N))}.
 *
 * <p>Only nodes carrying a stored type annotation match: in-memory nodes, nodes of collections without a schema
 * and stored nodes no type could be assigned to are not instances of any schema declaration.</p>
 */
public class SchemaNodeTest extends TypedNodeTest {

    /**
     * What a {@code schema-element(N)} or {@code schema-attribute(N)} test matches, resolved from the in-scope
     * schema definitions.
     *
     * @param name             the name of the declaration
     * @param acceptedNames    the names a matching node may have
     * @param declaredTypeCode the {@link Type} code of the declared type
     * @param nillable         true if the declaration is nillable
     */
    public record Declaration(QName name, Set<QName> acceptedNames, int declaredTypeCode, boolean nillable) {
    }

    private final Set<QName> acceptedNames;
    /** the accepted local names by namespace ({@code ""} for none): no allocation to test a node */
    private final Map<String, Set<String>> localNamesByNamespace;
    private final QName declarationName;

    /**
     * @param ctx              the context of the query
     * @param nodeKind         {@link Type#ELEMENT}, {@link Type#ATTRIBUTE} or {@link Type#DOCUMENT}
     * @param acceptedNames    the names of {@code substitutionMembers(decl)} for an element declaration, the name
     *                         of the declaration for an attribute declaration
     * @param declaredTypeCode the {@link Type} code of the declared type
     * @param nillable         true if the declaration is nillable (a nilled element may then match)
     * @throws XPathException never: the names are those of declarations
     */
    public SchemaNodeTest(final XQueryContext ctx, final int nodeKind, final Set<QName> acceptedNames,
            final int declaredTypeCode, final boolean nillable) throws XPathException {
        this(ctx, nodeKind, acceptedNames, declaredTypeCode, nillable, null);
    }

    /**
     * @param ctx         the context of the query
     * @param nodeKind    {@link Type#ELEMENT}, {@link Type#ATTRIBUTE} or {@link Type#DOCUMENT}
     * @param declaration the resolved declaration
     * @throws XPathException never: the names are those of declarations
     */
    public SchemaNodeTest(final XQueryContext ctx, final int nodeKind, final Declaration declaration) throws XPathException {
        this(ctx, nodeKind, declaration.acceptedNames(), declaration.declaredTypeCode(), declaration.nillable(),
                declaration.name());
    }

    private SchemaNodeTest(final XQueryContext ctx, final int nodeKind, final Set<QName> acceptedNames,
            final int declaredTypeCode, final boolean nillable, final QName declarationName) throws XPathException {
        super(ctx, nodeKind, null, declaredTypeCode, nillable);
        this.acceptedNames = Set.copyOf(acceptedNames);
        this.localNamesByNamespace = byNamespace(acceptedNames);
        this.declarationName = declarationName;
    }

    /** @return the names a matching node may have */
    public Set<QName> getAcceptedNames() {
        return acceptedNames;
    }

    /**
     * Index the accepted names for {@link #acceptsName(Map, String, String)}.
     *
     * @param names the accepted names
     * @return the local names by namespace
     */
    public static Map<String, Set<String>> byNamespace(final Set<QName> names) {
        final Map<String, Set<String>> result = new HashMap<>();
        for (final QName name : names) {
            final String ns = name.getNamespaceURI() == null ? XMLConstants.NULL_NS_URI : name.getNamespaceURI();
            result.computeIfAbsent(ns, k -> new LinkedHashSet<>()).add(name.getLocalPart());
        }
        result.replaceAll((ns, locals) -> Set.copyOf(locals));
        return Collections.unmodifiableMap(result);
    }

    /**
     * @param localNamesByNamespace the accepted names, see {@link #byNamespace(Set)}
     * @param namespaceUri          the namespace of a node, null for none
     * @param localName             the local name of the node
     * @return true if the name is accepted
     */
    public static boolean acceptsName(final Map<String, Set<String>> localNamesByNamespace,
            final String namespaceUri, final String localName) {
        final Set<String> locals = localNamesByNamespace.get(namespaceUri == null ? XMLConstants.NULL_NS_URI : namespaceUri);
        return locals != null && localName != null && locals.contains(localName);
    }

    @Override
    protected boolean matchesName(final Node node) {
        Node named = node;
        if (node.getNodeType() == Node.DOCUMENT_NODE) {
            named = ((Document) node).getDocumentElement();
            if (named == null) {
                return false;
            }
        }
        return acceptsName(localNamesByNamespace, named.getNamespaceURI(), named.getLocalName());
    }

    @Override
    protected boolean matchesName(final XMLStreamReader reader) {
        return acceptsName(localNamesByNamespace, reader.getNamespaceURI(), reader.getLocalName());
    }

    @Override
    public boolean matches(final QName name) {
        return acceptedNames.contains(new QName(name.getLocalPart(), name.getNamespaceURI()));
    }

    @Override
    protected boolean requiresAnnotation() {
        return true;
    }

    /**
     * Resolve the declaration of a {@code schema-element(N)} test.
     *
     * @param schemas the in-scope schema definitions
     * @param name    the element name
     * @return the declaration
     * @throws XPathException {@code XPST0008} if no imported schema declares a global element of the name
     */
    public static Declaration elementDeclaration(final InScopeSchemas schemas, final QName name) throws XPathException {
        final XSElementDeclaration decl = schemas.resolveElementDeclaration(name);
        final CompiledSchema schema = schemaOf(schemas, decl.getNamespace());
        final Set<QName> names = new LinkedHashSet<>();
        for (final XSElementDeclaration member : schema.substitutionMembers(decl)) {
            names.add(new QName(member.getName(), nonNull(member.getNamespace())));
        }
        return new Declaration(name, Collections.unmodifiableSet(names), schema.typeCode(decl.getTypeDefinition()),
                decl.getNillable());
    }

    /**
     * Resolve the declaration of a {@code schema-attribute(N)} test.
     *
     * @param schemas the in-scope schema definitions
     * @param name    the attribute name
     * @return the declaration
     * @throws XPathException {@code XPST0008} if no imported schema declares a global attribute of the name
     */
    public static Declaration attributeDeclaration(final InScopeSchemas schemas, final QName name) throws XPathException {
        final XSAttributeDeclaration decl = schemas.resolveAttributeDeclaration(name);
        final CompiledSchema schema = schemaOf(schemas, decl.getNamespace());
        return new Declaration(name, Set.of(new QName(decl.getName(), nonNull(decl.getNamespace()))),
                schema.typeCode(decl.getTypeDefinition()), false);
    }

    private static CompiledSchema schemaOf(final InScopeSchemas schemas, final String namespace) throws XPathException {
        return schemas.schemaFor(nonNull(namespace)).orElseThrow(() -> new XPathException((Expression) null,
                ErrorCodes.XPST0008, "No schema imported for namespace " + namespace));
    }

    private static String nonNull(final String namespace) {
        return namespace == null ? XMLConstants.NULL_NS_URI : namespace;
    }

    @Override
    public String toString() {
        final String name = declarationName == null ? acceptedNames.toString() : declarationName.getStringValue();
        final String test = (nodeType == Type.ATTRIBUTE ? "schema-attribute(" : "schema-element(") + name + ")";
        return nodeType == Type.DOCUMENT ? "document-node(" + test + ")" : test;
    }
}
