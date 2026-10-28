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

import org.apache.xerces.xs.StringList;
import org.apache.xerces.xs.XSAttributeDeclaration;
import org.apache.xerces.xs.XSComplexTypeDefinition;
import org.apache.xerces.xs.XSConstants;
import org.apache.xerces.xs.XSElementDeclaration;
import org.apache.xerces.xs.XSObjectList;
import org.apache.xerces.xs.XSSimpleTypeDefinition;
import org.apache.xerces.xs.XSTypeDefinition;
import org.apache.xerces.xs.XSWildcard;
import org.exist.dom.QName;
import org.exist.numbering.NodeId;
import org.exist.xquery.schema.ChildResolution;
import org.exist.xquery.schema.CompiledSchema;
import org.exist.xquery.schema.ValueChecker;
import org.exist.xquery.value.Type;
import org.w3c.dom.Node;

import javax.annotation.Nullable;
import javax.xml.XMLConstants;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Assigns schema types to the elements and attributes of a document while it is being stored (spec §3, §6.2).
 *
 * <p>A pure state machine, fed the document's events in document order. It does not validate the document:
 * by XSD's <em>Element Declarations Consistent</em> constraint a node's type is a function of its parent's type,
 * its name and its {@code xsi:type}, so a single top-down pass with a stack suffices. Content model order and
 * occurrence constraints are not checked; simple-content values are checked when a {@link ValueChecker} is
 * given.</p>
 *
 * <p>An element's resolution is deferred until the first event after its attributes (the next
 * {@link #startElement}, {@link #characters} or {@link #endElement}), because {@code xsi:type} and
 * {@code xsi:nil} arrive as attributes. Nodes inside an unassignable subtree, a {@code skip} wildcard, or a
 * {@code lax} wildcard without a global declaration get no assignment; consumers treat them as
 * {@code xs:anyType} elements and {@code xs:untypedAtomic} attributes. Only the root of an unassignable
 * subtree is reported, as {@link Status#UNASSIGNABLE}. A failure of an attribute (not allowed, or an
 * invalid value) makes only that attribute unassignable. The {@code xsi:*} attributes themselves are
 * consumed and get no assignment, as do {@code xml:*} attributes the element's type does not declare or admit.
 * A document whose root element is not in one of the schema's target namespaces is outside the schema and
 * gets no assignments at all.</p>
 *
 * <p>Not thread safe: use one instance per document.</p>
 */
public final class TypeAssigner {

    private static final String XSI_NS = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;

    public enum Status { ANNOTATED, UNASSIGNABLE }

    /**
     * The type assigned to a node.
     *
     * @param nodeId   the node's id
     * @param nodeKind {@link Node#ELEMENT_NODE} or {@link Node#ATTRIBUTE_NODE}
     * @param typeCode the {@link Type} code of the node's type ({@link Type#ANY_TYPE} when unassignable)
     * @param nilled   true for an element with {@code xsi:nil="true"}
     * @param status   whether the node could be typed
     * @param reason   why the node is unassignable, null when annotated
     * @param path     the node's path: prefixed names with 1-based same-name sibling positions,
     *                 e.g. {@code /t:trades[1]/t:trade[1]/t:party[1]/@id}
     */
    public record Assignment(NodeId nodeId, int nodeKind, int typeCode, boolean nilled, Status status,
            @Nullable String reason, String path) {
    }

    /** an assignment being built; queued in document order, drained once done */
    private static final class Slot {
        private final NodeId nodeId;
        private final int nodeKind;
        private final String path;
        private int typeCode;
        private boolean nilled;
        private Status status;
        private @Nullable String reason;
        private boolean done;
        private boolean dropped;

        private Slot(final NodeId nodeId, final int nodeKind, final String path, final int typeCode, final boolean nilled,
                final Status status, @Nullable final String reason, final boolean done) {
            this.nodeId = nodeId;
            this.nodeKind = nodeKind;
            this.path = path;
            this.typeCode = typeCode;
            this.nilled = nilled;
            this.status = status;
            this.reason = reason;
            this.done = done;
        }

        private Assignment toAssignment() {
            return new Assignment(nodeId, nodeKind, typeCode, nilled, status, reason, path);
        }
    }

    private record PendingAttribute(NodeId id, QName name, String value) {
    }

    /** an open element which is (or, while pending, will be) annotated */
    private static final class Frame {
        private final @Nullable Frame parent;
        private final NodeId id;
        private final QName name;
        private final String path;
        private final Function<String, String> prefixToUri;

        // collected while the start tag is pending
        private @Nullable String xsiType;
        private @Nullable String xsiNil;
        private @Nullable List<PendingAttribute> attributes;

        // set on resolution
        private @Nullable XSTypeDefinition type;
        private int typeCode;
        private boolean nilled;
        private boolean simpleContent;
        private @Nullable Slot slot;
        private @Nullable List<Slot> attributeSlots;
        /** the text content, collected only for a value check */
        private @Nullable StringBuilder text;
        private boolean hasNonWhitespaceText;
        private boolean hasChildElements;
        private @Nullable Map<QName, Integer> childCounts;

        private Frame(@Nullable final Frame parent, final NodeId id, final QName name, final String path,
                final Function<String, String> prefixToUri) {
            this.parent = parent;
            this.id = id;
            this.name = name;
            this.path = path;
            this.prefixToUri = prefixToUri;
        }

        private int nextPosition(final QName child) {
            if (childCounts == null) {
                childCounts = new HashMap<>();
            }
            return childCounts.merge(child, 1, Integer::sum);
        }
    }

    private final CompiledSchema schema;
    private final @Nullable ValueChecker checker;

    /** the open annotated elements; the top may be pending */
    private final Deque<Frame> stack = new ArrayDeque<>();
    /** the top of the stack while its start tag has not been resolved */
    private @Nullable Frame pending;
    /** the depth of open elements inside an unannotated subtree, including its root */
    private int silentDepth;
    private final Deque<Slot> out = new ArrayDeque<>();

    /**
     * @param schema  the schema governing the documents
     * @param checker checks simple-content and attribute values, or null to skip value checks
     *                ({@code check-values="false"})
     */
    public TypeAssigner(final CompiledSchema schema, @Nullable final ValueChecker checker) {
        this.schema = schema;
        this.checker = checker;
    }

    /**
     * Start an element. Its attributes follow as {@link #attribute} calls.
     *
     * @param id          the element's node id
     * @param name        the element's name (its prefix is used for the path)
     * @param prefixToUri resolves a prefix of the element's in-scope namespaces to its URI ({@code ""} for the
     *                    default namespace), or returns null for an unbound prefix; used for {@code xsi:type}
     */
    public void startElement(final NodeId id, final QName name, final Function<String, String> prefixToUri) {
        resolvePending();
        if (silentDepth > 0) {
            silentDepth++;
            return;
        }
        final Frame parent = stack.peek();
        final int position = parent == null ? 1 : parent.nextPosition(name);
        final String path = (parent == null ? "" : parent.path) + '/' + name.getStringValue() + '[' + position + ']';
        final Frame frame = new Frame(parent, id, name, path, prefixToUri);
        stack.push(frame);
        pending = frame;
    }

    /**
     * An attribute of the element most recently started.
     *
     * @param id    the attribute's node id
     * @param name  the attribute's name
     * @param value the attribute's value
     */
    public void attribute(final NodeId id, final QName name, final String value) {
        if (silentDepth > 0) {
            return;
        }
        final Frame frame = pending;
        if (frame == null) {
            throw new IllegalStateException("attribute " + name + " outside of a start tag");
        }
        if (XSI_NS.equals(name.getNamespaceURI())) {
            switch (name.getLocalPart()) {
                case "type" -> frame.xsiType = value;
                case "nil" -> frame.xsiNil = value;
                default -> { /* xsi:schemaLocation, xsi:noNamespaceSchemaLocation: consumed */ }
            }
            return;
        }
        if (frame.attributes == null) {
            frame.attributes = new ArrayList<>();
        }
        frame.attributes.add(new PendingAttribute(id, name, value));
    }

    /**
     * Character content of the current element.
     *
     * @param text the characters
     */
    public void characters(final CharSequence text) {
        resolvePending();
        if (silentDepth > 0) {
            return;
        }
        final Frame frame = stack.peek();
        if (frame == null) {
            return;
        }
        if (frame.text != null) {
            frame.text.append(text);
        }
        if (!frame.hasNonWhitespaceText && !isWhitespace(text)) {
            frame.hasNonWhitespaceText = true;
        }
    }

    /**
     * End the current element.
     */
    public void endElement() {
        resolvePending();
        if (silentDepth > 0) {
            silentDepth--;
            return;
        }
        final Frame frame = stack.pop();
        final Slot slot = frame.slot;
        if (slot == null || slot.done) {
            return;
        }

        // deferred: an element with simple content, or a nilled element
        final String reason;
        if (frame.nilled) {
            reason = frame.hasChildElements || frame.hasNonWhitespaceText
                    ? "nilled element " + frame.name.getStringValue() + " has content" : null;
        } else if (frame.hasChildElements) {
            reason = "element content not allowed in " + typeName(frame.typeCode);
        } else if (frame.text != null && !checker.isValid(frame.typeCode, frame.text.toString())) {
            reason = "invalid value for " + typeName(frame.typeCode);
        } else {
            reason = null;
        }
        if (reason != null) {
            slot.status = Status.UNASSIGNABLE;
            slot.reason = reason;
            slot.typeCode = Type.ANY_TYPE;
            slot.nilled = false;
            // the subtree of an unassignable node is unannotated, attributes included
            if (frame.attributeSlots != null) {
                for (final Slot attributeSlot : frame.attributeSlots) {
                    attributeSlot.dropped = true;
                }
            }
        }
        slot.done = true;
    }

    /**
     * @return the assignments completed since the last call, in document order
     */
    public List<Assignment> drain() {
        if (out.isEmpty() || !out.peekFirst().done) {
            return List.of();
        }
        final List<Assignment> result = new ArrayList<>();
        while (!out.isEmpty() && out.peekFirst().done) {
            final Slot slot = out.pollFirst();
            if (!slot.dropped) {
                result.add(slot.toAssignment());
            }
        }
        return result;
    }

    // --- resolution ---

    private void resolvePending() {
        final Frame frame = pending;
        if (frame != null) {
            pending = null;
            resolve(frame);
        }
    }

    /**
     * Resolve the type of a pending element, the top of the stack, and of its attributes.
     */
    private void resolve(final Frame frame) {
        final Frame parent = frame.parent;
        final XSElementDeclaration decl;
        if (parent == null) {
            if (!schema.targetNamespaces().contains(frame.name.getNamespaceURI())) {
                // the document is outside the schema: nothing is annotated
                silent();
                return;
            }
            final Optional<XSElementDeclaration> global = schema.globalElement(frame.name);
            if (global.isEmpty()) {
                unassignable(frame, "no global element declaration for " + frame.name.getStringValue());
                return;
            }
            if (global.get().getAbstract()) {
                unassignable(frame, "abstract element " + frame.name.getStringValue());
                return;
            }
            decl = global.get();
        } else {
            if (parent.nilled || parent.simpleContent) {
                // the parent becomes unassignable at its end, so its subtree is unannotated
                parent.hasChildElements = true;
                silent();
                return;
            }
            final ChildResolution resolution = schema.resolveChild((XSComplexTypeDefinition) parent.type, frame.name);
            switch (resolution.kind()) {
                case NOT_ALLOWED -> {
                    unassignable(frame, "element " + frame.name.getStringValue() + " not allowed here");
                    return;
                }
                case WILDCARD -> {
                    if (resolution.decl() == null) {
                        // skip, or lax without a global declaration
                        silent();
                        return;
                    }
                }
                default -> { }
            }
            decl = resolution.decl();
        }

        XSTypeDefinition type = decl.getTypeDefinition();
        if (frame.xsiType != null) {
            final String lexical = frame.xsiType.trim();
            final XSTypeDefinition xsiType = resolveXsiType(lexical, frame.prefixToUri);
            if (xsiType == null) {
                unassignable(frame, "unknown xsi:type " + lexical);
                return;
            }
            if (!validlyDerived(xsiType, type, blockingSet(decl, type))) {
                unassignable(frame, "xsi:type " + lexical + " is not validly derived from " + typeName(schema.typeCode(type)));
                return;
            }
            type = xsiType;
        }
        if (type instanceof XSComplexTypeDefinition complex && complex.getAbstract()) {
            unassignable(frame, "abstract type " + typeName(schema.typeCode(type)));
            return;
        }

        boolean nilled = false;
        if (frame.xsiNil != null) {
            switch (frame.xsiNil.trim()) {
                case "true", "1" -> {
                    if (!decl.getNillable()) {
                        unassignable(frame, "xsi:nil on non-nillable element " + frame.name.getStringValue());
                        return;
                    }
                    nilled = true;
                }
                case "false", "0" -> { }
                default -> {
                    unassignable(frame, "invalid xsi:nil value " + frame.xsiNil);
                    return;
                }
            }
        }

        final int code = schema.typeCode(type);
        frame.type = type;
        frame.typeCode = code;
        frame.nilled = nilled;
        frame.simpleContent = isSimpleContent(type);
        if (frame.simpleContent && checker != null && !nilled) {
            frame.text = new StringBuilder();
        }
        final boolean deferred = frame.simpleContent || nilled;
        final Slot slot = new Slot(frame.id, Node.ELEMENT_NODE, frame.path, code, nilled, Status.ANNOTATED, null, !deferred);
        out.addLast(slot);
        frame.slot = slot;

        if (frame.attributes != null) {
            for (final PendingAttribute attribute : frame.attributes) {
                final Slot attributeSlot = resolveAttribute(frame, attribute);
                if (attributeSlot != null) {
                    out.addLast(attributeSlot);
                    if (deferred) {
                        if (frame.attributeSlots == null) {
                            frame.attributeSlots = new ArrayList<>();
                        }
                        frame.attributeSlots.add(attributeSlot);
                    }
                }
            }
            frame.attributes = null;
        }
    }

    /**
     * @return the attribute's assignment, or null if it is not annotated (skip or lax attribute wildcard, or an
     *         undeclared {@code xml:*} attribute)
     */
    private @Nullable Slot resolveAttribute(final Frame owner, final PendingAttribute attribute) {
        final String path = owner.path + "/@" + attribute.name().getStringValue();
        if (owner.type instanceof XSComplexTypeDefinition complex) {
            final Optional<XSAttributeDeclaration> decl = schema.resolveAttribute(complex, attribute.name());
            if (decl.isPresent()) {
                final int code = schema.typeCode(decl.get().getTypeDefinition());
                if (checker != null && !checker.isValid(code, attribute.value())) {
                    return new Slot(attribute.id(), Node.ATTRIBUTE_NODE, path, Type.ANY_TYPE, false, Status.UNASSIGNABLE,
                            "invalid value for " + typeName(code), true);
                }
                return new Slot(attribute.id(), Node.ATTRIBUTE_NODE, path, code, false, Status.ANNOTATED, null, true);
            }
            final XSWildcard wildcard = complex.getAttributeWildcard();
            if (wildcard != null && wildcard.getProcessContents() != XSWildcard.PC_STRICT
                    && allowsNamespace(wildcard, attribute.name().getNamespaceURI())) {
                return null;
            }
        }
        if (XMLConstants.XML_NS_URI.equals(attribute.name().getNamespaceURI())) {
            // xml:lang, xml:space, xml:base, xml:id not declared by the type: unannotated, not unassignable
            return null;
        }
        return new Slot(attribute.id(), Node.ATTRIBUTE_NODE, path, Type.ANY_TYPE, false, Status.UNASSIGNABLE,
                "attribute " + attribute.name().getStringValue() + " not allowed", true);
    }

    /**
     * Report the pending element as the root of an unassignable subtree.
     */
    private void unassignable(final Frame frame, final String reason) {
        out.addLast(new Slot(frame.id, Node.ELEMENT_NODE, frame.path, Type.ANY_TYPE, false, Status.UNASSIGNABLE, reason, true));
        silent();
    }

    /**
     * Make the pending element, the top of the stack, the root of an unannotated subtree.
     */
    private void silent() {
        stack.pop();
        silentDepth = 1;
    }

    private @Nullable XSTypeDefinition resolveXsiType(final String lexical, final Function<String, String> prefixToUri) {
        final int colon = lexical.indexOf(':');
        final String prefix = colon < 0 ? "" : lexical.substring(0, colon);
        final String local = lexical.substring(colon + 1);
        if (local.isEmpty() || local.indexOf(':') >= 0 || (colon >= 0 && prefix.isEmpty())) {
            return null;
        }
        String uri = prefixToUri.apply(prefix);
        if (uri == null) {
            if (!prefix.isEmpty()) {
                return null;
            }
            uri = XMLConstants.NULL_NS_URI;
        }
        return schema.namedType(new QName(local, uri)).orElse(null);
    }

    /**
     * The derivation methods which may not be used to derive an {@code xsi:type} from the declared type:
     * the element's {@code block} plus the declared complex type's {@code block}.
     */
    private static short blockingSet(final XSElementDeclaration decl, final XSTypeDefinition declared) {
        short block = decl.getDisallowedSubstitutions();
        if (declared instanceof XSComplexTypeDefinition complex) {
            block |= complex.getProhibitedSubstitutions();
        }
        return block;
    }

    /**
     * Type Derivation OK: {@code type} is {@code declared}, or derived from it by steps none of which uses a
     * blocked method, or a member type of a union {@code declared}.
     */
    private static boolean validlyDerived(final XSTypeDefinition type, final XSTypeDefinition declared, final short block) {
        XSTypeDefinition current = type;
        while (current != null) {
            if (sameType(current, declared)) {
                return true;
            }
            final short method = current instanceof XSComplexTypeDefinition complex
                    ? complex.getDerivationMethod() : XSConstants.DERIVATION_RESTRICTION;
            if ((method & block) != 0) {
                return false;
            }
            final XSTypeDefinition base = current.getBaseType();
            if (base == current) {
                break;
            }
            current = base;
        }
        return declared instanceof XSSimpleTypeDefinition simple && isUnionMember(type, simple);
    }

    private static boolean isUnionMember(final XSTypeDefinition type, final XSSimpleTypeDefinition union) {
        if (union.getVariety() != XSSimpleTypeDefinition.VARIETY_UNION) {
            return false;
        }
        final XSObjectList members = union.getMemberTypes();
        for (int i = 0; i < members.getLength(); i++) {
            final XSSimpleTypeDefinition member = (XSSimpleTypeDefinition) members.item(i);
            if (validlyDerived(type, member, (short) 0) || isUnionMember(type, member)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameType(final XSTypeDefinition a, final XSTypeDefinition b) {
        if (a == b) {
            return true;
        }
        return !a.getAnonymous() && !b.getAnonymous() && a.getName() != null && a.getName().equals(b.getName())
                && nullToEmpty(a.getNamespace()).equals(nullToEmpty(b.getNamespace()));
    }

    private static boolean isSimpleContent(final XSTypeDefinition type) {
        return type instanceof XSSimpleTypeDefinition
                || ((XSComplexTypeDefinition) type).getContentType() == XSComplexTypeDefinition.CONTENTTYPE_SIMPLE;
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

    private static String typeName(final int typeCode) {
        final String name = Type.getTypeName(typeCode);
        return name != null ? name : String.valueOf(typeCode);
    }

    private static String nullToEmpty(@Nullable final String s) {
        return s == null ? "" : s;
    }

    private static boolean isWhitespace(final CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
    }
}
