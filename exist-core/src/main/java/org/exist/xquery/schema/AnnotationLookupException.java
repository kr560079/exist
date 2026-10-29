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

import org.exist.xquery.XPathException;

/**
 * Carries the {@link XPathException} of a type annotation lookup ({@code EXSA0002}, {@code EXSA0003}) through
 * interfaces whose methods cannot throw it, such as {@link org.exist.xquery.NodeTest#matches}. Expressions
 * evaluating such tests unwrap it with {@link #getCause()} and rethrow the original exception.
 */
public final class AnnotationLookupException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AnnotationLookupException(final XPathException cause) {
        super(cause.getMessage(), cause);
    }

    @Override
    public synchronized XPathException getCause() {
        return (XPathException) super.getCause();
    }
}
