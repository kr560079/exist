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

import io.lacuna.bifurcan.IMap;
import org.apache.xerces.xs.XSTypeDefinition;
import org.exist.Namespaces;
import org.exist.dom.QName;
import org.exist.dom.memtree.MemTreeBuilder;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.dom.persistent.NodeProxy;
import org.exist.indexing.IndexWorker;
import org.exist.indexing.schematype.SchemaTypeIndexWorker;
import org.exist.xquery.BasicFunction;
import org.exist.xquery.FunctionSignature;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.functions.map.MapType;
import org.exist.xquery.schema.CompiledSchema;
import org.exist.xquery.schema.ImportedSchema;
import org.exist.xquery.schema.TypeAnnotationProvider;
import org.exist.xquery.value.*;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.exist.xquery.FunctionDSL.*;

/**
 * {@code schema:status}, {@code schema:unassignable} and {@code schema:types}.
 *
 * <p>The first two report on a document node the caller already holds, so they need no permission of their own; for a
 * document in memory or in a collection without a schema they report {@code untyped} and nothing unassignable.
 * {@code schema:types} reveals only the types of schemas the query imported or an {@code import schema} without
 * location hint would find (those configured on collections the caller may read); for any other namespace it
 * returns the empty sequence, as for an unknown one.</p>
 */
public class SchemaStatusFunctions extends BasicFunction {

    private static final String DESCRIPTION_DOC = "A document node";

    public static final FunctionSignature FS_STATUS = functionSignature(
            new QName("status", SchemaTypeModule.NAMESPACE_URI, SchemaTypeModule.PREFIX),
            "Reports whether the document's nodes carry schema type annotations. The map has the key status "
                    + "(typed: every node of the document was assigned a type; partial: some subtrees could not be typed; "
                    + "stale: the type index was built with another schema or not at all, reindex the collection; "
                    + "untyped: the document is in memory or its collection has no schema) and, if the document has "
                    + "index entries, the key schema (the checksum of the schema it was indexed with).",
            returns(Type.MAP_ITEM, "The status of the document"),
            param("doc", Type.DOCUMENT, DESCRIPTION_DOC));

    public static final FunctionSignature FS_UNASSIGNABLE = functionSignature(
            new QName("unassignable", SchemaTypeModule.NAMESPACE_URI, SchemaTypeModule.PREFIX),
            "Returns the paths of the roots of the subtrees of the document which could not be assigned a type "
                    + "(at most 100). The empty sequence for a document in memory or without a schema.",
            returnsOptMany(Type.STRING, "The paths of the unassignable subtrees"),
            param("doc", Type.DOCUMENT, DESCRIPTION_DOC));

    public static final FunctionSignature FS_TYPES = functionSignature(
            new QName("types", SchemaTypeModule.NAMESPACE_URI, SchemaTypeModule.PREFIX),
            "Returns the named types of the schema for a target namespace, a type before the types derived from it, as "
                    + "type elements with the attributes name, base and variety. Only schemas the query imported or "
                    + "configured on collections the caller may read are known; for any other namespace the result is "
                    + "empty.",
            returnsOptMany(Type.ELEMENT, "The type elements"),
            param("namespace", Type.STRING, "The target namespace, the empty string for no namespace"));

    public SchemaStatusFunctions(final XQueryContext context, final FunctionSignature signature) {
        super(context, signature);
    }

    @Override
    public Sequence eval(final Sequence[] args, final Sequence contextSequence) throws XPathException {
        return switch (getName().getLocalPart()) {
            case "status" -> status(args[0].itemAt(0));
            case "unassignable" -> unassignable(args[0].itemAt(0));
            default -> types(args[0].getStringValue());
        };
    }

    // --- status and unassignable ---

    private SchemaTypeIndexWorker.Status stateOf(final Item item) throws XPathException {
        if (item instanceof NodeProxy proxy) {
            final DocumentImpl doc = proxy.getOwnerDocument();
            final IndexWorker worker = context.getBroker().getIndexController().getWorkerByIndexId(TypeAnnotationProvider.INDEX_ID);
            if (doc != null && worker instanceof SchemaTypeIndexWorker typeWorker) {
                return typeWorker.statusOf(doc);
            }
        }
        return new SchemaTypeIndexWorker.Status("untyped", null, List.of());
    }

