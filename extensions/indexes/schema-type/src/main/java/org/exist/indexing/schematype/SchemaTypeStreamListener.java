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

import org.apache.xerces.xs.XSTypeDefinition;
import org.exist.dom.QName;
import org.exist.dom.persistent.AbstractCharacterData;
import org.exist.dom.persistent.AttrImpl;
import org.exist.dom.persistent.ElementImpl;
import org.exist.indexing.AbstractStreamListener;
import org.exist.indexing.IndexWorker;
import org.exist.numbering.NodeId;
import org.exist.storage.NodePath;
import org.exist.storage.txn.Txn;
import org.exist.xquery.ErrorCodes;
import org.exist.xquery.schema.ValueChecker;
import org.w3c.dom.Node;

import javax.annotation.Nullable;
import javax.xml.XMLConstants;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Receives the nodes of a document of a collection with a schema and collects the work for the next
 * {@link SchemaTypeIndexWorker#flush()}.
 *
 * <ul>
 *   <li>{@link ReindexMode#STORE} from the document's root element (storing, reindexing): a <em>full pass</em>.
 *       The nodes are fed to a {@link TypeAssigner}; the document's annotations are replaced by the assignments.</li>
 *   <li>{@link ReindexMode#STORE} of a subtree (the reindex root of an update, see
 *       {@link SchemaTypeIndexWorker#getReindexRoot}, or a single attribute): a <em>seeded pass</em>. The assigner
 *       is seeded with the stored annotation, path and in-scope namespaces of the subtree root's parent; the
 *       subtree's annotations are replaced by the assignments.</li>
 *   <li>{@link ReindexMode#REMOVE_SOME_NODES}: the entries of the subtrees received are removed.</li>
 * </ul>
 *
 * <p>{@link ReindexMode#REMOVE_ALL_NODES} needs no events: the worker removes the document's entries on flush.</p>
 */
final class SchemaTypeStreamListener extends AbstractStreamListener {

    enum PassKind { NONE, FULL, SEEDED, REMOVE }

    /** an assignment with the lexical value of its node, when a typed value may be needed */
    record Pending(TypeAssigner.Assignment assignment, @Nullable String lexical, @Nullable TypeIndexStore.NameKey name) {
    }

    /** a node received in a pass, until its assignment is drained */
    private record Received(TypeIndexStore.NameKey name, NodeId id, short kind) {
    }

    /**
     * What the assigner needs to know about the parent of a subtree root.
     *
     * @param parentType  the parent's annotated type, or null if it is not annotated
     * @param parentNilled whether the parent is nilled
     * @param parentPath  the parent's path
     * @param preceding   the parent's element children before the subtree root with the root's name (empty for an
     *                    attribute)
     * @param namespaces  the namespaces in scope at the parent
     */
    record Seed(@Nullable XSTypeDefinition parentType, boolean parentNilled, String parentPath,
            Map<QName, Integer> preceding, Map<String, String> namespaces) {
        static final Seed UNANNOTATED = new Seed(null, false, "", Map.of(), Map.of());
    }

    /** an attribute value kept until the attribute's assignment is drained */
    private record AttrValue(NodeId id, String value) {
    }

    private final SchemaTypeIndexWorker worker;

    private PassKind kind = PassKind.NONE;
    private int docId = -1;

    // assignment passes
    private @Nullable SchemaTypeConfig config;
    private @Nullable TypeAssigner assigner;
    private @Nullable NodeId rootId;
    private @Nullable String rootPath;
    private boolean collectValues;
    private final Deque<Map<String, String>> namespaces = new ArrayDeque<>();
    private final Deque<StringBuilder> texts = new ArrayDeque<>();
    private final List<AttrValue> attrValues = new ArrayList<>();
    private final List<Pending> pending = new ArrayList<>();
    /** the names of the nodes of the pass whose assignment has not been drained, by node id */
    private final Map<String, Received> undrained = new HashMap<>();
    /** the nodes of the pass known to be untyped, by name */
    private final Map<TypeIndexStore.NameKey, List<TypeIndexStore.Member>> untyped = new HashMap<>();
    /** the flags of the names of the nodes received since the last {@link #takeNames()} */
    private final Map<TypeIndexStore.NameKey, Byte> names = new HashMap<>();
    /** per open element of the pass: the number of its element children by name */
    private final Deque<Map<TypeIndexStore.NameKey, Integer>> childNames = new ArrayDeque<>();
    private boolean clearBeforeWrite;
    private boolean rootEnded;
    private final List<String> unassignablePaths = new ArrayList<>();
    private int unassignableCount;

    // subtree removal
    private final List<NodeId> removalRoots = new ArrayList<>();
    private int depth;

    SchemaTypeStreamListener(final SchemaTypeIndexWorker worker) {
        this.worker = worker;
    }

    @Override
    public IndexWorker getWorker() {
        return worker;
    }

    PassKind kind() {
        return kind;
    }

    int docId() {
        return docId;
    }

    // --- events ---

    @Override
    public void startElement(final Txn transaction, final ElementImpl element, final NodePath path) {
        if (!inPass()) {
            final ReindexMode mode = worker.getMode();
            if (mode == ReindexMode.STORE) {
                flushPending();
                if (element.getNodeId().getTreeLevel() == 1) {
                    beginPass(PassKind.FULL, element.getNodeId(), null, null);
                } else {
                    final Seed seed = worker.seedFor(element);
                    final Integer preceding = seed.preceding().get(element.getQName());
                    final String path0 = seed.parentType() == null ? null : seed.parentPath() + '/'
                            + element.getQName().getStringValue() + '[' + ((preceding == null ? 0 : preceding) + 1) + ']';
                    if (beginPass(PassKind.SEEDED, element.getNodeId(), path0, seed.namespaces())) {
                        assigner.startSubtree(seed.parentType(), seed.parentNilled(), seed.parentPath(), seed.preceding());
                    }
                }
            } else if (mode == ReindexMode.REMOVE_SOME_NODES) {
                removalNode(element.getNodeId());
                depth++;
            }
        }
        if (inPass()) {
            final Map<String, String> parent = namespaces.peek();
            Map<String, String> scope = parent == null ? Map.of() : parent;
            final Iterator<String> prefixes = element.getPrefixes();
            if (prefixes.hasNext()) {
                final Map<String, String> declared = new HashMap<>(scope);
                while (prefixes.hasNext()) {
                    final String prefix = prefixes.next();
                    declared.put(prefix, element.getNamespaceForPrefix(prefix));
                }
                scope = declared;
            }
            namespaces.push(scope);
            if (collectValues) {
                texts.push(new StringBuilder());
            }
            final Map<String, String> inScope = scope;
            final QName name = element.getQName();
            final TypeIndexStore.NameKey key = TypeIndexStore.NameKey.of(Node.ELEMENT_NODE, name);
            byte flags = 0;
            if (childNames.isEmpty()) {
                // the root of a seeded pass: its siblings are not received
                if (kind == PassKind.SEEDED) {
                    flags = TypeIndexStore.SUMMARY_REPEATED;
                }
            } else if (childNames.peek().merge(key, 1, Integer::sum) > 1) {
                flags = TypeIndexStore.SUMMARY_REPEATED;
            }
            childNames.push(new HashMap<>());
            names.merge(key, flags, (a, b) -> (byte) (a | b));
            undrained.put(element.getNodeId().toString(), new Received(key, element.getNodeId(), Node.ELEMENT_NODE));
            assigner.startElement(element.getNodeId(), name, prefix -> resolvePrefix(inScope, name, prefix));
            drain(null, null);
        }
        super.startElement(transaction, element, path);
    }

    @Override
    public void attribute(final Txn transaction, final AttrImpl attrib, final NodePath path) {
        if (inPass()) {
            final String value = attrib.getValue();
            final TypeIndexStore.NameKey key = TypeIndexStore.NameKey.of(Node.ATTRIBUTE_NODE, attrib.getQName());
            names.merge(key, (byte) 0, (a, b) -> (byte) (a | b));
            undrained.put(attrib.getNodeId().toString(), new Received(key, attrib.getNodeId(), Node.ATTRIBUTE_NODE));
            assigner.attribute(attrib.getNodeId(), attrib.getQName(), value);
            if (collectValues) {
                attrValues.add(new AttrValue(attrib.getNodeId(), value));
            }
        } else {
            final ReindexMode mode = worker.getMode();
            if (mode == ReindexMode.STORE) {
                // an attribute on its own (e.g. a changed value): assign it from its owner's type
                flushPending();
                final Seed seed = worker.seedFor(attrib);
                final String path0 = seed.parentType() == null ? null
                        : seed.parentPath() + "/@" + attrib.getQName().getStringValue();
                if (beginPass(PassKind.SEEDED, attrib.getNodeId(), path0, Map.of())) {
                    final TypeAssigner.Assignment a = assigner.assignAttribute(seed.parentType(), seed.parentPath(),
                            attrib.getNodeId(), attrib.getQName(), attrib.getValue());
                    final TypeIndexStore.NameKey key = TypeIndexStore.NameKey.of(Node.ATTRIBUTE_NODE, attrib.getQName());
                    if (a != null) {
                        rejectIfRequired(a);
                        pending.add(new Pending(a, collectValues ? attrib.getValue() : null, key));
                        names.merge(key, (byte) 0, (x, y) -> (byte) (x | y));
                    } else {
                        names.merge(key, (byte) 0, (x, y) -> (byte) (x | y));
                        untyped.computeIfAbsent(key, k -> new ArrayList<>())
                                .add(new TypeIndexStore.Member(attrib.getNodeId(), Node.ATTRIBUTE_NODE));
                    }
                    rootEnded = true;
                }
            } else if (mode == ReindexMode.REMOVE_SOME_NODES) {
                removalNode(attrib.getNodeId());
            }
        }
        super.attribute(transaction, attrib, path);
    }

    @Override
    public void characters(final Txn transaction, final AbstractCharacterData text, final NodePath path) {
        if (inPass()) {
            final short type = text.getNodeType();
            if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
                final String data = text.getData();
                assigner.characters(data);
                if (collectValues && !texts.isEmpty()) {
                    texts.peek().append(data);
                }
                drain(null, null);
            }
        }
        super.characters(transaction, text, path);
    }

    @Override
    public void endElement(final Txn transaction, final ElementImpl element, final NodePath path) {
        if (inPass()) {
            final String text = collectValues && !texts.isEmpty() ? texts.pop().toString() : null;
            assigner.endElement();
            drain(element.getNodeId(), text);
            if (!childNames.isEmpty()) {
                childNames.pop();
            }
            attrValues.clear();
            namespaces.pop();
            if (element.getNodeId().equals(rootId)) {
                rootEnded = true;
            }
        } else if (kind == PassKind.REMOVE && depth > 0) {
            depth--;
        }
        super.endElement(transaction, element, path);
    }

    /** true while the nodes of an assignment pass are being received */
    private boolean inPass() {
        return (kind == PassKind.FULL || kind == PassKind.SEEDED) && !rootEnded;
    }

    // --- passes ---

    /** hand the work collected so far to the worker before a new stream starts */
    private void flushPending() {
        if (kind != PassKind.NONE) {
            worker.flush();
        }
    }

    /**
     * @return false if the collection has no configuration (the stream is ignored)
     */
    private boolean beginPass(final PassKind passKind, final NodeId root, @Nullable final String path,
            @Nullable final Map<String, String> seedNamespaces) {
        final SchemaTypeConfig cfg = worker.currentConfig();
        reset();
        if (cfg == null) {
            return false;
        }
        worker.verifySchemaAvailable(cfg);
        kind = passKind;
        docId = worker.getDocument().getDocId();
        config = cfg;
        rootId = root;
        rootPath = path;
        assigner = new TypeAssigner(cfg.schema(), cfg.checkValues() ? new ValueChecker(cfg.schema()) : null);
        collectValues = !cfg.typedValueTypes().isEmpty();
        clearBeforeWrite = true;
        if (seedNamespaces != null) {
            namespaces.push(seedNamespaces);
        }
        return true;
    }

    private void removalNode(final NodeId id) {
        if (kind != PassKind.REMOVE) {
            flushPending();
            reset();
            kind = PassKind.REMOVE;
            docId = worker.getDocument().getDocId();
        }
        if (depth == 0) {
            removalRoots.add(id);
        }
    }

    private void drain(@Nullable final NodeId endedElement, @Nullable final String endedText) {
        for (final TypeAssigner.Assignment assignment : assigner.drain()) {
            rejectIfRequired(assignment);
            String lexical = null;
            if (collectValues && assignment.status() == TypeAssigner.Status.ANNOTATED) {
                if (assignment.nodeKind() == Node.ATTRIBUTE_NODE) {
                    for (final AttrValue value : attrValues) {
                        if (value.id().equals(assignment.nodeId())) {
                            lexical = value.value();
                            break;
                        }
                    }
                } else if (endedElement != null && endedElement.equals(assignment.nodeId())) {
                    lexical = endedText;
                }
            }
            final Received received = undrained.remove(assignment.nodeId().toString());
            pending.add(new Pending(assignment, lexical, received == null ? null : received.name()));
        }
    }

    /** with {@code on-unassignable="reject"} an unassignable node fails the store (EXSA0004) */
    private void rejectIfRequired(final TypeAssigner.Assignment assignment) {
        // Everything but an explicit reindex or repair rejects (store, copy, move, update, root replace): there the
        // node is left untyped and the document flagged partially typed instead.
        if (assignment.status() == TypeAssigner.Status.UNASSIGNABLE && config != null
                && config.onUnassignable() == OnUnassignable.REJECT && !worker.isExplicitReindex()) {
            final String path = assignment.path();
            final String reason = assignment.reason();
            final String uri = worker.getDocument() == null ? "" : " in " + worker.getDocument().getURI();
            reset();
            throw new SchemaTypeException(ErrorCodes.EXSA0004, "node " + path + uri + " cannot be assigned a type: "
                    + (reason == null ? "unknown reason" : reason));
        }
    }

    private static @Nullable String resolvePrefix(final Map<String, String> scope, final QName element, final String prefix) {
        if (XMLConstants.XML_NS_PREFIX.equals(prefix)) {
            return XMLConstants.XML_NS_URI;
        }
        final String uri = scope.get(prefix);
        if (uri != null) {
            return uri;
        }
        final String elementPrefix = element.getPrefix() == null ? "" : element.getPrefix();
        if (elementPrefix.equals(prefix)) {
            return element.getNamespaceURI();
        }
        return null;
    }

    // --- hand-over to the worker ---

    @Nullable SchemaTypeConfig config() {
        return config;
    }

    @Nullable NodeId rootId() {
        return rootId;
    }

    /** the path of the root of a seeded pass, null if its parent is not annotated */
    @Nullable String rootPath() {
        return rootPath;
    }

    /** the assignments drained since the last call */
    List<Pending> takePending() {
        final List<Pending> result = new ArrayList<>(pending);
        pending.clear();
        return result;
    }

    /**
     * The names of the nodes received since the last call, with their flags ({@link TypeIndexStore#SUMMARY_REPEATED}).
     */
    Map<TypeIndexStore.NameKey, Byte> takeNames() {
        final Map<TypeIndexStore.NameKey, Byte> result = new HashMap<>(names);
        names.clear();
        return result;
    }

    /**
     * The nodes known to be untyped since the last call, by name: once the root has ended, those which got no
     * assignment (e.g. below an unannotated parent, or the content of a skip wildcard).
     */
    Map<TypeIndexStore.NameKey, List<TypeIndexStore.Member>> takeUntyped() {
        if (rootEnded) {
            for (final Received node : undrained.values()) {
                untyped.computeIfAbsent(node.name(), k -> new ArrayList<>()).add(new TypeIndexStore.Member(node.id(), node.kind()));
            }
            undrained.clear();
        }
        final Map<TypeIndexStore.NameKey, List<TypeIndexStore.Member>> result = new HashMap<>(untyped);
        untyped.clear();
        return result;
    }

    /** true once: whether the old entries must be cleared before the first write of the pass */
    boolean takeClear() {
        final boolean clear = clearBeforeWrite;
        clearBeforeWrite = false;
        return clear;
    }

    boolean rootEnded() {
        return rootEnded;
    }

    /** record the paths of unassignable nodes written by the worker */
    void recordUnassignable(final List<String> paths) {
        unassignableCount += paths.size();
        for (final String path : paths) {
            if (unassignablePaths.size() >= DocMeta.MAX_PATHS) {
                break;
            }
            unassignablePaths.add(path);
        }
    }

    List<String> unassignablePaths() {
        return List.copyOf(unassignablePaths);
    }

    int unassignableCount() {
        return unassignableCount;
    }

    List<NodeId> removalRoots() {
        return removalRoots;
    }

    /** forget the current pass */
    void reset() {
        kind = PassKind.NONE;
        docId = -1;
        config = null;
        assigner = null;
        rootId = null;
        rootPath = null;
        collectValues = false;
        namespaces.clear();
        texts.clear();
        attrValues.clear();
        pending.clear();
        undrained.clear();
        untyped.clear();
        names.clear();
        childNames.clear();
        clearBeforeWrite = false;
        rootEnded = false;
        unassignablePaths.clear();
        unassignableCount = 0;
        removalRoots.clear();
        depth = 0;
    }
}
