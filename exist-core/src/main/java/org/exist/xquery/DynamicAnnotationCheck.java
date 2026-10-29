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

import org.exist.dom.persistent.DocumentSet;
import org.exist.xquery.util.ExpressionDumper;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.SequenceType;
import org.exist.xquery.value.Type;

/**
 * Check that every item of a sequence passed to or returned from a function matches a sequence type whose nodes
 * are tested on their type annotations: {@code element(N, T)}, {@code attribute(N, T)}, {@code schema-element(N)},
 * {@code schema-attribute(N)} and {@code document-node(...)} of these. Only added for such sequence types.
 */
public class DynamicAnnotationCheck extends AbstractExpression {

    private final SequenceType type;
    private final Expression expression;

    /**
     * @param context    the context of the query
     * @param type       the required type, see {@link #isNeeded(SequenceType)}
     * @param expression the checked expression
     */
    public DynamicAnnotationCheck(final XQueryContext context, final SequenceType type, final Expression expression) {
        super(context);
        this.type = type;
        this.expression = expression;
    }

    /**
     * @param type a sequence type
     * @return true if its items must be tested on their type annotations
     */
    public static boolean isNeeded(final SequenceType type) {
        return type.getTypeAnnotation() != -1;
    }

    @Override
    public Sequence eval(final Sequence contextSequence, final Item contextItem) throws XPathException {
        final Sequence seq = expression.eval(contextSequence, contextItem);
        for (final SequenceIterator i = seq.iterate(); i.hasNext(); ) {
            final Item item = i.nextItem();
            final boolean matches;
            try {
                matches = type.checkType(item);
            } catch (final XPathException e) {
                if (e.getLine() <= 0) {
                    e.setLocation(expression.getLine(), expression.getColumn(), expression.getSource());
                }
                throw e;
            }
            if (!matches) {
                throw new XPathException(expression, ErrorCodes.XPTY0004, "Type error in expression: required type is "
                        + type + "; got: " + Type.getTypeName(item.getType()) + ": " + item.getStringValue());
            }
        }
        return seq;
    }

    @Override
    public int returnsType() {
        return expression.returnsType();
    }

    @Override
    public Cardinality getCardinality() {
        return expression.getCardinality();
    }

    @Override
    public int getDependencies() {
        return expression.getDependencies();
    }

    @Override
    public void resetState(final boolean postOptimization) {
        super.resetState(postOptimization);
        expression.resetState(postOptimization);
    }

    @Override
    public void analyze(final AnalyzeContextInfo contextInfo) throws XPathException {
        contextInfo.setParent(this);
        expression.analyze(contextInfo);
    }

    @Override
    public void dump(final ExpressionDumper dumper) {
        if (dumper.verbosity() > 1) {
            dumper.display("dynamic-annotation-check[").display(type.toString()).display(", ");
        }
        expression.dump(dumper);
        if (dumper.verbosity() > 1) {
            dumper.display("]");
        }
    }

    @Override
    public String toString() {
        return expression.toString();
    }

    @Override
    public void setContextDocSet(final DocumentSet contextSet) {
        super.setContextDocSet(contextSet);
        expression.setContextDocSet(contextSet);
    }

    @Override
    public void accept(final ExpressionVisitor visitor) {
        expression.accept(visitor);
    }

    @Override
    public int getLine() {
        return expression.getLine();
    }

    @Override
    public int getColumn() {
        return expression.getColumn();
    }
}
