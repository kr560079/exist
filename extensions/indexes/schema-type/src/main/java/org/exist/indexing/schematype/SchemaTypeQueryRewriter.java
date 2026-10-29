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

import org.exist.xquery.Constants;
import org.exist.xquery.LocationStep;
import org.exist.xquery.NameTest;
import org.exist.xquery.NodeTest;
import org.exist.xquery.Pragma;
import org.exist.xquery.QueryRewriter;
import org.exist.xquery.TypedNodeTest;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.schema.TypeAnnotationProvider;
import org.exist.xquery.value.Type;

import javax.annotation.Nullable;

/**
 * Answers descendant steps whose node test is {@code element(*, T)}, {@code attribute(*, T)},
 * {@code schema-element(N)} or {@code schema-attribute(N)} from the type&rarr;nodes table of the schema type index
 * (spec &sect;7.5), by installing a {@link TypeLookupStep} as the step's {@link LocationStep.IndexLookup}. The step
 * itself is not replaced: its predicates, its context and the fallback to the usual evaluation stay where they are.
 *
 * <p>A descendant step with a name test whose first predicate compares a schema-typed path with a literal
 * ({@code //N[M > 5]}) is answered from the typed-value table, see {@link TypedRangeLookup}.</p>
 *
 * <p>Typed tests with a name ({@code element(N, T)}) are not rewritten: they are answered from the structural index
 * by the name and filtered by type, which is cheaper for the usual, selective names. Nor are tests every node without
 * an annotation passes ({@code element(*, xs:anyType)}, {@code attribute(*, xs:anyAtomicType)}): the index has no
 * entries for unannotated nodes.</p>
 */
public class SchemaTypeQueryRewriter extends QueryRewriter {

    private @Nullable Boolean typedValuesConfigured = null;

    public SchemaTypeQueryRewriter(final XQueryContext context) {
        super(context);
    }

    @Override
    public @Nullable Pragma rewriteLocationStep(final LocationStep step) {
        final NodeTest test = step.getTest();
        if (step.getIndexLookup() != null) {
            return null;
        }
        if (!(test instanceof TypedNodeTest typed)) {
            // a comparison in the first predicate of a named step: the typed-value table
            if (test instanceof NameTest && step.getPredicates() != null && typedValuesConfigured()) {
                final TypedRangeLookup lookup = TypedRangeLookup.forStep(step);
                if (lookup != null) {
                    step.setIndexLookup(lookup);
                }
            }
            return null;
        }
        if (!typed.isWildcardTest()) {
            return null;
        }
        final int kind = typed.getType();
        final int axis = step.getAxis();
        final boolean answerable = switch (kind) {
            case Type.ELEMENT -> axis == Constants.DESCENDANT_AXIS || axis == Constants.DESCENDANT_SELF_AXIS;
            case Type.ATTRIBUTE -> axis == Constants.DESCENDANT_ATTRIBUTE_AXIS;
            default -> false;
        };
        if (!answerable || unannotatedNodesMatch(typed, kind)) {
            return null;
        }
        step.setIndexLookup(new TypeLookupStep(typed));
        // nothing to wrap the step in: the lookup is part of it
        return null;
    }

    /** whether some collection has typed values, looked up once per query */
    private boolean typedValuesConfigured() {
        if (typedValuesConfigured == null) {
            typedValuesConfigured = getContext().getBroker() != null
                    && TypeAnnotationProvider.of(getContext().getBroker()) instanceof SchemaTypeIndexWorker worker
                    && worker.hasTypedValueConfigurations();
        }
        return typedValuesConfigured;
    }

    /** whether a node without a stored annotation may pass the test: the index cannot find those */
    private static boolean unannotatedNodesMatch(final TypedNodeTest test, final int kind) {
        final int code = test.getTypeCode();
        if (code == Type.ANY_TYPE) {
            // nearly every node: nothing to gain
            return true;
        }
        if (test.isAnnotationRequired()) {
            return false;
        }
        final int unannotated = kind == Type.ELEMENT ? Type.ANY_TYPE : Type.UNTYPED_ATOMIC;
        try {
            return Type.subTypeOf(unannotated, code);
        } catch (final IllegalArgumentException e) {
            return true;
        }
    }
}
