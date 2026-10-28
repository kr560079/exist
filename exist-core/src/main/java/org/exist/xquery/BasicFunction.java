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

import org.exist.xquery.value.Item;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.SequenceType;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.UserDefinedAtomicValue;
import org.exist.xquery.value.ValueSequence;
import org.exist.xquery.util.ExpressionDumper;

/**
 * Abstract base class for simple functions. Subclasses should overwrite
 * method {@link #eval(Sequence[], Sequence)}.
 *
 * @author <a href="mailto:wolfgang@exist-db.org">Wolfgang Meier</a>
 */
public abstract class BasicFunction extends Function {

    public BasicFunction(final XQueryContext context, final FunctionSignature signature) {
        super(context, signature);
    }

    @Override
	public Sequence eval(Sequence contextSequence, final Item contextItem) throws XPathException {
        if (context.getProfiler().isEnabled()) {
            context.getProfiler().start(this);
            context.getProfiler().message(this, Profiler.DEPENDENCIES, "DEPENDENCIES", Dependency.getDependenciesName(this.getDependencies()));
            if (contextSequence != null) {
                context.getProfiler().message(this, Profiler.START_SEQUENCES, "CONTEXT SEQUENCE", contextSequence);
            }
            if (contextItem != null) {
                context.getProfiler().message(this, Profiler.START_SEQUENCES, "CONTEXT ITEM", contextItem.toSequence());
            }
        }

        if (contextItem != null) {
            contextSequence = contextItem.toSequence();
        }

        final int argCount = getArgumentCount();
        final Sequence[] args = new Sequence[argCount];
        for (int i = 0; i < argCount; i++) {
            try {
                args[i] = unwrapUserTypes(i, getArgument(i).eval(contextSequence, contextItem));
            } catch (final XPathException e) {
                if (e.getErrorCode() == null || e.getErrorCode() == ErrorCodes.ERROR) {
                    e.prependMessage(
                            ErrorCodes.XPTY0004,
                            "checking function parameter " + (i + 1) + " in call " + ExpressionDumper.dump(this) + ": ");
                }

                throw e;
            }
        }

        final Sequence result = eval(args, contextSequence);

        if (context.getProfiler().isEnabled()) {
            context.getProfiler().end(this, "", result);
        }

        return result;
    }

    /**
     * Replace values of user-defined (schema) types in an argument by their built-in base values, if the
     * parameter is declared with a built-in atomic type other than {@code xs:anyAtomicType}. Implementations
     * may then cast the argument items to the value class of the declared type; the results of such
     * functions are values of built-in types. Parameters of type {@code xs:anyAtomicType} keep the user types,
     * so that functions which return their input items preserve them.
     *
     * @param argIndex the index of the argument
     * @param arg      the value of the argument
     * @return the argument value, unwrapped if needed
     * @throws XPathException if the argument cannot be iterated
     */
    private Sequence unwrapUserTypes(final int argIndex, final Sequence arg) throws XPathException {
        if (arg.isEmpty() || !hasBuiltInAtomicParameterType(argIndex)) {
            return arg;
        }
        if (arg.hasOne()) {
            final Item item = arg.itemAt(0);
            return item instanceof UserDefinedAtomicValue userValue ? userValue.unwrapUserType() : arg;
        }
        boolean hasUserValue = false;
        for (final SequenceIterator i = arg.iterate(); i.hasNext(); ) {
            if (i.nextItem() instanceof UserDefinedAtomicValue) {
                hasUserValue = true;
                break;
            }
        }
        if (!hasUserValue) {
            return arg;
        }
        final ValueSequence unwrapped = new ValueSequence(arg.getItemCount());
        for (final SequenceIterator i = arg.iterate(); i.hasNext(); ) {
            final Item item = i.nextItem();
            unwrapped.add(item instanceof UserDefinedAtomicValue userValue ? userValue.unwrapUserType() : item);
        }
        return unwrapped;
    }

    private boolean hasBuiltInAtomicParameterType(final int argIndex) {
        final SequenceType[] argTypes = getSignature().getArgumentTypes();
        if (argTypes == null || argTypes.length == 0) {
            return false;
        }
        final SequenceType argType;
        if (argIndex < argTypes.length) {
            argType = argTypes[argIndex];
        } else if (getSignature().isVariadic()) {
            argType = argTypes[argTypes.length - 1];
        } else {
            return false;
        }
        if (argType == null) {
            return false;
        }
        final int type = argType.getPrimaryType();
        return type != Type.ANY_ATOMIC_TYPE && (type == Type.NUMERIC || Type.subTypeOf(type, Type.ANY_ATOMIC_TYPE));
    }

    /**
     * Process the function. All arguments are passed in the array args. The number of
     * arguments, their type and cardinality have already been checked to match
     * the function signature.
     *
     * @param args The arguments given to the function.
     * @param contextSequence The context sequence for the function or null.
     * @throws XPathException An error occurred.
     *
     * @return The result of the XPath function
     */
    public abstract Sequence eval(Sequence[] args, Sequence contextSequence) throws XPathException;
}
