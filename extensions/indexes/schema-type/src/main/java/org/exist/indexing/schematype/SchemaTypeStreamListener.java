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

import org.exist.dom.QName;
import org.exist.dom.persistent.AbstractCharacterData;
import org.exist.dom.persistent.AttrImpl;
import org.exist.dom.persistent.ElementImpl;
import org.exist.indexing.AbstractStreamListener;
import org.exist.indexing.IndexWorker;
import org.exist.numbering.NodeId;
import org.exist.storage.NodePath;
import org.exist.storage.txn.Txn;
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
 *   <li>{@link ReindexMode#STORE} from the document's root element (storing, reindexing, or the full
 *       reassignment requested by {@link SchemaTypeIndexWorker#getReindexRoot}): a <em>full pass</em>. The nodes are
 *       fed to a {@link TypeAssigner}; the document's annotations are replaced by the assignments.</li>
 *   <li>{@link ReindexMode#STORE} of a subtree, or {@link ReindexMode#REMOVE_SOME_NODES}: the entries of the
 *       subtrees received are removed. Node updates of a typed document are always followed by a full pass, since
 *       the worker's reindex root is the root element, so a stored subtree is typed by that pass.</li>
 * </ul>
 *
 * <p>{@link ReindexMode#REMOVE_ALL_NODES} needs no events: the worker removes the document's entries on flush.</p>
 */
final class SchemaTypeStreamListener extends AbstractStreamListener {

    enum PassKind { NONE, FULL, SUBTREES }

    /** an assignment with the lexical value of its node, when a typed value may be needed */
    record Pending(TypeAssigner.Assignment assignment, @Nullable String lexical) {
    }

    /** an attribute value kept until the attribute's assignment is drained */
    private record AttrValue(NodeId id, String value) {
    }

    private final SchemaTypeIndexWorker worker;

    private PassKind kind = PassKind.NONE;
    private int docId = -1;

    // full pass
    private @Nullable SchemaTypeConfig config;
    private @Nullable TypeAssigner assigner;
    private boolean collectValues;
    private final Deque<Map<String, String>> namespaces = new ArrayDeque<>();
    private final Deque<StringBuilder> texts = new ArrayDeque<>();
    private final List<AttrValue> attrValues = new ArrayList<>();
    private final List<Pending> pending = new ArrayList<>();
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
        final ReindexMode mode = worker.getMode();
        final NodeId id = element.getNodeId();
        if (!inFullPass() && mode == ReindexMode.STORE && id.getTreeLevel() == 1) {
            beginFullPass();
        }
        if (inFullPass()) {
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
            assigner.startElement(id, name, prefix -> resolvePrefix(inScope, name, prefix));
            drain(null, null);
        } else if (isSubtreeMode(mode)) {
            subtreeNode(id);
            depth++;
        }
        super.startElement(transaction, element, path);
    }

    @Override
    public void attribute(final Txn transaction, final AttrImpl attrib, final NodePath path) {
        if (inFullPass()) {
            final String value = attrib.getValue();
            assigner.attribute(attrib.getNodeId(), attrib.getQName(), value);
            if (collectValues) {
                attrValues.add(new AttrValue(attrib.getNodeId(), value));
            }
        } else if (isSubtreeMode(worker.getMode())) {
            subtreeNode(attrib.getNodeId());
        }
        super.attribute(transaction, attrib, path);
    }

    @Override
    public void characters(final Txn transaction, final AbstractCharacterData text, final NodePath path) {
        if (inFullPass()) {
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
        if (inFullPass()) {
            final String text = collectValues && !texts.isEmpty() ? texts.pop().toString() : null;
            assigner.endElement();
            drain(element.getNodeId(), text);
            attrValues.clear();
            namespaces.pop();
            if (element.getNodeId().getTreeLevel() == 1) {
                rootEnded = true;
            }
        } else if (kind == PassKind.SUBTREES && depth > 0) {
            depth--;
        }
        super.endElement(transaction, element, path);
    }

    /** true while the nodes of a full pass are being received */
    private boolean inFullPass() {
        return kind == PassKind.FULL && !rootEnded;
    }

    private static boolean isSubtreeMode(final ReindexMode mode) {
        return mode == ReindexMode.STORE || mode == ReindexMode.REMOVE_SOME_NODES;
    }

    // --- passes ---

    private void beginFullPass() {
        if (kind == PassKind.FULL) {
            // a completed pass not yet flushed
            worker.flush();
        }
        final SchemaTypeConfig cfg = worker.currentConfig();
        reset();
        if (cfg == null) {
            return;
        }
        kind = PassKind.FULL;
        docId = worker.getDocument().getDocId();
        config = cfg;
        assigner = new TypeAssigner(cfg.schema(), cfg.checkValues() ? new ValueChecker(cfg.schema()) : null);
        collectValues = !cfg.typedValueTypes().isEmpty();
        clearBeforeWrite = true;
    }

    private void subtreeNode(final NodeId id) {
        if (kind == PassKind.FULL) {
            // a completed pass not yet flushed
            worker.flush();
        }
        if (kind != PassKind.SUBTREES) {
            reset();
            kind = PassKind.SUBTREES;
            docId = worker.getDocument().getDocId();
        }
        if (depth == 0) {
            removalRoots.add(id);
        }
    }

    private void drain(@Nullable final NodeId endedElement, @Nullable final String endedText) {
        for (final TypeAssigner.Assignment assignment : assigner.drain()) {
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
            pending.add(new Pending(assignment, lexical));
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

    /** the assignments drained since the last call */
    List<Pending> takePending() {
        final List<Pending> result = new ArrayList<>(pending);
        pending.clear();
        return result;
    }

    /** true once: whether the document's entries must be cleared before the first write of the pass */
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
        collectValues = false;
        namespaces.clear();
        texts.clear();
        attrValues.clear();
        pending.clear();
        clearBeforeWrite = false;
        rootEnded = false;
        unassignablePaths.clear();
        unassignableCount = 0;
        removalRoots.clear();
        depth = 0;
    }
}
