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

import org.exist.collections.Collection;
import org.exist.dom.persistent.LockedDocument;
import org.exist.security.Permission;
import org.exist.security.PermissionDeniedException;
import org.exist.security.Subject;
import org.exist.storage.DBBroker;
import org.exist.storage.lock.Lock.LockMode;
import org.exist.xmldb.XmldbURI;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * A schema loaded for an {@code import schema}, with what using it was authorized by: the database documents it was
 * read from (with their versions), whether remote sources were fetched, and, for an import without a location hint,
 * the collection whose configuration supplied it.
 *
 * <p>A compiled query reused from the query pool by another user must be re-authorized from these, as compiling it
 * would have been ({@link #mayBeUsedBy(DBBroker, Subject)}), and is stale when a document changed
 * ({@link #isCurrent(DBBroker)}).</p>
 *
 * @param schema the schema
 * @param databaseVersions the id ({@code xmldb:exist://} URI) of each database document of the schema to its
 *     last-modified time when loaded
 * @param remote true if a source was fetched from a remote location
 * @param configuredOn the collection whose configuration supplied the schema (it was then read as the configuration's
 *     owner), or null if the importing user read it
 */
public record ImportedSchema(CompiledSchema schema, Map<String, Long> databaseVersions, boolean remote,
        @Nullable XmldbURI configuredOn) {

    /**
     * @param collection the collection whose configuration supplied the schema
     * @return a copy recording that the schema came from that collection's configuration
     */
    public ImportedSchema configuredOn(final XmldbURI collection) {
        return new ImportedSchema(schema, databaseVersions, remote, collection);
    }

    /**
     * Whether a user could import the schema the same way: READ on every database document (or, for a configured
     * schema, READ on the configured collection), and the dba role if remote sources were used.
     *
     * @param broker a broker
     * @param subject the user
     * @return true if the user may use the schema
     */
    public boolean mayBeUsedBy(final DBBroker broker, final Subject subject) {
        if (remote && !subject.hasDbaRole()) {
            return false;
        }
        if (configuredOn != null) {
            try (final Collection collection = broker.openCollection(configuredOn, LockMode.READ_LOCK)) {
                return collection != null && collection.getPermissionsNoLock().validate(subject, Permission.READ);
            } catch (final PermissionDeniedException | RuntimeException e) {
                return false;
            }
        }
        for (final String id : databaseVersions.keySet()) {
            if (!readable(broker, subject, id)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param broker a broker which may read the documents (version checks must not depend on the borrowing user)
     * @return true if no database document of the schema changed since it was loaded
     */
    public boolean isCurrent(final DBBroker broker) {
        for (final Map.Entry<String, Long> dep : databaseVersions.entrySet()) {
            if (ChecksummingResourceResolver.databaseVersion(broker, dep.getKey()) != dep.getValue()) {
                return false;
            }
        }
        return true;
    }

    static boolean readable(final DBBroker broker, final Subject subject, final String id) {
        final String path = ChecksummingResourceResolver.stripXmldbScheme(id);
        if (path == null) {
            return true;
        }
        try (final LockedDocument locked = broker.getXMLResource(XmldbURI.create(path), LockMode.READ_LOCK)) {
            return locked != null && locked.getDocument().getPermissions().validate(subject, Permission.READ);
        } catch (final PermissionDeniedException | RuntimeException e) {
            return false;
        }
    }
}
