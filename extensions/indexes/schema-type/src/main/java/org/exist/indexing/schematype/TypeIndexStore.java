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

import org.exist.dom.persistent.DocumentImpl;
import org.exist.dom.persistent.DocumentSet;
import org.exist.dom.persistent.NewArrayNodeSet;
import org.exist.dom.persistent.NodeProxy;
import org.exist.dom.persistent.NodeSet;
import org.exist.numbering.NodeId;
import org.exist.numbering.NodeIdFactory;
import org.exist.storage.btree.BTreeException;
import org.exist.storage.btree.IndexQuery;
import org.exist.storage.btree.Value;
import org.exist.storage.index.BFile;
import org.exist.storage.io.VariableByteArrayInput;
import org.exist.storage.io.VariableByteInput;
import org.exist.storage.io.VariableByteOutputStream;
import org.exist.storage.lock.LockManager;
import org.exist.storage.lock.ManagedLock;
import org.exist.util.ByteConversion;
import org.exist.util.LockException;
import org.exist.util.ReadOnlyException;
import org.exist.xquery.TerminatedException;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The tables of the schema type index, kept in one {@link BFile}. The first byte of every key names its table:
 *
 * <table>
 *   <caption>key layout</caption>
 *   <tr><th>prefix</th><th>key</th><th>value</th></tr>
 *   <tr><td>{@code 0x00}</td><td>collectionId</td><td>{@link CollectionMeta}</td></tr>
 *   <tr><td>{@code 0x01}</td><td>docId</td><td>{@link DocMeta}</td></tr>
 *   <tr><td>{@code 0x02}</td><td>docId, nodeId</td><td>localTypeId (short), flags (bit 0 = nilled)</td></tr>
 *   <tr><td>{@code 0x03}</td><td>localTypeId, docId</td><td>node list</td></tr>
 *   <tr><td>{@code 0x04}</td><td>collectionId, localTypeId, {@link TypedKeys typed key}, docId</td><td>node list</td></tr>
 *   <tr><td>{@code 0x05}</td><td>docId, localTypeId, typed key</td><td>collectionId: the {@code 0x04} keys of a document,
 *       so that removing a document does not scan the typed values of all documents</td></tr>
 *   <tr><td>{@code 0x06}</td><td>docId, localTypeId</td><td>placeholder: the {@code 0x03} keys of a document, so that
 *       they are removed independently of the local type table of the document's current collection</td></tr>
 * </table>
 *
 * <p>Integers are big-endian. A node id in a key is its unit count (short) followed by its serialized bytes.
 * A node list is a sequence of appended chunks, each an int count followed by that many entries of a node
 * kind byte ({@link org.w3c.dom.Node#ELEMENT_NODE} or {@link org.w3c.dom.Node#ATTRIBUTE_NODE}) and a node id
 * written with prefix compression against the previous entry of the chunk.</p>
 *
 * <p>The typed keys of {@code 0x04} preserve the order of the values ({@link TypedKeys}), so that a comparison is a
 * range scan; local type ids are per collection, so the collection id comes first and a scan only reads the entries
 * of one collection. This layout (format 2, introduced with the typed-value comparisons) replaced one keyed by
 * {@code localTypeId} and {@link org.exist.storage.Indexable#serializeValue}, whose decimal keys did not sort
 * numerically: an index written before it must be rebuilt ({@code xmldb:reindex} of the collection); the index is
 * derived data and can always be rebuilt.</p>
 *
 * <p>Every method takes the file's lock. Every write for a document advances that document's generation, which
 * readers use to invalidate cached annotations.</p>
 */
final class TypeIndexStore {

    static final byte COLLECTION_META = 0x00;
    static final byte DOC_META = 0x01;
    static final byte ANNOTATION = 0x02;
    static final byte TYPE_MEMBERS = 0x03;
    static final byte TYPED_VALUES = 0x04;
    static final byte DOC_TYPED_KEYS = 0x05;
    static final byte DOC_MEMBER_KEYS = 0x06;

    private static final byte NILLED = 0x01;
    private static final byte[] EMPTY = {0};

    /** a stored annotation */
    record StoredAnnotation(short localTypeId, boolean nilled) {
    }

    /** a node in a node list */
    record Member(NodeId nodeId, short nodeKind) {
    }

    private final BFile db;
    private final LockManager lockManager;
    private final NodeIdFactory nodeFactory;
    /** write generations, striped by document id to bound memory; a collision only causes a spurious re-read */
    private static final int GENERATION_STRIPES = 4096;
    private final AtomicLongArray generations = new AtomicLongArray(GENERATION_STRIPES);
    /** the number of file accesses, for tests */
    private final AtomicLong accesses = new AtomicLong();

    TypeIndexStore(final BFile db, final LockManager lockManager, final NodeIdFactory nodeFactory) {
        this.db = db;
        this.lockManager = lockManager;
        this.nodeFactory = nodeFactory;
    }

    BFile file() {
        return db;
    }

    /**
     * @param docId a document id
     * @return a number which changes whenever entries of the document are written (and possibly when entries of
     *         another document are written)
     */
    long generation(final int docId) {
        return generations.get(docId & (GENERATION_STRIPES - 1));
    }

    private void touched(final int docId) {
        generations.incrementAndGet(docId & (GENERATION_STRIPES - 1));
    }

    /**
     * @return the number of file accesses so far
     */
    long accessCount() {
        return accesses.get();
    }

    // --- metadata ---

    /**
     * @return the ids of the collections which have metadata
     */
    Set<Integer> collectionIdsWithMeta() throws IOException {
        final byte[] prefix = {COLLECTION_META};
        final Set<Integer> ids = new HashSet<>();
        for (final byte[] key : keys(prefix, prefix)) {
            ids.add(ByteConversion.byteToIntH(key, 1));
        }
        return ids;
    }

    @Nullable CollectionMeta getCollectionMeta(final int collectionId) throws IOException {
        final byte[] data = get(intKey(COLLECTION_META, collectionId));
        return data == null ? null : CollectionMeta.deserialize(data);
    }

    void putCollectionMeta(final int collectionId, final CollectionMeta meta) throws IOException {
        put(intKey(COLLECTION_META, collectionId), meta.serialize());
    }

    void removeCollectionMeta(final int collectionId) throws IOException {
        remove(intKey(COLLECTION_META, collectionId));
    }

    @Nullable DocMeta getDocMeta(final int docId) throws IOException {
        final byte[] data = get(intKey(DOC_META, docId));
        return data == null ? null : DocMeta.deserialize(data);
    }

    void putDocMeta(final int docId, final DocMeta meta) throws IOException {
        put(intKey(DOC_META, docId), meta.serialize());
        touched(docId);
    }

    // --- annotations ---

    void putAnnotation(final int docId, final NodeId id, final short localTypeId, final boolean nilled) throws IOException {
        final byte[] value = new byte[3];
        ByteConversion.shortToByteH(localTypeId, value, 0);
        value[2] = nilled ? NILLED : 0;
        put(annotationKey(docId, id), value);
        touched(docId);
    }

    @Nullable StoredAnnotation getAnnotation(final int docId, final NodeId id) throws IOException {
        final byte[] value = get(annotationKey(docId, id));
        if (value == null) {
            return null;
        }
        return new StoredAnnotation(ByteConversion.byteToShortH(value, 0), (value[2] & NILLED) != 0);
    }

    void addTypeMember(final short localTypeId, final int docId, final NodeId id, final short nodeKind) throws IOException {
        addTypeMembers(localTypeId, docId, List.of(new Member(id, nodeKind)));
    }

    /**
     * Append nodes, in document order, to the type's node list of a document.
     */
    void addTypeMembers(final short localTypeId, final int docId, final List<Member> members) throws IOException {
        if (!members.isEmpty()) {
            append(membersKey(localTypeId, docId), encode(members));
            put(docMemberKey(docId, localTypeId), EMPTY);
            touched(docId);
        }
    }

    /**
     * Append nodes, in document order, to the node list of a typed value of a document.
     *
     * @param collectionId the id of the document's collection, whose local type table {@code localTypeId} is from
     * @param key          the typed key ({@link TypedKeys}), or {@code {UNINDEXABLE}}
     */
    void putTypedValues(final int collectionId, final short localTypeId, final byte[] key, final int docId,
            final List<Member> members) throws IOException {
        if (members.isEmpty()) {
            return;
        }
        append(typedKey(collectionId, localTypeId, key, docId), encode(members));
        final byte[] value = new byte[4];
        ByteConversion.intToByteH(collectionId, value, 0);
        put(docTypedKey(docId, localTypeId, key), value);
        touched(docId);
    }

    // --- queries ---

    /**
     * @return the nodes of the given documents annotated with one of the local types (local ids are per
     *         collection: pass only documents of one collection)
     */
    NodeSet nodesOfType(final DocumentSet docs, final short[] localTypeIds) throws IOException {
        final NodeSet result = new NewArrayNodeSet();
        if (docs.getDocumentCount() == 0) {
            return result;
        }
        // the keys are (localTypeId, docId): look up the documents' keys, or scan the range of their ids only
        final int[] docIds = new int[docs.getDocumentCount()];
        int n = 0;
        for (final Iterator<DocumentImpl> i = docs.getDocumentIterator(); i.hasNext(); ) {
            docIds[n++] = i.next().getDocId();
        }
        Arrays.sort(docIds, 0, n);
        for (final short localTypeId : localTypeIds) {
            if (n <= POINT_LOOKUP_DOCS) {
                for (int i = 0; i < n; i++) {
                    addAll(result, docs.getDoc(docIds[i]), get(membersKey(localTypeId, docIds[i])));
                }
                continue;
            }
            final byte[] prefix = new byte[3];
            prefix[0] = TYPE_MEMBERS;
            ByteConversion.shortToByteH(localTypeId, prefix, 1);
            final byte[] start = membersKey(localTypeId, docIds[0]);
            final byte[] end = docIds[n - 1] == Integer.MAX_VALUE ? successor(prefix) : membersKey(localTypeId, docIds[n - 1] + 1);
            for (final byte[] key : keysBetween(start, end, prefix)) {
                final DocumentImpl doc = docs.getDoc(ByteConversion.byteToIntH(key, key.length - 4));
                if (doc != null) {
                    addAll(result, doc, get(key));
                }
            }
        }
        return result;
    }

    /** up to this many documents, {@link #nodesOfType} looks up each document's key instead of scanning a range */
    private static final int POINT_LOOKUP_DOCS = 16;

    /**
     * The nodes of the given documents whose typed value has a key in a range.
     *
     * @param collectionId the collection of the documents, whose local type table the type ids are from
     * @param docs         documents of that collection
     * @param localTypeIds the local types
     * @param low          the scan starts at the first key starting with or greater than this (inclusive); a typed
     *                     key or a prefix of one ({@link TypedKeys}), never null
     * @param high         the scan ends after the last key starting with this (inclusive), a typed key or a prefix
     * @return the nodes, in document order, without duplicates
     */
    NodeSet typedRange(final int collectionId, final DocumentSet docs, final short[] localTypeIds, final byte[] low,
            final byte[] high) throws IOException {
        final NodeSet result = new NewArrayNodeSet();
        if (docs.getDocumentCount() == 0 || Arrays.compareUnsigned(low, high) > 0 && !startsWith(low, high)) {
            return result;
        }
        for (final short localTypeId : localTypeIds) {
            final byte[] prefix = typedPrefix(collectionId, localTypeId);
            final byte[] upper = successor(concat(prefix, high));
            for (final byte[] key : keysBetween(concat(prefix, low), upper, prefix)) {
                final DocumentImpl doc = docs.getDoc(ByteConversion.byteToIntH(key, key.length - 4));
                if (doc != null) {
                    addAll(result, doc, get(key));
                }
            }
        }
        return result;
    }

    /**
     * @return true if a node of one of the documents of one of the local types has a typed value without key
     *         ({@link TypedKeys#UNINDEXABLE})
     */
    boolean hasUnindexable(final int collectionId, final DocumentSet docs, final short[] localTypeIds) throws IOException {
        for (final short localTypeId : localTypeIds) {
            final byte[] prefix = concat(typedPrefix(collectionId, localTypeId), new byte[] {TypedKeys.UNINDEXABLE});
            for (final byte[] key : keys(prefix, prefix)) {
                if (key.length == prefix.length + 4 && docs.getDoc(ByteConversion.byteToIntH(key, prefix.length)) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean startsWith(final byte[] key, final byte[] prefix) {
        return key.length >= prefix.length && Arrays.equals(key, 0, prefix.length, prefix, 0, prefix.length);
    }

    // --- removal ---

    /**
     * Remove all entries of a document, its metadata included.
     */
    void removeDocument(final int docId) throws IOException {
        removeAnnotations(docId);
        remove(intKey(DOC_META, docId));
        touched(docId);
    }

    /**
     * Remove the annotation, type member and typed value entries of a document, keeping its metadata.
     */
    void removeAnnotations(final int docId) throws IOException {
        final byte[] annotations = intKey(ANNOTATION, docId);
        for (final byte[] key : keys(annotations, annotations)) {
            remove(key);
        }
        final byte[] memberKeys = intKey(DOC_MEMBER_KEYS, docId);
        for (final byte[] key : keys(memberKeys, memberKeys)) {
            remove(membersKey(ByteConversion.byteToShortH(key, 5), docId));
            remove(key);
        }
        final byte[] typed = intKey(DOC_TYPED_KEYS, docId);
        for (final byte[] key : keys(typed, typed)) {
            final short localTypeId = ByteConversion.byteToShortH(key, 5);
            final byte[] collectionId = get(key);
            if (collectionId != null && collectionId.length == 4) {
                remove(typedKey(ByteConversion.byteToIntH(collectionId, 0), localTypeId,
                        Arrays.copyOfRange(key, 7, key.length), docId));
            }
            remove(key);
        }
        touched(docId);
    }

    /**
     * Remove the entries of the nodes of a subtree of a document.
     *
     * @param subtreeRoot the root of the subtree (an element or an attribute)
     */
    void removeNodes(final int docId, final NodeId subtreeRoot) throws IOException {
        final byte[] annotations = intKey(ANNOTATION, docId);
        final Set<Short> affected = new HashSet<>();
        for (final byte[] key : keys(annotations, annotations)) {
            final int units = ByteConversion.byteToShortH(key, 5);
            final NodeId id = nodeFactory.createFromData(units, key, 7);
            if (id.isDescendantOrSelfOf(subtreeRoot)) {
                final byte[] value = get(key);
                if (value != null) {
                    affected.add(ByteConversion.byteToShortH(value, 0));
                }
                remove(key);
            }
        }
        for (final short localTypeId : affected) {
            if (filter(membersKey(localTypeId, docId), subtreeRoot)) {
                remove(docMemberKey(docId, localTypeId));
            }
        }
        final byte[] typed = intKey(DOC_TYPED_KEYS, docId);
        for (final byte[] key : keys(typed, typed)) {
            final short localTypeId = ByteConversion.byteToShortH(key, 5);
            if (!affected.contains(localTypeId)) {
                continue;
            }
            final byte[] collectionId = get(key);
            if (collectionId == null || collectionId.length != 4
                    || filter(typedKey(ByteConversion.byteToIntH(collectionId, 0), localTypeId,
                            Arrays.copyOfRange(key, 7, key.length), docId), subtreeRoot)) {
                remove(key);
            }
        }
        touched(docId);
    }

    /**
     * Drop the members of a subtree from a node list.
     *
     * @return true if the list is now empty and was removed
     */
    private boolean filter(final byte[] key, final NodeId subtreeRoot) throws IOException {
        final byte[] data = get(key);
        if (data == null) {
            return true;
        }
        final List<Member> kept = new ArrayList<>();
        boolean changed = false;
        for (final Member member : decode(data)) {
            if (member.nodeId().isDescendantOrSelfOf(subtreeRoot)) {
                changed = true;
            } else {
                kept.add(member);
            }
        }
        if (kept.isEmpty()) {
            remove(key);
            return true;
        }
        if (changed) {
            put(key, encode(kept));
        }
        return false;
    }

    // --- node lists ---

    private static byte[] encode(final List<Member> members) throws IOException {
        final VariableByteOutputStream os = new VariableByteOutputStream(16 + members.size() * 6);
        os.writeInt(members.size());
        NodeId previous = null;
        for (final Member member : members) {
            os.writeByte((byte) member.nodeKind());
            previous = member.nodeId().write(previous, os);
        }
        return os.toByteArray();
    }

    private List<Member> decode(final byte[] data) throws IOException {
        final List<Member> members = new ArrayList<>();
        final VariableByteInput is = new VariableByteArrayInput(data);
        while (is.available() > 0) {
            final int count = is.readInt();
            NodeId previous = null;
            for (int i = 0; i < count; i++) {
                final short kind = is.readByte();
                previous = nodeFactory.createFromStream(previous, is);
                members.add(new Member(previous, kind));
            }
        }
        return members;
    }

    private void addAll(final NodeSet result, final DocumentImpl doc, @Nullable final byte[] data) throws IOException {
        if (data == null) {
            return;
        }
        for (final Member member : decode(data)) {
            result.add(new NodeProxy(null, doc, member.nodeId(), member.nodeKind()));
        }
    }

    // --- keys ---

    private static byte[] intKey(final byte table, final int id) {
        final byte[] key = new byte[5];
        key[0] = table;
        ByteConversion.intToByteH(id, key, 1);
        return key;
    }

    private static byte[] annotationKey(final int docId, final NodeId id) {
        final byte[] key = new byte[7 + id.size()];
        key[0] = ANNOTATION;
        ByteConversion.intToByteH(docId, key, 1);
        ByteConversion.shortToByteH((short) id.units(), key, 5);
        id.serialize(key, 7);
        return key;
    }

    private static byte[] membersKey(final short localTypeId, final int docId) {
        final byte[] key = new byte[7];
        key[0] = TYPE_MEMBERS;
        ByteConversion.shortToByteH(localTypeId, key, 1);
        ByteConversion.intToByteH(docId, key, 3);
        return key;
    }

    private static byte[] typedPrefix(final int collectionId, final short localTypeId) {
        final byte[] key = new byte[7];
        key[0] = TYPED_VALUES;
        ByteConversion.intToByteH(collectionId, key, 1);
        ByteConversion.shortToByteH(localTypeId, key, 5);
        return key;
    }

    private static byte[] typedKey(final int collectionId, final short localTypeId, final byte[] valueKey, final int docId) {
        final byte[] key = new byte[7 + valueKey.length + 4];
        System.arraycopy(typedPrefix(collectionId, localTypeId), 0, key, 0, 7);
        System.arraycopy(valueKey, 0, key, 7, valueKey.length);
        ByteConversion.intToByteH(docId, key, 7 + valueKey.length);
        return key;
    }

    private static byte[] docMemberKey(final int docId, final short localTypeId) {
        final byte[] key = new byte[7];
        key[0] = DOC_MEMBER_KEYS;
        ByteConversion.intToByteH(docId, key, 1);
        ByteConversion.shortToByteH(localTypeId, key, 5);
        return key;
    }

    /**
     * Scan the whole type member table for the entries of a document; for tests.
     *
     * @return the local type ids with a {@code 0x03} entry for the document
     */
    Set<Short> memberTypeIdsByScan(final int docId) throws IOException {
        final byte[] prefix = {TYPE_MEMBERS};
        final Set<Short> ids = new HashSet<>();
        for (final byte[] key : keys(prefix, prefix)) {
            if (ByteConversion.byteToIntH(key, 3) == docId) {
                ids.add(ByteConversion.byteToShortH(key, 1));
            }
        }
        return ids;
    }

    private static byte[] docTypedKey(final int docId, final short localTypeId, final byte[] valueKey) {
        final byte[] key = new byte[7 + valueKey.length];
        key[0] = DOC_TYPED_KEYS;
        ByteConversion.intToByteH(docId, key, 1);
        ByteConversion.shortToByteH(localTypeId, key, 5);
        System.arraycopy(valueKey, 0, key, 7, valueKey.length);
        return key;
    }

    private static byte[] concat(final byte[] a, final byte[] b) {
        final byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /**
     * The smallest key greater than every key starting with the prefix, or null if there is none.
     */
    private static @Nullable byte[] successor(final byte[] prefix) {
        for (int i = prefix.length - 1; i >= 0; i--) {
            if (prefix[i] != (byte) 0xFF) {
                final byte[] r = Arrays.copyOf(prefix, i + 1);
                r[i]++;
                return r;
            }
        }
        return null;
    }

    // --- file access ---

    /**
     * @return the keys {@code >= start} which start with {@code prefix}
     */
    private List<byte[]> keys(final byte[] start, final byte[] prefix) throws IOException {
        return keysBetween(start, successor(prefix), prefix);
    }

    /** the keys from start (inclusive) to upper (exclusive, null for no bound) which start with the prefix */
    private List<byte[]> keysBetween(final byte[] start, @Nullable final byte[] upper, final byte[] prefix) throws IOException {
        accesses.incrementAndGet();
        final IndexQuery query = upper == null
                ? new IndexQuery(IndexQuery.GEQ, new Value(start))
                : new IndexQuery(IndexQuery.RANGE, new Value(start), new Value(upper));
        final List<Value> found;
        try (final ManagedLock<ReentrantLock> lock = lockManager.acquireBtreeReadLock(db.getLockName())) {
            found = db.findKeys(query);
        } catch (final LockException | BTreeException | TerminatedException e) {
            throw new IOException(e.getMessage(), e);
        }
        final List<byte[]> keys = new ArrayList<>(found.size());
        for (final Value value : found) {
            final byte[] key = value.getData();
            if (key.length >= prefix.length && Arrays.equals(key, 0, prefix.length, prefix, 0, prefix.length)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private @Nullable byte[] get(final byte[] key) throws IOException {
        accesses.incrementAndGet();
        try (final ManagedLock<ReentrantLock> lock = lockManager.acquireBtreeReadLock(db.getLockName())) {
            final Value value = db.get(new Value(key));
            return value == null ? null : value.getData();
        } catch (final LockException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private void put(final byte[] key, final byte[] value) throws IOException {
        accesses.incrementAndGet();
        try (final ManagedLock<ReentrantLock> lock = lockManager.acquireBtreeWriteLock(db.getLockName())) {
            if (db.put(new Value(key), value, true) == BFile.UNKNOWN_ADDRESS) {
                throw new IOException("Unable to write to " + db.getFile());
            }
        } catch (final LockException | ReadOnlyException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private void append(final byte[] key, final byte[] value) throws IOException {
        accesses.incrementAndGet();
        try (final ManagedLock<ReentrantLock> lock = lockManager.acquireBtreeWriteLock(db.getLockName())) {
            if (db.append(new Value(key), new org.exist.util.FixedByteArray(value)) == BFile.UNKNOWN_ADDRESS) {
                throw new IOException("Unable to write to " + db.getFile());
            }
        } catch (final LockException | ReadOnlyException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private void remove(final byte[] key) throws IOException {
        accesses.incrementAndGet();
        try (final ManagedLock<ReentrantLock> lock = lockManager.acquireBtreeWriteLock(db.getLockName())) {
            db.remove(new Value(key));
        } catch (final LockException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}
