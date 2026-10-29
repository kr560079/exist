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

import org.exist.security.Subject;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.test.ExistEmbeddedServer;
import org.exist.xquery.XPathException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.exist.util.PropertiesBuilder.propertiesBuilder;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Remote {@code import schema} location hints, with the server-level allow-remote gate on: only dba users may have
 * them fetched, and errors never echo what the remote source answered.
 */
public class ImportSchemaRemoteTest {

    @RegisterExtension
    public static final ExistEmbeddedServer existEmbeddedServer = new ExistEmbeddedServer(
            propertiesBuilder().put(SchemaRegistry.PROPERTY_ALLOW_REMOTE, true).build(), true, true);

    private static final String SECRET = "SECRET-INTERNAL-DETAIL";

    private ServerSocket server;
    private Thread acceptor;
    private final AtomicInteger connections = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try (final Socket socket = server.accept()) {
                    connections.incrementAndGet();
                    final OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.0 500 " + SECRET + "\r\nContent-Type: text/plain\r\nContent-Length: " + SECRET.length()
                            + "\r\n\r\n" + SECRET).getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } catch (final IOException e) {
                    // closed
                }
            }
        }, "import-schema-remote-test");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        server.close();
        acceptor.join(5000);
    }

    private String query() {
        return "import schema namespace r='urn:example:remote' at 'http://127.0.0.1:" + server.getLocalPort() + "/r.xsd'; 1";
    }

    private XPathException importAs(final Subject subject) throws Exception {
        final BrokerPool pool = existEmbeddedServer.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(subject))) {
            return assertThrows(XPathException.class, () -> pool.getXQueryService().execute(broker, query(), null));
        }
    }

    @Test
    void nonDbaRemoteHintRefusedWithoutFetching() throws Exception {
        final XPathException e = importAs(existEmbeddedServer.getBrokerPool().getSecurityManager().getGuestSubject());
        assertEquals("XQST0059", e.getErrorCode().getErrorQName().getLocalPart(), e.getMessage());
        assertTrue(e.getMessage().contains("remote schema locations are not permitted"), e.getMessage());
        assertFalse(e.getMessage().contains(SECRET), e.getMessage());
        assertEquals(0, connections.get(), "no fetch may be attempted for a non-dba user");
    }

    @Test
    void dbaRemoteHintFailureDoesNotEchoRemoteText() throws Exception {
        final XPathException e = importAs(existEmbeddedServer.getBrokerPool().getSecurityManager().getSystemSubject());
        assertEquals("XQST0059", e.getErrorCode().getErrorQName().getLocalPart(), e.getMessage());
        assertTrue(connections.get() > 0, "the fetch should have been attempted");
        assertFalse(e.getMessage().contains(SECRET), e.getMessage());
        assertFalse(e.getMessage().contains("response code"), e.getMessage());
        assertTrue(e.getMessage().contains("the schema cannot be loaded"), e.getMessage());
    }

    @Test
    void remoteTimeoutsAreSet() {
        assertEquals(10_000, ChecksummingResourceResolver.REMOTE_CONNECT_TIMEOUT_MS);
        assertEquals(30_000, ChecksummingResourceResolver.REMOTE_READ_TIMEOUT_MS);
    }
}
