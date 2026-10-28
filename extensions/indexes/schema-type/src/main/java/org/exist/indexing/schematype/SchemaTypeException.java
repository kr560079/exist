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

import org.exist.xquery.ErrorCodes.ErrorCode;

/**
 * Thrown while a document is being indexed (the stream listener callbacks cannot throw checked exceptions) when
 * the store must fail: {@code EXSA0002} if the schema is unavailable, {@code EXSA0004} if a node cannot be typed
 * and the collection rejects such documents.
 */
public class SchemaTypeException extends RuntimeException {

    private final ErrorCode errorCode;

    public SchemaTypeException(final ErrorCode errorCode, final String message) {
        super(errorCode.getErrorQName().getLocalPart() + ": " + message);
        this.errorCode = errorCode;
    }

    public SchemaTypeException(final ErrorCode errorCode, final String message, final Throwable cause) {
        super(errorCode.getErrorQName().getLocalPart() + ": " + message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
