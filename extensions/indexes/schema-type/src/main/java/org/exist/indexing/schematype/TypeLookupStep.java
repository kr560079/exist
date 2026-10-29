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

import org.exist.dom.persistent.NewArrayNodeSet;
import org.exist.dom.persistent.NodeProxy;
import org.exist.dom.persistent.NodeSet;
import org.exist.xquery.Constants;
import org.exist.xquery.IndexUseReporter;
import org.exist.xquery.LocationStep;
import org.exist.xquery.PerformanceStats;
import org.exist.xquery.TypedNodeTest;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.schema.AnnotationLookupException;
import org.exist.xquery.schema.TypeAnnotationProvider;

import javax.annotation.Nullable;

/**
 * The type&rarr;nodes lookup of a descendant step with a typed node test, installed by
 * {@link SchemaTypeQueryRewriter}: the candidates are the nodes of the context documents annotated with the test's
 * type or a type derived from it ({@link SchemaTypeIndexWorker#candidatesOfType}); those below the context nodes
 * which pass the test itself (name, nilled, required annotation) are the step's nodes. If the index cannot answer
 * for every context document, the step is evaluated as usual, for all of them.
 */
final class TypeLookupStep implements LocationStep.IndexLookup, IndexUseReporter {

    /** the index type reported to the profiler (system:trace) */
    static final String INDEX_TYPE = "schema-type";

    private final TypedNodeTest test;
    private volatile boolean usedIndex = false;

    TypeLookupStep(final TypedNodeTest test) {
        this.test = test;
    }

    @Override
    public @Nullable NodeSet lookup(final LocationStep step, final NodeSet contextSet) throws XPathException {
        final XQueryContext context = step.getContext();
        final long start = System.currentTimeMillis();
        if (!(TypeAnnotationProvider.of(context.getBroker()) instanceof SchemaTypeIndexWorker worker)) {
            return null;
        }
        final NodeSet candidates = worker.candidatesOfType(contextSet.getDocumentSet(), test.getTypeCode(), test.getType());
        if (candidates == null) {
            return null;
        }
        final NodeSet selected = candidates.isEmpty() ? candidates
                : candidates.selectAncestorDescendant(contextSet, NodeSet.DESCENDANT,
                        step.getAxis() == Constants.DESCENDANT_SELF_AXIS, step.getContextId(), true);
        final NodeSet result = new NewArrayNodeSet();
        try {
            for (final NodeProxy node : selected) {
                if (test.matches(node)) {
                    result.add(node);
                }
            }
        } catch (final AnnotationLookupException e) {
            // as for an unusable document: the step reports it, or not, as it would without the index
            return null;
        }
        usedIndex = true;
        if (context.getProfiler().traceFunctions()) {
            context.getProfiler().traceIndexUsage(context, INDEX_TYPE, step,
                    PerformanceStats.IndexOptimizationLevel.OPTIMIZED, System.currentTimeMillis() - start);
        }
        return result;
    }

    @Override
    public boolean hasUsedIndex() {
        return usedIndex;
    }
}
