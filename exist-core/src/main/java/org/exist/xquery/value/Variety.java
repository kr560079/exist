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
package org.exist.xquery.value;

/**
 * The variety of a user-defined (schema) type.
 */
public enum Variety {
    ATOMIC("atomic"), LIST("list"), UNION("union"), COMPLEX_SIMPLE_CONTENT("simple"), COMPLEX_ELEMENT_ONLY("element-only"),
    COMPLEX_MIXED("mixed"), COMPLEX_EMPTY("empty");

    private final String label;

    Variety(final String label) {
        this.label = label;
    }

    /** @return the name of the variety in schema-type records ({@code fn:node-type-annotation}, {@code schema:types}) */
    public String label() {
        return label;
    }

    /** @return true for a simple type: an atomic, list or union type */
    public boolean isSimple() {
        return this == ATOMIC || this == LIST || this == UNION;
    }
}
