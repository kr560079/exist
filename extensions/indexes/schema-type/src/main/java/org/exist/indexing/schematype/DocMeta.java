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

import org.exist.storage.io.VariableByteArrayInput;
import org.exist.storage.io.VariableByteInput;
import org.exist.storage.io.VariableByteOutputStream;
import org.exist.xquery.schema.SchemaKey;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The persisted metadata of a document in a collection with a schema.
 *
 * @param indexedWith       the key of the schema the document's annotations were assigned with
 * @param status            whether every node could be typed
 * @param unassignablePaths the paths of the roots of unassignable subtrees, at most {@link #MAX_PATHS}; for
 *                          diagnostics only (paths are not unique)
 */
public record DocMeta(SchemaKey indexedWith, DocStatus status, List<String> unassignablePaths) {

    /** the maximum number of unassignable paths recorded per document */
    public static final int MAX_PATHS = 100;

    public enum DocStatus {
        /** every node in the schema's scope was assigned a type */
        TYPED,
        /** some subtrees could not be typed and are unannotated */
        PARTIAL
    }

    public DocMeta {
        unassignablePaths = List.copyOf(unassignablePaths.size() > MAX_PATHS
                ? unassignablePaths.subList(0, MAX_PATHS) : unassignablePaths);
    }

    byte[] serialize() {
        final VariableByteOutputStream os = new VariableByteOutputStream(64);
        try {
            os.writeUTF(indexedWith.sha256());
            os.writeByte((byte) status.ordinal());
            os.writeInt(unassignablePaths.size());
            for (final String path : unassignablePaths) {
                os.writeUTF(path);
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return os.toByteArray();
    }

    static DocMeta deserialize(final byte[] data) throws IOException {
        final VariableByteInput is = new VariableByteArrayInput(data);
        final SchemaKey key = new SchemaKey(is.readUTF());
        final DocStatus status = DocStatus.values()[is.readByte()];
        final int count = is.readInt();
        final List<String> paths = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            paths.add(is.readUTF());
        }
        return new DocMeta(key, status, paths);
    }
}
