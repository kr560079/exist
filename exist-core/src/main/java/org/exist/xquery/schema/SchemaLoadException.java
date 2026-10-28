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

import javax.annotation.Nullable;

/**
 * Thrown when a schema (or one of its includes/imports) cannot be loaded or compiled.
 */
public class SchemaLoadException extends Exception {

    private final @Nullable String systemId;
    private final int line;
    private final int column;

    public SchemaLoadException(final String message, @Nullable final String systemId, final int line, final int column, @Nullable final Throwable cause) {
        super(message, cause);
        this.systemId = systemId;
        this.line = line;
        this.column = column;
    }

    public SchemaLoadException(final String message) {
        this(message, null, -1, -1, null);
    }

    /** @return the system ID of the document the problem was found in, or null if unknown */
    public @Nullable String systemId() {
        return systemId;
    }

    /** @return the 1-based line number, or -1 if unknown */
    public int line() {
        return line;
    }

    /** @return the 1-based column number, or -1 if unknown */
    public int column() {
        return column;
    }
}
