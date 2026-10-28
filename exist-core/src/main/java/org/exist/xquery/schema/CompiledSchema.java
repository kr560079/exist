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

import org.apache.xerces.xs.StringList;
import org.apache.xerces.xs.XSAttributeDeclaration;
import org.apache.xerces.xs.XSAttributeUse;
import org.apache.xerces.xs.XSComplexTypeDefinition;
import org.apache.xerces.xs.XSConstants;
import org.apache.xerces.xs.XSElementDeclaration;
import org.apache.xerces.xs.XSModel;
import org.apache.xerces.xs.XSModelGroup;
import org.apache.xerces.xs.XSNamedMap;
import org.apache.xerces.xs.XSObjectList;
import org.apache.xerces.xs.XSParticle;
import org.apache.xerces.xs.XSSimpleTypeDefinition;
import org.apache.xerces.xs.XSTerm;
import org.apache.xerces.xs.XSTypeDefinition;
import org.apache.xerces.xs.XSWildcard;
import org.exist.dom.QName;
import org.exist.xquery.XPathException;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.UserDefinedType;
import org.exist.xquery.value.UserTypeRegistry;
import org.exist.xquery.value.Variety;

import javax.annotation.Nullable;
import javax.xml.XMLConstants;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A precomputed model of an XML Schema, for assigning types to nodes without running a validator.
 *
 * <p>XSD's <em>Element Declarations Consistent</em> constraint means that a child element's declaration is a
 * function of its parent's type and its expanded name, so the content model of each complex type is flattened
 * into a map from child name to {@link ChildResolution}, including the members of the substitution groups of
 * the element particles it references, plus the list of element wildcards.</p>
 *
 * <p>Instances are safe for concurrent readers: all state is immutable after {@link #compile(SchemaKey, XSModel)},
 * except the content models (built lazily on first use) and the cache of type codes, which are held in
 * {@link ConcurrentHashMap}s.</p>
 */
public final class CompiledSchema {

    private static final String XS_NS = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final String XSI_NS = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;
    private static final String ANON_PREFIX = "#anon:";

    private final SchemaKey key;
    private final Set<String> targetNamespaces;
    private final Map<QName, XSElementDeclaration> globalElements;
    private final Map<QName, XSAttributeDeclaration> globalAttributes;
    private final Map<QName, XSTypeDefinition> namedTypes;
    /** head (global element) to the set of elements which may appear in its place */
    private final Map<XSElementDeclaration, Set<XSElementDeclaration>> substitutionMembers;
    /** anonymous type definition to its {@code #anon:<declaration path>} id */
    private final Map<XSTypeDefinition, String> anonymousIds;

    private final ConcurrentHashMap<XSComplexTypeDefinition, ContentModel> contentModels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<XSTypeDefinition, Integer> typeCodes = new ConcurrentHashMap<>();

    /**
     * The flattened content model of a complex type.
     *
     * @param children the declared children (element particles and substitution group members) by name
     * @param wildcards the element wildcards, in document order
     */
    private record ContentModel(Map<QName, ChildResolution> children, List<XSWildcard> wildcards) {
    }

    private CompiledSchema(final SchemaKey key, final Set<String> targetNamespaces,
            final Map<QName, XSElementDeclaration> globalElements, final Map<QName, XSAttributeDeclaration> globalAttributes,
            final Map<QName, XSTypeDefinition> namedTypes, final Map<XSElementDeclaration, Set<XSElementDeclaration>> substitutionMembers,
            final Map<XSTypeDefinition, String> anonymousIds) {
        this.key = key;
        this.targetNamespaces = targetNamespaces;
        this.globalElements = globalElements;
        this.globalAttributes = globalAttributes;
        this.namedTypes = namedTypes;
        this.substitutionMembers = substitutionMembers;
        this.anonymousIds = anonymousIds;
    }

    /**
     * Compile a schema model.
     *
     * @param key the key identifying the schema; user-defined type codes are registered under it
     * @param model the Xerces schema model
     * @return the compiled schema
     */
    public static CompiledSchema compile(final SchemaKey key, final XSModel model) {
        final Set<String> namespaces = new LinkedHashSet<>();
        final StringList nsList = model.getNamespaces();
        for (int i = 0; i < nsList.getLength(); i++) {
            final String ns = nsList.item(i);
            if (!XS_NS.equals(ns) && !XSI_NS.equals(ns)) {
                namespaces.add(ns == null ? XMLConstants.NULL_NS_URI : ns);
            }
        }

        final Map<QName, XSElementDeclaration> elements = new HashMap<>();
        final XSNamedMap elementMap = model.getComponents(XSConstants.ELEMENT_DECLARATION);
        for (int i = 0; i < elementMap.getLength(); i++) {
            final XSElementDeclaration decl = (XSElementDeclaration) elementMap.item(i);
            elements.put(qname(decl.getName(), decl.getNamespace()), decl);
        }

        final Map<QName, XSAttributeDeclaration> attributes = new HashMap<>();
        final XSNamedMap attributeMap = model.getComponents(XSConstants.ATTRIBUTE_DECLARATION);
        for (int i = 0; i < attributeMap.getLength(); i++) {
            final XSAttributeDeclaration decl = (XSAttributeDeclaration) attributeMap.item(i);
            attributes.put(qname(decl.getName(), decl.getNamespace()), decl);
        }

        final Map<QName, XSTypeDefinition> types = new HashMap<>();
        final XSNamedMap typeMap = model.getComponents(XSConstants.TYPE_DEFINITION);
        for (int i = 0; i < typeMap.getLength(); i++) {
            final XSTypeDefinition def = (XSTypeDefinition) typeMap.item(i);
            types.put(qname(def.getName(), def.getNamespace()), def);
        }

        final Map<XSElementDeclaration, Set<XSElementDeclaration>> members = new IdentityHashMap<>();
        for (final XSElementDeclaration head : elements.values()) {
            members.put(head, computeSubstitutionMembers(model, head));
        }

        final Map<XSTypeDefinition, String> anonymous = new IdentityHashMap<>();
        for (int i = 0; i < elementMap.getLength(); i++) {
            final XSElementDeclaration decl = (XSElementDeclaration) elementMap.item(i);
            collectAnonymous(decl.getTypeDefinition(), "/" + clark(decl.getName(), decl.getNamespace()), anonymous);
        }
        for (int i = 0; i < attributeMap.getLength(); i++) {
            final XSAttributeDeclaration decl = (XSAttributeDeclaration) attributeMap.item(i);
            collectAnonymous(decl.getTypeDefinition(), "/@" + clark(decl.getName(), decl.getNamespace()), anonymous);
        }
        for (int i = 0; i < typeMap.getLength(); i++) {
            final XSTypeDefinition def = (XSTypeDefinition) typeMap.item(i);
            if (!XS_NS.equals(def.getNamespace())) {
                collectAnonymousWithin(def, "type(" + clark(def.getName(), def.getNamespace()) + ")", anonymous);
            }
        }

        return new CompiledSchema(key, Collections.unmodifiableSet(namespaces), Map.copyOf(elements), Map.copyOf(attributes),
                Map.copyOf(types), Collections.unmodifiableMap(members), Collections.unmodifiableMap(anonymous));
    }

    public SchemaKey key() {
        return key;
    }

    /**
     * @return the target namespaces of the schema's components, {@code ""} standing for no namespace;
     *         the XML Schema namespace of the built-in types is excluded
     */
    public Set<String> targetNamespaces() {
        return targetNamespaces;
    }

    public Optional<XSElementDeclaration> globalElement(final QName name) {
        return Optional.ofNullable(globalElements.get(name));
    }

    public Optional<XSAttributeDeclaration> globalAttribute(final QName name) {
        return Optional.ofNullable(globalAttributes.get(name));
    }

    /**
     * @param name the type name
     * @return the named type definition, including the XML Schema built-in types
     */
    public Optional<XSTypeDefinition> namedType(final QName name) {
        return Optional.ofNullable(namedTypes.get(name));
    }

    /**
     * Look up a type definition by the stable id under which its code is registered with
     * {@link UserTypeRegistry}: {@code {ns}local} for named types (including the XML Schema built-in
     * types), {@code #anon:<declaration path>} for anonymous types reachable from a global component.
     *
     * @param typeId the type id
     * @return the type definition, or empty if this schema defines no type with that id
     */
    public Optional<XSTypeDefinition> typeById(final String typeId) {
        if (typeId.startsWith("{")) {
            final int close = typeId.indexOf('}');
            if (close < 0) {
                return Optional.empty();
            }
            return namedType(new QName(typeId.substring(close + 1), typeId.substring(1, close)));
        }
        if (typeId.startsWith(ANON_PREFIX)) {
            for (final Map.Entry<XSTypeDefinition, String> entry : anonymousIds.entrySet()) {
                if (entry.getValue().equals(typeId)) {
                    return Optional.of(entry.getKey());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Resolve a child element against the content model of its parent's type.
     *
     * <p>Element particles match by name, global element particles also by the members of their substitution
     * group (see {@link #substitutionMembers(XSElementDeclaration)}). Otherwise the first element wildcard
     * allowing the child's namespace applies: {@code skip} yields no declaration; {@code strict} and {@code lax}
     * look up a non-abstract global element declaration. A {@code strict} wildcard without such a declaration
     * does not allow the child (it would be invalid), whereas {@code lax} yields a {@link ChildResolution.Kind#WILDCARD}
     * without a declaration.</p>
     *
     * @param parent the parent's type
     * @param child the child's expanded name
     * @return the resolution
     */
    public ChildResolution resolveChild(final XSComplexTypeDefinition parent, final QName child) {
        final ContentModel model = contentModels.computeIfAbsent(parent, this::buildContentModel);
        final ChildResolution declared = model.children().get(child);
        if (declared != null) {
            return declared;
        }
        for (final XSWildcard wildcard : model.wildcards()) {
            if (allowsNamespace(wildcard, child.getNamespaceURI())) {
                final short processContents = wildcard.getProcessContents();
                if (processContents == XSWildcard.PC_SKIP) {
                    return ChildResolution.wildcard(null, processContents);
                }
                final XSElementDeclaration global = globalElements.get(child);
                if (global != null && global.getAbstract()) {
                    // an abstract element may never appear in an instance
                    return ChildResolution.NOT_ALLOWED;
                }
                if (global == null && processContents == XSWildcard.PC_STRICT) {
                    return ChildResolution.NOT_ALLOWED;
                }
                return ChildResolution.wildcard(global, processContents);
            }
        }
        return ChildResolution.NOT_ALLOWED;
    }

    /**
     * Resolve an attribute of an element of the given type: first its attribute uses (including inherited ones),
     * then its attribute wildcard, where {@code strict} and {@code lax} look up a global attribute declaration.
     *
     * @param owner the type of the owner element
     * @param attr the attribute's expanded name
     * @return the attribute declaration, or empty if there is none (not allowed, or matched by a {@code skip}
     *         wildcard, or by a {@code strict}/{@code lax} wildcard without a global declaration)
     */
    public Optional<XSAttributeDeclaration> resolveAttribute(final XSComplexTypeDefinition owner, final QName attr) {
        final XSObjectList uses = owner.getAttributeUses();
        for (int i = 0; i < uses.getLength(); i++) {
            final XSAttributeDeclaration decl = ((XSAttributeUse) uses.item(i)).getAttrDeclaration();
            if (attr.getLocalPart().equals(decl.getName()) && attr.getNamespaceURI().equals(nullToEmpty(decl.getNamespace()))) {
                return Optional.of(decl);
            }
        }
        final XSWildcard wildcard = owner.getAttributeWildcard();
        if (wildcard != null && wildcard.getProcessContents() != XSWildcard.PC_SKIP
                && allowsNamespace(wildcard, attr.getNamespaceURI())) {
            return Optional.ofNullable(globalAttributes.get(attr));
        }
        return Optional.empty();
    }

    /**
     * The elements which may appear where {@code head} is referenced: the head itself when it is not abstract,
     * plus the transitive members of its substitution group which are not abstract and whose substitution is not
     * blocked by the head ({@code block="substitution"}, or a derivation method blocked by the head or its type).
     * {@code final} on the head is enforced by Xerces when the schema is loaded.
     *
     * @param head an element declaration
     * @return the substitutable elements
     */
    public Set<XSElementDeclaration> substitutionMembers(final XSElementDeclaration head) {
        final Set<XSElementDeclaration> members = substitutionMembers.get(head);
        if (members != null) {
            return members;
        }
        return head.getAbstract() ? Set.of() : Set.of(head);
    }

    /**
     * Get the type code of a type definition. XML Schema built-in types map to {@link Type} constants; other
     * types are registered with {@link UserTypeRegistry} under this schema's key (their base types first).
     *
     * @param def the type definition
     * @return the type code
     */
    public int typeCode(final XSTypeDefinition def) {
        final Integer cached = typeCodes.get(def);
        if (cached != null) {
            return cached;
        }
        // not computeIfAbsent: computing a code recursively computes the codes of base and member types
        final int code = computeTypeCode(def);
        typeCodes.putIfAbsent(def, code);
        return code;
    }

    private int computeTypeCode(final XSTypeDefinition def) {
        if (XS_NS.equals(def.getNamespace()) && !def.getAnonymous()) {
            return builtInTypeCode(def);
        }

        final XSTypeDefinition baseDef = def.getBaseType();
        final int baseCode = baseDef == null || baseDef == def ? Type.ANY_TYPE : typeCode(baseDef);

        final Variety variety;
        final int primitiveCode;
        final int[] memberCodes;
        if (def instanceof XSSimpleTypeDefinition simple) {
            switch (simple.getVariety()) {
                case XSSimpleTypeDefinition.VARIETY_LIST -> {
                    variety = Variety.LIST;
                    primitiveCode = 0;
                    memberCodes = new int[]{typeCode(simple.getItemType())};
                }
                case XSSimpleTypeDefinition.VARIETY_UNION -> {
                    variety = Variety.UNION;
                    primitiveCode = 0;
                    final XSObjectList members = simple.getMemberTypes();
                    memberCodes = new int[members.getLength()];
                    for (int i = 0; i < memberCodes.length; i++) {
                        memberCodes[i] = typeCode((XSTypeDefinition) members.item(i));
                    }
                }
                default -> {
                    variety = Variety.ATOMIC;
                    primitiveCode = primitiveOrZero(baseCode);
                    memberCodes = new int[0];
                }
            }
        } else {
            final XSComplexTypeDefinition complex = (XSComplexTypeDefinition) def;
            switch (complex.getContentType()) {
                case XSComplexTypeDefinition.CONTENTTYPE_SIMPLE -> {
                    variety = Variety.COMPLEX_SIMPLE_CONTENT;
                    final int simpleCode = typeCode(complex.getSimpleType());
                    primitiveCode = primitiveOrZero(simpleCode);
                    memberCodes = new int[]{simpleCode};
                }
                case XSComplexTypeDefinition.CONTENTTYPE_ELEMENT -> {
                    variety = Variety.COMPLEX_ELEMENT_ONLY;
                    primitiveCode = 0;
                    memberCodes = new int[0];
                }
                case XSComplexTypeDefinition.CONTENTTYPE_MIXED -> {
                    variety = Variety.COMPLEX_MIXED;
                    primitiveCode = 0;
                    memberCodes = new int[0];
                }
                default -> {
                    variety = Variety.COMPLEX_EMPTY;
                    primitiveCode = 0;
                    memberCodes = new int[0];
                }
            }
        }

        final QName name;
        final String anonymousId;
        final String typeId;
        if (def.getAnonymous() || def.getName() == null) {
            name = null;
            anonymousId = anonymousId(def);
            typeId = anonymousId;
        } else {
            name = qname(def.getName(), def.getNamespace());
            anonymousId = null;
            typeId = clark(def.getName(), def.getNamespace());
        }
        return UserTypeRegistry.register(key, typeId, code -> new UserDefinedType(code, name, anonymousId, baseCode,
                primitiveCode, variety, key, def, memberCodes));
    }

    private int builtInTypeCode(final XSTypeDefinition def) {
        try {
            return Type.getType("xs:" + def.getName());
        } catch (final XPathException e) {
            // a built-in type unknown to eXist: use its nearest known ancestor
            final XSTypeDefinition base = def.getBaseType();
            if (base == null || base == def) {
                return def instanceof XSSimpleTypeDefinition ? Type.ANY_SIMPLE_TYPE : Type.ANY_TYPE;
            }
            return typeCode(base);
        }
    }

    private static int primitiveOrZero(final int code) {
        try {
            return Type.primitiveTypeOf(code);
        } catch (final IllegalArgumentException e) {
            return 0;
        }
    }

    private String anonymousId(final XSTypeDefinition def) {
        final String id = anonymousIds.get(def);
        if (id != null) {
            return id;
        }
        // not reachable from a global component of this schema
        return ANON_PREFIX + "#" + Integer.toHexString(System.identityHashCode(def));
    }

    // --- content models ---

    private ContentModel buildContentModel(final XSComplexTypeDefinition type) {
        final Map<QName, ChildResolution> children = new HashMap<>();
        final List<XSWildcard> wildcards = new ArrayList<>();
        collectParticles(type.getParticle(), children, wildcards);
        return new ContentModel(Map.copyOf(children), List.copyOf(wildcards));
    }

    private void collectParticles(@Nullable final XSParticle particle, final Map<QName, ChildResolution> children,
            final List<XSWildcard> wildcards) {
        if (particle == null || (!particle.getMaxOccursUnbounded() && particle.getMaxOccurs() == 0)) {
            return;
        }
        final XSTerm term = particle.getTerm();
        if (term instanceof XSElementDeclaration decl) {
            if (decl.getScope() == XSConstants.SCOPE_GLOBAL) {
                for (final XSElementDeclaration member : substitutionMembers(decl)) {
                    children.putIfAbsent(qname(member.getName(), member.getNamespace()), ChildResolution.declared(member));
                }
            } else {
                children.putIfAbsent(qname(decl.getName(), decl.getNamespace()), ChildResolution.declared(decl));
            }
        } else if (term instanceof XSModelGroup group) {
            final XSObjectList particles = group.getParticles();
            for (int i = 0; i < particles.getLength(); i++) {
                collectParticles((XSParticle) particles.item(i), children, wildcards);
            }
        } else if (term instanceof XSWildcard wildcard) {
            wildcards.add(wildcard);
        }
    }

    private static boolean allowsNamespace(final XSWildcard wildcard, final String namespace) {
        final String ns = namespace == null || namespace.isEmpty() ? null : namespace;
        final StringList list = wildcard.getNsConstraintList();
        return switch (wildcard.getConstraintType()) {
            case XSWildcard.NSCONSTRAINT_LIST -> list.contains(ns);
            case XSWildcard.NSCONSTRAINT_NOT -> !list.contains(ns);
            default -> true;
        };
    }

    // --- substitution groups ---

    private static Set<XSElementDeclaration> computeSubstitutionMembers(final XSModel model, final XSElementDeclaration head) {
        final Set<XSElementDeclaration> result = new LinkedHashSet<>();
        if (!head.getAbstract()) {
            result.add(head);
        }
        if (!head.isDisallowedSubstitution(XSConstants.DERIVATION_SUBSTITUTION)) {
            short blocking = head.getDisallowedSubstitutions();
            if (head.getTypeDefinition() instanceof XSComplexTypeDefinition headType) {
                blocking |= headType.getProhibitedSubstitutions();
            }
            blocking &= (short) (XSConstants.DERIVATION_EXTENSION | XSConstants.DERIVATION_RESTRICTION);

            // Xerces' substitution group is transitive; walk it again to be independent of that
            final Set<XSElementDeclaration> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            final Deque<XSElementDeclaration> todo = new ArrayDeque<>();
            todo.add(head);
            while (!todo.isEmpty()) {
                final XSObjectList group = model.getSubstitutionGroup(todo.poll());
                if (group == null) {
                    continue;
                }
                for (int i = 0; i < group.getLength(); i++) {
                    final XSElementDeclaration member = (XSElementDeclaration) group.item(i);
                    if (member != head && seen.add(member)) {
                        todo.add(member);
                        if (!member.getAbstract()
                                && derivationAllowed(member.getTypeDefinition(), head.getTypeDefinition(), blocking)) {
                            result.add(member);
                        }
                    }
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Type Derivation OK: every derivation step from {@code derived} up to {@code base} uses a method not in
     * {@code blocking}.
     */
    private static boolean derivationAllowed(final XSTypeDefinition derived, final XSTypeDefinition base, final short blocking) {
        XSTypeDefinition t = derived;
        while (t != base) {
            final XSTypeDefinition next = t.getBaseType();
            if (next == null || next == t) {
                return false;
            }
            final short method = t instanceof XSComplexTypeDefinition complex
                    ? complex.getDerivationMethod() : XSConstants.DERIVATION_RESTRICTION;
            if ((method & blocking) != 0) {
                return false;
            }
            t = next;
        }
        return true;
    }

    // --- anonymous type ids ---

    private static void collectAnonymous(@Nullable final XSTypeDefinition type, final String path,
            final Map<XSTypeDefinition, String> ids) {
        if (type == null || !type.getAnonymous() || ids.containsKey(type)) {
            return;
        }
        ids.put(type, ANON_PREFIX + path);
        collectAnonymousWithin(type, path, ids);
    }

    private static void collectAnonymousWithin(final XSTypeDefinition type, final String path,
            final Map<XSTypeDefinition, String> ids) {
        if (type instanceof XSSimpleTypeDefinition simple) {
            if (simple.getBaseType() != simple) {
                collectAnonymous(simple.getBaseType(), path + "/#base", ids);
            }
            collectAnonymous(simple.getItemType(), path + "/#item", ids);
            final XSObjectList members = simple.getMemberTypes();
            for (int i = 0; members != null && i < members.getLength(); i++) {
                collectAnonymous((XSTypeDefinition) members.item(i), path + "/#member" + (i + 1), ids);
            }
        } else if (type instanceof XSComplexTypeDefinition complex) {
            collectAnonymous(complex.getSimpleType(), path + "/#content", ids);
            final XSObjectList uses = complex.getAttributeUses();
            for (int i = 0; i < uses.getLength(); i++) {
                final XSAttributeDeclaration decl = ((XSAttributeUse) uses.item(i)).getAttrDeclaration();
                if (decl.getScope() != XSConstants.SCOPE_GLOBAL) {
                    collectAnonymous(decl.getTypeDefinition(), path + "/@" + clark(decl.getName(), decl.getNamespace()), ids);
                }
            }
            collectAnonymousInParticle(complex.getParticle(), path, ids);
        }
    }

    private static void collectAnonymousInParticle(@Nullable final XSParticle particle, final String path,
            final Map<XSTypeDefinition, String> ids) {
        if (particle == null) {
            return;
        }
        final XSTerm term = particle.getTerm();
        if (term instanceof XSElementDeclaration decl) {
            if (decl.getScope() != XSConstants.SCOPE_GLOBAL) {
                collectAnonymous(decl.getTypeDefinition(), path + "/" + clark(decl.getName(), decl.getNamespace()), ids);
            }
        } else if (term instanceof XSModelGroup group) {
            final XSObjectList particles = group.getParticles();
            for (int i = 0; i < particles.getLength(); i++) {
                collectAnonymousInParticle((XSParticle) particles.item(i), path, ids);
            }
        }
    }

    // --- names ---

    private static QName qname(final String local, @Nullable final String namespace) {
        return new QName(local, nullToEmpty(namespace));
    }

    private static String clark(final String local, @Nullable final String namespace) {
        return "{" + nullToEmpty(namespace) + "}" + local;
    }

    private static String nullToEmpty(@Nullable final String s) {
        return s == null ? XMLConstants.NULL_NS_URI : s;
    }
}
