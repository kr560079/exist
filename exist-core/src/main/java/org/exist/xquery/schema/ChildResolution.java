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

import org.apache.xerces.xs.XSElementDeclaration;
import org.apache.xerces.xs.XSWildcard;

import javax.annotation.Nullable;

/**
 * The outcome of resolving a child element name against the content model of its parent's type.
 *
 * @param kind how the child was resolved
 * @param decl the element declaration governing the child: the declared (or substituting) element for
 *             {@link Kind#DECLARED}; for {@link Kind#WILDCARD} the global declaration found by a
 *             {@code strict} or {@code lax} wildcard, or null when none was found (always null for {@code skip})
 * @param processContents {@link XSWildcard#PC_STRICT}, {@link XSWildcard#PC_LAX} or {@link XSWildcard#PC_SKIP};
 *                        the matching wildcard's value for {@link Kind#WILDCARD}, otherwise {@code PC_STRICT}
 */
public record ChildResolution(Kind kind, @Nullable XSElementDeclaration decl, short processContents) {

    public enum Kind {
        /** matched an element particle, or a member of a particle's substitution group */
        DECLARED,
        /** matched an element wildcard ({@code xs:any}) */
        WILDCARD,
        /** the content model does not allow the child */
        NOT_ALLOWED
    }

    public static final ChildResolution NOT_ALLOWED = new ChildResolution(Kind.NOT_ALLOWED, null, XSWildcard.PC_STRICT);

    static ChildResolution declared(final XSElementDeclaration decl) {
        return new ChildResolution(Kind.DECLARED, decl, XSWildcard.PC_STRICT);
    }

    static ChildResolution wildcard(@Nullable final XSElementDeclaration decl, final short processContents) {
        return new ChildResolution(Kind.WILDCARD, decl, processContents);
    }
}
