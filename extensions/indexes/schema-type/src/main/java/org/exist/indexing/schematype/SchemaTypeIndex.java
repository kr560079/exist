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

import org.exist.backup.RawDataBackup;
import org.exist.indexing.AbstractIndex;
import org.exist.indexing.IndexWorker;
import org.exist.indexing.RawBackupSupport;
import org.exist.storage.DBBroker;
import org.exist.storage.btree.DBException;
import org.exist.xquery.schema.TypeAnnotationProvider;

import java.io.IOException;

/**
 * Index module for schema-aware XQuery: per-collection schema configuration now,
 * type annotations and typed-value storage in later tasks.
 */
public class SchemaTypeIndex extends AbstractIndex implements RawBackupSupport {

    public static final String ID = TypeAnnotationProvider.INDEX_ID;

    public static final class Factory implements org.exist.indexing.IndexFactory {
        @Override
        public String getDefaultId() {
            return "schema-type-index";
        }

        @Override
        public Class<? extends AbstractIndex> getIndexClass() {
            return SchemaTypeIndex.class;
        }
    }

    @Override
    public void open() {
        // storage is added by a later task
    }

    @Override
    public void close() throws DBException {
    }

    @Override
    public void sync() throws DBException {
    }

    @Override
    public void remove() throws DBException {
    }

    @Override
    public boolean checkIndex(final DBBroker broker) {
        return true;
    }

    @Override
    public IndexWorker getWorker(final DBBroker broker) {
        return new SchemaTypeIndexWorker(broker, this);
    }

    @Override
    public void backupToArchive(final RawDataBackup backup) throws IOException {
        // no storage yet
    }
}
