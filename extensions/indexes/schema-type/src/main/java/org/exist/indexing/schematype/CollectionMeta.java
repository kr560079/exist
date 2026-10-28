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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The persisted metadata of a collection with a schema: the schema its documents were last indexed with and
 * the table of local type ids.
 *
 * <p>Annotations store a 16-bit local type id instead of the runtime type code, which is not stable across
 * JVMs. The table maps the stable id of each type ({@code {ns}local}, or {@code #anon:<declaration path>}) to its
 * local id. Ids are allocated append-only and never reused, so entries written with an earlier table stay
 * valid.</p>
 *
 * @param schema       the key of the schema the collection is configured with
 * @param localTypeIds the stable type id to local type id table, in allocation order
 */
public record CollectionMeta(SchemaKey schema, Map<String, Short> localTypeIds) {

    public CollectionMeta {
        localTypeIds = Collections.unmodifiableMap(new LinkedHashMap<>(localTypeIds));
    }

    /**
     * @return the next local type id to allocate
     */
    short nextLocalTypeId() {
        int max = -1;
        for (final short id : localTypeIds.values()) {
            max = Math.max(max, id);
        }
        if (max >= Short.MAX_VALUE) {
            throw new IllegalStateException("Local type id table is full");
        }
        return (short) (max + 1);
    }

    byte[] serialize() {
        final VariableByteOutputStream os = new VariableByteOutputStream(64);
        try {
            os.writeUTF(schema.sha256());
            os.writeInt(localTypeIds.size());
            for (final Map.Entry<String, Short> entry : localTypeIds.entrySet()) {
                os.writeUTF(entry.getKey());
                os.writeShort(entry.getValue());
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return os.toByteArray();
    }

    static CollectionMeta deserialize(final byte[] data) throws IOException {
        final VariableByteInput is = new VariableByteArrayInput(data);
        final SchemaKey schema = new SchemaKey(is.readUTF());
        final int count = is.readInt();
        final Map<String, Short> ids = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            final String typeId = is.readUTF();
            ids.put(typeId, is.readShort());
        }
        return new CollectionMeta(schema, ids);
    }
}
