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
package org.exist.xquery;

import org.exist.collections.Collection;
import org.exist.dom.persistent.NodeSet;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.txn.Txn;
import org.exist.test.ExistEmbeddedServer;
import org.exist.test.TestConstants;
import org.exist.util.MimeType;
import org.exist.util.StringInputSource;
import org.exist.xmldb.XmldbURI;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.StringValue;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ValueComparison#nodeSetCompare} without a context sequence must apply the comparison's own relation.
 * The method is called directly, as no query is known to reach this branch.
 */
class ValueComparisonNodeSetCompareTest {

    @RegisterExtension
    static final ExistEmbeddedServer server = new ExistEmbeddedServer(true, true);

    @ParameterizedTest
    @CsvSource({"EQ, b", "NEQ, 'a,c'", "LT, a", "LTEQ, 'a,b'", "GT, c", "GTEQ, 'b,c'"})
    void withoutContextAppliesTheRelation(final Constants.Comparison relation, final String expected) throws Exception {
        final BrokerPool pool = server.getBrokerPool();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()));
             final Txn txn = pool.getTransactionManager().beginTransaction()) {
            final Collection collection = broker.getOrCreateCollection(txn, TestConstants.TEST_COLLECTION_URI);
            broker.storeDocument(txn, XmldbURI.create("value-comparison.xml"),
                    new StringInputSource("<r><v>a</v><v>b</v><v>c</v></r>"), MimeType.XML_TYPE, collection);
            broker.saveCollection(txn, collection);
            txn.commit();

            final XQuery xquery = pool.getXQueryService();
            final NodeSet nodes = xquery.execute(broker,
                    "doc('" + TestConstants.TEST_COLLECTION_URI + "/value-comparison.xml')/r/v", null).toNodeSet();

            final XQueryContext context = new XQueryContext(pool);
            final ValueComparison comparison = new ValueComparison(context,
                    new LiteralValue(context, new StringValue("unused")),
                    new LiteralValue(context, new StringValue("b")), relation);
            final Sequence result = comparison.nodeSetCompare(nodes, null);

            final StringBuilder values = new StringBuilder();
            for (int i = 0; i < result.getItemCount(); i++) {
                values.append(i == 0 ? "" : ",").append(result.itemAt(i).getStringValue());
            }
            assertEquals(expected, values.toString());
        }
    }
}