    private Sequence status(final Item item) throws XPathException {
        final SchemaTypeIndexWorker.Status state = stateOf(item);
        final IMap<AtomicValue, Sequence> map = MapType.newLinearMap(null);
        map.put(new StringValue("status"), new StringValue(state.status()));
        if (state.schema() != null) {
            map.put(new StringValue("schema"), new StringValue(state.schema()));
        }
        return new MapType(this, context, map.forked(), Type.STRING);
    }

    private Sequence unassignable(final Item item) throws XPathException {
        final ValueSequence result = new ValueSequence();
        for (final String path : stateOf(item).unassignable()) {
            result.add(new StringValue(this, path));
        }
        return result;
    }

    // --- types ---

    private Sequence types(final String namespace) throws XPathException {
        final CompiledSchema schema = visibleSchema(namespace);
        if (schema == null || Namespaces.SCHEMA_NS.equals(namespace)) {
            return Sequence.EMPTY_SEQUENCE;
        }
        final List<XSTypeDefinition> defs = new ArrayList<>();
        for (final XSTypeDefinition def : schema.namedTypes()) {
            if (!def.getAnonymous() && def.getName() != null && namespace.equals(nullToEmpty(def.getNamespace()))) {
                defs.add(def);
            }
        }
        defs.sort(Comparator.<XSTypeDefinition>comparingInt(SchemaStatusFunctions::depth).thenComparing(XSTypeDefinition::getName));

        context.pushDocumentContext();
        try {
            final MemTreeBuilder builder = context.getDocumentBuilder();
            final ValueSequence result = new ValueSequence(defs.size());
            for (final XSTypeDefinition def : defs) {
                final int nodeNr = builder.startElement(new QName("type", ""), null);
                builder.addAttribute(new QName("name", ""), def.getName());
                final XSTypeDefinition base = def.getBaseType();
                if (base != null && base != def && base.getName() != null && !base.getAnonymous()) {
                    builder.addAttribute(new QName("base", ""), nameOf(base, namespace));
                }
                final UserDefinedType type = UserTypeRegistry.get(schema.typeCode(def));
                if (type != null) {
                    builder.addAttribute(new QName("variety", ""), type.variety().label());
                }
                builder.endElement();
                result.add(builder.getDocument().getNode(nodeNr));
            }
            return result;
        } finally {
            context.popDocumentContext();
        }
    }

    /** the schema a query may see for a namespace: one it imported, or one an import without hint would find */
    private @Nullable CompiledSchema visibleSchema(final String namespace) throws XPathException {
        final CompiledSchema imported = context.getInScopeSchemas().schemaFor(namespace).orElse(null);
        if (imported != null) {
            return imported;
        }
        final TypeAnnotationProvider provider = TypeAnnotationProvider.of(context.getBroker());
        if (provider == null) {
            return null;
        }
        final ImportedSchema configured = provider.configuredSchema(namespace);
        return configured == null ? null : configured.schema();
    }

    /** the number of base types of a type */
    private static int depth(final XSTypeDefinition def) {
        int depth = 0;
        for (XSTypeDefinition t = def.getBaseType(); t != null && t != def && depth < 1000; t = t.getBaseType()) {
            depth++;
            if (t.getBaseType() == t) {
                break;
            }
        }
        return depth;
    }

    /** the name of a base type: the local name in the type's own namespace, else a prefixed or URI-qualified name */
    private static String nameOf(final XSTypeDefinition base, final String ownNamespace) {
        final String ns = nullToEmpty(base.getNamespace());
        if (ns.equals(ownNamespace)) {
            return base.getName();
        }
        return Namespaces.SCHEMA_NS.equals(ns) ? "xs:" + base.getName() : "Q{" + ns + "}" + base.getName();
    }

    private static String nullToEmpty(@Nullable final String s) {
        return s == null ? "" : s;
    }
}
