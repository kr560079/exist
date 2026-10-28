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

import org.exist.xquery.schema.SchemaKey;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Registry of user-defined (schema) types. Codes start at {@link #USER_TYPE_BASE},
 * above all built-in {@link Type} codes.
 */
public final class UserTypeRegistry {

    public static final int USER_TYPE_BASE = 1024;

    private static final AtomicInteger NEXT_CODE = new AtomicInteger(USER_TYPE_BASE);
    private static final Map<String, Integer> CODES = new ConcurrentHashMap<>();
    private static final Map<Integer, UserDefinedType> TYPES = new ConcurrentHashMap<>();

    /** set once the first type is registered; lets hot paths skip user-type handling entirely */
    private static volatile boolean anyRegistered = false;

    private UserTypeRegistry() {
    }

    /**
     * Register a type; idempotent per (schema, typeId).
     *
     * @param schema the schema defining the type
     * @param typeId {ns}local for named types, #anon:declaration-path for anonymous types
     * @param factory creates the type definition for the allocated code
     * @return the type code
     */
    public static int register(final SchemaKey schema, final String typeId, final Function<Integer, UserDefinedType> factory) {
        final String key = schema.sha256() + '|' + typeId;
        final Integer existing = CODES.get(key);
        if (existing != null) {
            return existing;
        }
        synchronized (UserTypeRegistry.class) {
            final Integer again = CODES.get(key);
            if (again != null) {
                return again;
            }
            final int code = NEXT_CODE.getAndIncrement();
            final UserDefinedType type = factory.apply(code);
            TYPES.put(code, type);
            CODES.put(key, code);
            anyRegistered = true;
            return code;
        }
    }

    public static @Nullable UserDefinedType get(final int code) {
        return code < USER_TYPE_BASE ? null : TYPES.get(code);
    }

    /**
     * Test whether any user-defined type was ever registered. If not, no value of a user-defined
     * type can exist.
     *
     * @return true if a type was registered
     */
    public static boolean hasUserTypes() {
        return anyRegistered;
    }

    public static boolean isUserType(final int code) {
        return code >= USER_TYPE_BASE && TYPES.containsKey(code);
    }
}
