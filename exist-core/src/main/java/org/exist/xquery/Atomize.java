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
import org.exist.dom.persistent.NodeProxy;
import org.exist.xquery.functions.array.ArrayType;
import org.exist.xquery.util.ExpressionDumper;
import org.exist.xquery.value.AtomicValue;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.ValueSequence;

import javax.annotation.Nullable;

/**
 * @author wolf
 */
public class Atomize extends AbstractExpression {

	private final Expression expression;
	
	public Atomize(XQueryContext context, Expression expr) {
		super(context);
		this.expression = expr;
	}
	
	/* (non-Javadoc)
     * @see org.exist.xquery.Expression#analyze(org.exist.xquery.Expression)
     */
    public void analyze(AnalyzeContextInfo contextInfo) throws XPathException {
    	contextInfo.setParent(this);
        expression.analyze(contextInfo);
    }
    
	/* (non-Javadoc)
	 * @see org.exist.xquery.Expression#eval(org.exist.xquery.StaticContext, org.exist.dom.persistent.DocumentSet, org.exist.xquery.value.Sequence, org.exist.xquery.value.Item)
	 */
	public Sequence eval(Sequence contextSequence, Item contextItem) throws XPathException {
        if (context.getProfiler().isEnabled()) {
            context.getProfiler().start(this);       
            context.getProfiler().message(this, Profiler.DEPENDENCIES, "DEPENDENCIES", Dependency.getDependenciesName(this.getDependencies()));
            if (contextSequence != null)
                {context.getProfiler().message(this, Profiler.START_SEQUENCES, "CONTEXT SEQUENCE", contextSequence);}
            if (contextItem != null)
                {context.getProfiler().message(this, Profiler.START_SEQUENCES, "CONTEXT ITEM", contextItem.toSequence());}
        }        
		
        final Sequence result = atomize(expression.eval(contextSequence, contextItem));

        if (context.getProfiler().isEnabled())           
            {context.getProfiler().end(this, "", result);}   
     
        return result;
	}

    /**
     * Atomize an item in a context which admits at most one atomic value (value comparisons, order by, group by and
     * switch keys, ...): a stored node's typed value may have several items (list types) or none (nilled elements).
     *
     * @param item the item
     * @param expr the expression for error reporting, or null
     * @return the atomic value, {@link AtomicValue#EMPTY_VALUE} if the typed value of a node is empty
     * @throws XPathException {@code XPTY0004} if the typed value of a node has more than one item
     */
    public static AtomicValue atomizeSingle(final Item item, @Nullable final Expression expr) throws XPathException {
        return item instanceof NodeProxy node ? node.atomizeSingle(expr) : item.atomize();
    }

    public static Sequence atomize(Sequence input) throws XPathException {
        if (input.isEmpty())
            {return Sequence.EMPTY_SEQUENCE;}
        input = ArrayType.flatten(input);
        if (input.hasOne()) {
            final Item item = input.itemAt(0);
            // a stored node's typed value may be a sequence (list types) or empty (nilled elements)
            return item instanceof NodeProxy node ? node.typedValue() : item.atomize();
        }

        Item next;
        final ValueSequence result = new ValueSequence();
        for(final SequenceIterator i = input.iterate(); i.hasNext(); ) {
            next = i.nextItem();
            if (next instanceof NodeProxy node) {
                final Sequence typedValue = node.typedValue();
                if (typedValue instanceof Item atomic) {
                    result.add(atomic);
                } else {
                    result.addAll(typedValue);
                }
            } else {
                result.add(next.atomize());
            }
        }
        return result;
    }

    public Expression getExpression() {
        return expression;
    }
    
	/* (non-Javadoc)
     * @see org.exist.xquery.Expression#dump(org.exist.xquery.util.ExpressionDumper)
     */
    public void dump(ExpressionDumper dumper) {
        if(dumper.verbosity() > 1)
            {dumper.display("atomize[");}
        expression.dump(dumper);
        if(dumper.verbosity() > 1)
            {dumper.display("]");}
    }
    
    public void accept(ExpressionVisitor visitor) {
    	expression.accept(visitor);
    }
    
    public String toString() {
        return expression.toString();
    }    
    
	/* (non-Javadoc)
	 * @see org.exist.xquery.Expression#returnsType()
	 */
	public int returnsType() {
		return Type.ANY_ATOMIC_TYPE;
	}
	
	/* (non-Javadoc)
	 * @see org.exist.xquery.AbstractExpression#getDependencies()
	 */
	public int getDependencies() {
		return expression.getDependencies();
	}

	public void setContextDocSet(DocumentSet contextSet) {
		super.setContextDocSet(contextSet);
		expression.setContextDocSet(contextSet);
	}
	
	/* (non-Javadoc)
	 * @see org.exist.xquery.AbstractExpression#resetState()
	 */
	public void resetState(boolean postOptimization) {
		super.resetState(postOptimization);
		expression.resetState(postOptimization);
	}

    public int getLine() {
        return expression.getLine();
    }

    public int getColumn() {
        return expression.getColumn();
    }

    public int getSubExpressionCount() {
    	return 1;
    }
    
    public Expression getSubExpression(int index) {
    	if (index == 0) {return expression;}
    	
	    throw new IndexOutOfBoundsException("Index: "+index+", Size: "+getSubExpressionCount());
    }
}
