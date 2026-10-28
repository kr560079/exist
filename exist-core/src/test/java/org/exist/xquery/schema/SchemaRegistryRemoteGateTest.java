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

import org.exist.collections.Collection;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.Txn;
import org.exist.test.ExistEmbeddedServer;
import org.exist.util.MimeType;
import org.exist.util.StringInputSource;
import org.exist.xmldb.XmldbURI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.exist.util.PropertiesBuilder.propertiesBuilder;
import static org.junit.jupiter.api.Assertions.*;

/** The server-level allow-remote gate being on lets a collection's own allow-remote take effect. */
public class SchemaRegistryRemoteGateTest {

    @RegisterExtension
    public static final ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(
            propertiesBuilder().put(SchemaRegistry.PROPERTY_ALLOW_REMOTE, true).build(), true, true);

    private static final String XSD = "<xs:schema xmlns:xs='http://www.w3.org/2001/XMLSchema' targetNamespace='urn:example:remote'>"
            + "<xs:import namespace='urn:example:elsewhere' schemaLocation='http://schemas.invalid/elsewhere.xsd'/>"
            + "<xs:element name='root' type='xs:string'/></xs:schema>";

    @Test
    void gateOnAndCollectionAllowsRemoteAttemptsTheFetch() throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            try (final Txn txn = pool.getTransactionManager().beginTransaction();
                 final Collection col = broker.getOrCreateCollection(txn, XmldbURI.create("/db/test-gate"))) {
                broker.storeDocument(txn, XmldbURI.create("r.xsd"), new StringInputSource(XSD.getBytes(StandardCharsets.UTF_8)), MimeType.XML_TYPE, col);
                broker.saveCollection(txn, col);
                txn.commit();
            }
            final String href = "xmldb:exist:///db/test-gate/r.xsd";
            final SchemaRegistry registry = pool.getSchemaRegistry();

            // collection did not ask for remote access: still refused
            final SchemaLoadException refused = assertThrows(SchemaLoadException.class, () -> registry.load(broker, href, false));
            assertFalse(refused.getMessage().contains("by server configuration"), refused.getMessage());
            assertTrue(refused.getMessage().contains("remote schema access disabled"), refused.getMessage());

            // collection asked and the server permits it: the fetch is attempted (and fails on the unresolvable host)
            final SchemaLoadException attempted = assertThrows(SchemaLoadException.class, () -> registry.load(broker, href, true));
            assertFalse(attempted.getMessage().contains("remote schema access disabled"), attempted.getMessage());
        }
    }
}
