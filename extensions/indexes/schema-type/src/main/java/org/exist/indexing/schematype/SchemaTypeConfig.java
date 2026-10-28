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

import org.exist.dom.QName;
import org.exist.storage.DBBroker;
import org.exist.util.DatabaseConfigurationException;
import org.exist.xquery.XPathException;
import org.exist.xquery.schema.CompiledSchema;
import org.exist.xquery.schema.SchemaLoadException;
import org.exist.xquery.value.Type;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The parsed {@code <schema-type>} / {@code <typed-values>} configuration of a collection.
 *
 * @param href the location of the root schema
 * @param onUnassignable what to do with nodes that cannot be assigned a type
 * @param checkValues whether lexical values are checked against their simple type at store time
 * @param allowRemote whether includes and imports may be fetched over the network
 * @param reindexOnSchemaChange whether a changed schema triggers an automatic reindex
 * @param typedValueTypes the types that get typed-value index entries
 * @param schema the compiled schema
 */
public record SchemaTypeConfig(String href, OnUnassignable onUnassignable, boolean checkValues, boolean allowRemote,
        boolean reindexOnSchemaChange, Set<QName> typedValueTypes, CompiledSchema schema) {

    private static final String HREF_ATTR = "href";
    private static final String ON_UNASSIGNABLE_ATTR = "on-unassignable";
    private static final String CHECK_VALUES_ATTR = "check-values";
    private static final String ALLOW_REMOTE_ATTR = "allow-remote";
    private static final String REINDEX_ATTR = "reindex-on-schema-change";
    private static final String TYPE_ELEMENT = "type";
    private static final String NAME_ATTR = "name";
    private static final String XML_SCHEMA_NS = "http://www.w3.org/2001/XMLSchema";

    /**
     * Parses and validates the configuration, loading the schema eagerly.
     *
     * @throws DatabaseConfigurationException if an attribute is invalid, the schema cannot be loaded or a typed-value type is unknown
     */
    static SchemaTypeConfig parse(final DBBroker broker, final Element schemaTypeEl, @Nullable final Element typedValuesEl,
            final Map<String, String> namespaces) throws DatabaseConfigurationException {
        final String href = schemaTypeEl.getAttribute(HREF_ATTR);
        if (href.isEmpty()) {
            throw new DatabaseConfigurationException("<schema-type> requires an '" + HREF_ATTR + "' attribute");
        }

        final OnUnassignable onUnassignable;
        final String onUnassignableValue = schemaTypeEl.hasAttribute(ON_UNASSIGNABLE_ATTR)
                ? schemaTypeEl.getAttribute(ON_UNASSIGNABLE_ATTR) : "untyped";
        try {
            onUnassignable = OnUnassignable.fromAttribute(onUnassignableValue);
        } catch (final IllegalArgumentException e) {
            throw new DatabaseConfigurationException("Invalid value '" + onUnassignableValue + "' for attribute '"
                    + ON_UNASSIGNABLE_ATTR + "' of <schema-type>: expected 'untyped' or 'reject'");
        }
        final boolean checkValues = booleanAttr(schemaTypeEl, CHECK_VALUES_ATTR, true);
        final boolean allowRemote = booleanAttr(schemaTypeEl, ALLOW_REMOTE_ATTR, false);
        final boolean reindex = booleanAttr(schemaTypeEl, REINDEX_ATTR, false);

        final CompiledSchema schema;
        try {
            schema = broker.getBrokerPool().getSchemaRegistry().load(broker, href, allowRemote);
        } catch (final SchemaLoadException e) {
            final StringBuilder msg = new StringBuilder("Unable to load schema ").append(href).append(": ").append(e.getMessage());
            if (e.systemId() != null) {
                msg.append(" [").append(e.systemId());
                if (e.line() >= 0) {
                    msg.append(':').append(e.line());
                    if (e.column() >= 0) {
                        msg.append(':').append(e.column());
                    }
                }
                msg.append(']');
            }
            throw new DatabaseConfigurationException(msg.toString(), e);
        }

        final Set<QName> typedValueTypes = new LinkedHashSet<>();
        if (typedValuesEl != null) {
            final NodeList kids = typedValuesEl.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                final Node kid = kids.item(i);
                if (kid.getNodeType() == Node.ELEMENT_NODE && TYPE_ELEMENT.equals(kid.getLocalName())) {
                    typedValueTypes.add(resolveType((Element) kid, schema, namespaces));
                }
            }
        }

        return new SchemaTypeConfig(href, onUnassignable, checkValues, allowRemote, reindex, Set.copyOf(typedValueTypes), schema);
    }

    private static boolean booleanAttr(final Element el, final String name, final boolean defaultValue) throws DatabaseConfigurationException {
        if (!el.hasAttribute(name)) {
            return defaultValue;
        }
        final String value = el.getAttribute(name).trim();
        return switch (value) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> throw new DatabaseConfigurationException("Invalid value '" + value + "' for attribute '" + name
                    + "' of <" + el.getLocalName() + ">: expected 'true' or 'false'");
        };
    }

    private static QName resolveType(final Element typeEl, final CompiledSchema schema, final Map<String, String> namespaces)
            throws DatabaseConfigurationException {
        final String name = typeEl.getAttribute(NAME_ATTR);
        if (name.isEmpty()) {
            throw new DatabaseConfigurationException("<type> requires a '" + NAME_ATTR + "' attribute");
        }
        final int colon = name.indexOf(':');
        final String prefix = colon < 0 ? "" : name.substring(0, colon);
        final String local = colon < 0 ? name : name.substring(colon + 1);

        String ns = "";
        if (colon >= 0) {
            ns = namespaces.get(prefix);
            if (ns == null) {
                ns = typeEl.lookupNamespaceURI(prefix);
            }
            if (ns == null) {
                throw new DatabaseConfigurationException("Unbound namespace prefix '" + prefix + "' in typed-values type '" + name + "'");
            }
        }
        final QName qname = new QName(local, ns);

        if (XML_SCHEMA_NS.equals(ns)) {
            try {
                Type.getType(qname);
            } catch (final XPathException e) {
                throw new DatabaseConfigurationException("Unknown built-in type '" + name + "' in <typed-values>");
            }
        } else if (schema.namedType(qname).isEmpty()) {
            throw new DatabaseConfigurationException("Unknown type '" + name + "' in <typed-values>: not defined by the configured schema");
        }
        return qname;
    }
}
