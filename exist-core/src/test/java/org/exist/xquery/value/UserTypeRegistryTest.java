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

import org.exist.dom.QName;
import org.exist.xquery.schema.SchemaKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class UserTypeRegistryTest {

    private static final SchemaKey K1 = new SchemaKey("k1-" + System.nanoTime());
    private static final SchemaKey K2 = new SchemaKey("k2-" + System.nanoTime());

    private static int registerAtomic(final SchemaKey key, final String name, final int base) {
        return UserTypeRegistry.register(key, "{urn:t}" + name, code -> new UserDefinedType(code,
                new QName(name, "urn:t"), null, base, Type.primitiveTypeOf(base), Variety.ATOMIC, key, null, new int[0]));
    }

    @Test
    void userRestrictionOfDecimalIsDecimalAndNumeric() {
        final int nnd = registerAtomic(K1, "NonNegativeDecimal", Type.DECIMAL);
        assertTrue(UserTypeRegistry.isUserType(nnd));
        assertTrue(nnd >= UserTypeRegistry.USER_TYPE_BASE);
        assertTrue(Type.subTypeOf(nnd, Type.DECIMAL));
        assertTrue(Type.subTypeOf(nnd, Type.NUMERIC));
        assertTrue(Type.subTypeOf(nnd, Type.ANY_ATOMIC_TYPE));
        assertTrue(Type.subTypeOf(nnd, Type.ITEM));
        assertFalse(Type.subTypeOf(nnd, Type.STRING));
        assertFalse(Type.subTypeOf(Type.DECIMAL, nnd));
        assertEquals(Type.DECIMAL, Type.primitiveTypeOf(nnd));
        assertEquals(Type.DECIMAL, Type.getSuperType(nnd));
        assertEquals("Q{urn:t}NonNegativeDecimal", Type.getTypeName(nnd));
        assertTrue(Type.isCastable(nnd, Type.DOUBLE));
        assertEquals(Type.DECIMAL, Type.getCommonSuperType(nnd, Type.DECIMAL));
    }

    @Test
    void chainedUserTypes() {
        final int nnd = registerAtomic(K1, "NonNegativeDecimal", Type.DECIMAL);
        final int pd = registerAtomic(K1, "PositiveDecimal", nnd);
        assertTrue(Type.subTypeOf(pd, nnd));
        assertTrue(Type.subTypeOf(pd, Type.DECIMAL));
        assertFalse(Type.subTypeOf(nnd, pd));
        assertEquals(Type.DECIMAL, Type.primitiveTypeOf(pd));
        assertEquals(nnd, Type.getSuperType(pd));
        assertEquals(nnd, Type.getCommonSuperType(pd, nnd));
    }

    @Test
    void registerIsIdempotentPerSchemaAndName() {
        assertEquals(registerAtomic(K1, "T", Type.STRING), registerAtomic(K1, "T", Type.STRING));
        assertNotEquals(registerAtomic(K1, "T", Type.STRING), registerAtomic(K2, "T", Type.STRING));
    }

    @Test
    void anonymousTypeNameUsesAnonymousId() {
        final int c = UserTypeRegistry.register(K1, "#anon:/e", code -> new UserDefinedType(code, null,
                "#anon:/e", Type.STRING, Type.STRING, Variety.ATOMIC, K1, null, new int[0]));
        assertEquals("#anon:/e", Type.getTypeName(c));
    }

    @Test
    void builtInBehaviourUnchanged() {
        assertTrue(Type.subTypeOf(Type.INT, Type.DECIMAL));
        assertFalse(Type.subTypeOf(Type.STRING, Type.DECIMAL));
        assertFalse(UserTypeRegistry.isUserType(Type.INT));
        assertNull(UserTypeRegistry.get(Type.INT));
    }

    private static int registerUnion(final SchemaKey key, final String name, final int... members) {
        return UserTypeRegistry.register(key, "{urn:t}" + name, code -> new UserDefinedType(code,
                new QName(name, "urn:t"), null, Type.ANY_ATOMIC_TYPE, 0, Variety.UNION, key, null, members));
    }

    @Test
    void userUnionSemantics() {
        final int u = registerUnion(K1, "DecOrStr", Type.DECIMAL, Type.STRING);
        assertTrue(Type.subTypeOf(Type.INT, u));
        assertTrue(Type.subTypeOf(Type.STRING, u));
        assertFalse(Type.subTypeOf(Type.BOOLEAN, u));
        assertTrue(Type.subTypeOf(u, Type.ANY_ATOMIC_TYPE));
        assertTrue(Type.hasMember(u, Type.DECIMAL));
        assertFalse(Type.hasMember(u, Type.INT));
        assertTrue(Type.subTypeOfUnion(Type.INT, u));
        assertTrue(Type.subTypeOfUnion(u, u));
        assertFalse(Type.subTypeOfUnion(Type.BOOLEAN, u));
    }

    @Test
    void nonUnionUserSuperRejectsBuiltIn() {
        final int nnd = registerAtomic(K1, "NonNegativeDecimal", Type.DECIMAL);
        assertFalse(Type.subTypeOf(Type.INT, nnd));
        assertFalse(Type.subTypeOfUnion(Type.INT, nnd));
        assertFalse(Type.hasMember(nnd, Type.DECIMAL));
    }

    @Test
    void userListKeepsItemTypeInMemberCodes() {
        final int item = registerAtomic(K1, "ListItem", Type.INT);
        final int list = UserTypeRegistry.register(K1, "{urn:t}IntList", code -> new UserDefinedType(code,
                new QName("IntList", "urn:t"), null, Type.ANY_ATOMIC_TYPE, 0, Variety.LIST, K1, null, new int[]{item}));
        assertArrayEquals(new int[]{item}, UserTypeRegistry.get(list).memberCodes());
        assertEquals(Variety.LIST, UserTypeRegistry.get(list).variety());
        assertFalse(Type.hasMember(list, item));
        assertFalse(Type.subTypeOf(Type.INT, list));
    }
}
