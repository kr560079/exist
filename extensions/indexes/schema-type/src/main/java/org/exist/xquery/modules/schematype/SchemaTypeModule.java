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
package org.exist.xquery.modules.schematype;

import org.exist.xquery.AbstractInternalModule;
import org.exist.xquery.FunctionDef;
import org.exist.xquery.ModuleFactory;

import java.util.List;
import java.util.Map;

import static org.exist.xquery.FunctionDSL.functionDefs;

/**
 * The {@code schema:} module: diagnostics of the schema type index and of the schema types in scope.
 *
 * <p>The module is registered whether or not the schema type index is enabled; without the index its functions report
 * documents as {@code untyped} and know only the schemas a query imported. Nothing of the index is loaded when the
 * module is registered.</p>
 */
public class SchemaTypeModule extends AbstractInternalModule {

    public static final String NAMESPACE_URI = "http://exist-db.org/xquery/schema-type";

    public static final String PREFIX = "schema";
    public static final String RELEASED_IN_VERSION = "eXist-7.0.0";

    public static final FunctionDef[] functions = functionDefs(SchemaStatusFunctions.class,
            SchemaStatusFunctions.FS_STATUS,
            SchemaStatusFunctions.FS_UNASSIGNABLE,
            SchemaStatusFunctions.FS_TYPES);

    public SchemaTypeModule(final Map<String, List<?>> parameters) {
        super(functions, parameters);
    }

    @Override
    public String getNamespaceURI() {
        return NAMESPACE_URI;
    }

    @Override
    public String getDefaultPrefix() {
        return PREFIX;
    }

    @Override
    public String getDescription() {
        return "Diagnostics of the schema type index and of the schema types in scope";
    }

    @Override
    public String getReleaseVersion() {
        return RELEASED_IN_VERSION;
    }

    public static final class Factory implements ModuleFactory {
        @Override
        public String getNamespaceURI() {
            return NAMESPACE_URI;
        }

        @Override
        public Class<? extends org.exist.xquery.Module> getModuleClass() {
            return SchemaTypeModule.class;
        }
    }
}
