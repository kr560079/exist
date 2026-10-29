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
import org.exist.dom.persistent.DocumentSet;
import org.exist.dom.persistent.NewArrayNodeSet;
import org.exist.dom.persistent.NodeProxy;
import org.exist.dom.persistent.NodeSet;
import org.exist.indexing.StructuralIndex;
import org.exist.indexing.schematype.SchemaTypeIndexWorker.TypedScope;
import org.exist.storage.ElementValue;
import org.exist.storage.IndexSpec;
import org.exist.xquery.Atomize;
import org.exist.xquery.CastExpression;
import org.exist.xquery.ChildSelector;
import org.exist.xquery.Constants;
import org.exist.xquery.Constants.Comparison;
import org.exist.xquery.DescendantOrSelfSelector;
import org.exist.xquery.DescendantSelector;
import org.exist.xquery.DynamicCardinalityCheck;
import org.exist.xquery.DynamicTypeCheck;
import org.exist.xquery.Expression;
import org.exist.xquery.GeneralComparison;
import org.exist.xquery.IndexUseReporter;
import org.exist.xquery.LiteralValue;
import org.exist.xquery.LocationStep;
import org.exist.xquery.NameTest;
import org.exist.xquery.NodeSelector;
import org.exist.xquery.NodeTest;
import org.exist.xquery.PathExpr;
import org.exist.xquery.PerformanceStats;
import org.exist.xquery.Predicate;
import org.exist.xquery.Constants.StringTruncationOperator;
import org.exist.xquery.TypedNodeTest;
import org.exist.xquery.ValueComparison;
import org.exist.xquery.XPathException;
import org.exist.xquery.XQueryContext;
import org.exist.xquery.schema.TypeAnnotationProvider;
import org.exist.xquery.value.AbstractDateTimeValue;
import org.exist.xquery.value.AtomicValue;
import org.exist.xquery.value.FloatValue;
import org.exist.xquery.value.DoubleValue;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.Sequence;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.Type;
import org.exist.xquery.value.UserDefinedType;
import org.exist.xquery.value.UserTypeRegistry;

import javax.annotation.Nullable;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.XMLGregorianCalendar;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Answers a descendant step whose first predicate compares a schema-typed path with a literal,
 * {@code //N[P op L]} with {@code P} one of {@code .}, a child element {@code M} or an attribute {@code @A} and
 * {@code op} one of {@code = < <= > >= eq lt le gt ge}, from the typed-value table of the schema type index (spec
 * &sect;7.5), installed by {@link SchemaTypeQueryRewriter} as the step's {@link LocationStep.IndexLookup}.
 *
 * <p>The lookup returns the step's nodes which have a compared node whose typed value has a key in the ranges of the
 * literal: a superset of the nodes for which the predicate is true (the ranges are inclusive, and widened where the
 * comparison converts a value), and a subset of the step's nodes. The step then applies its predicates as usual, the
 * comparison included, so that the result is exactly that of the usual evaluation, provided that every step node left
 * out would have made the predicate false without an error. The lookup therefore only answers (and otherwise returns
 * null, and the step is evaluated as usual) if:</p>
 * <ul>
 *   <li>the literal (a literal, a cast or constructor of one, or a sequence of them) evaluates to atomic values of
 *       one comparable family ({@link TypedKeys}), numbers counting as one; for a value comparison to exactly one; NaN
 *       is not looked up;</li>
 *   <li>every document is answerable ({@link SchemaTypeIndexWorker#typedScopes}) and its collection has no legacy
 *       range index on the compared name (that index already answers the comparison);</li>
 *   <li>every compared node below the step's nodes is annotated with a listed type whose values are of the literal's
 *       family (and, for a value comparison, not a list type) and has an indexable value: an unannotated node, one
 *       of another family, or one whose value could not be indexed would compare differently or raise an error;</li>
 *   <li>for a value comparison, no step node has two compared children (the comparison would raise XPTY0004).</li>
 * </ul>
 * <p>{@code !=} and {@code ne} are never answered. Strings are compared by codepoint, so the default collation must be
 * the codepoint collation. Dates and times without timezone compare with an implicit timezone: their keys are kept
 * apart and the ranges over them are widened by the largest timezone offset, 14 hours.</p>
 */
final class TypedRangeLookup implements LocationStep.IndexLookup, IndexUseReporter {

    /** the node the comparison compares, relative to the step's node */
    enum Leaf { SELF, CHILD, ATTRIBUTE }

    /** the largest timezone offset, in seconds */
    private static final BigDecimal MAX_TZ_SECONDS = BigDecimal.valueOf(14 * 3600);

    private final GeneralComparison comparison;
    /** the relation, with the path on the left */
    private final Comparison relation;
    private final boolean valueComparison;
    private final Leaf leaf;
    private final @Nullable QName leafName;
    private final Expression literal;
    private volatile boolean usedIndex = false;

    private TypedRangeLookup(final GeneralComparison comparison, final Comparison relation, final Leaf leaf,
            @Nullable final QName leafName, final Expression literal) {
        this.comparison = comparison;
        this.relation = relation;
        this.valueComparison = comparison instanceof ValueComparison;
        this.leaf = leaf;
        this.leafName = leafName;
        this.literal = literal;
    }

    // --- rewriting ---

    /**
     * @return a lookup for a step whose first predicate is a comparison the typed-value table may answer, or null
     */
    static @Nullable TypedRangeLookup forStep(final LocationStep step) {
        final int axis = step.getAxis();
        if (axis != Constants.DESCENDANT_AXIS && axis != Constants.DESCENDANT_SELF_AXIS) {
            return null;
        }
        if (!isName(step.getTest(), Type.ELEMENT)) {
            return null;
        }
        final Predicate[] predicates = step.getPredicates();
        if (predicates == null || predicates.length == 0 || predicates[0].getLength() != 1
                || !(predicates[0].getExpression(0) instanceof GeneralComparison comparison)
                || comparison.getTruncation() != StringTruncationOperator.NONE) {
            return null;
        }
        final Comparison relation = comparison.getRelation();
        if (relation != Comparison.EQ && relation != Comparison.LT && relation != Comparison.LTEQ
                && relation != Comparison.GT && relation != Comparison.GTEQ) {
            return null;
        }
        LocationStep path = leafStep(comparison.getLeft());
        if (path != null && isLiteral(comparison.getRight())) {
            return create(comparison, relation, path, comparison.getRight());
        }
        path = leafStep(comparison.getRight());
        if (path != null && isLiteral(comparison.getLeft())) {
            return create(comparison, flip(relation), path, comparison.getLeft());
        }
        return null;
    }

    private static @Nullable TypedRangeLookup create(final GeneralComparison comparison, final Comparison relation,
            final LocationStep path, final Expression literal) {
        final NodeTest test = path.getTest();
        return switch (path.getAxis()) {
            case Constants.SELF_AXIS -> new TypedRangeLookup(comparison, relation, Leaf.SELF, null, literal);
            case Constants.CHILD_AXIS -> new TypedRangeLookup(comparison, relation, Leaf.CHILD, test.getName(), literal);
            case Constants.ATTRIBUTE_AXIS -> new TypedRangeLookup(comparison, relation, Leaf.ATTRIBUTE, test.getName(), literal);
            default -> null;
        };
    }

    private static Comparison flip(final Comparison relation) {
        return switch (relation) {
            case LT -> Comparison.GT;
            case GT -> Comparison.LT;
            case LTEQ -> Comparison.GTEQ;
            case GTEQ -> Comparison.LTEQ;
            default -> relation;
        };
    }

    /** a plain name test (not a typed test, not a wildcard) for a node kind */
    private static boolean isName(final NodeTest test, final int kind) {
        return test instanceof NameTest && !(test instanceof TypedNodeTest) && test.getType() == kind
                && !test.isWildcardTest() && test.getName() != null;
    }

    /** the step of a compared path: {@code .}, {@code M} or {@code @A}, without predicates */
    private static @Nullable LocationStep leafStep(final Expression expression) {
        final Expression e = unwrap(expression);
        if (!(e instanceof LocationStep step) || (step.getPredicates() != null && step.getPredicates().length > 0)) {
            return null;
        }
        return switch (step.getAxis()) {
            case Constants.SELF_AXIS -> step.getTest().getType() == Type.NODE && step.getTest().isWildcardTest() ? step : null;
            case Constants.CHILD_AXIS -> isName(step.getTest(), Type.ELEMENT) ? step : null;
            case Constants.ATTRIBUTE_AXIS -> isName(step.getTest(), Type.ATTRIBUTE) ? step : null;
            default -> null;
        };
    }

    private static Expression unwrap(Expression e) {
        while (true) {
            if (e.getClass() == PathExpr.class && ((PathExpr) e).getLength() == 1) {
                e = ((PathExpr) e).getExpression(0);
            } else if ((e instanceof Atomize || e instanceof DynamicCardinalityCheck || e instanceof DynamicTypeCheck)
                    && e.getSubExpressionCount() == 1) {
                e = e.getSubExpression(0);
            } else {
                return e;
            }
        }
    }

    /** whether an expression is made of literals only (so that it may be evaluated once more, without context) */
    private static boolean isLiteral(final Expression expression) {
        final Expression e = unwrap(expression);
        if (e instanceof LiteralValue) {
            return true;
        }
        if (e instanceof CastExpression cast) {
            return isLiteral(cast.getInnerExpression());
        }
        if (e instanceof PathExpr path && !(e instanceof Predicate)) {
            for (int i = 0; i < path.getLength(); i++) {
                if (!isLiteral(path.getExpression(i))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    // --- lookup ---

    @Override
    public @Nullable NodeSet lookup(final LocationStep step, final NodeSet contextSet) throws XPathException {
        final XQueryContext context = step.getContext();
        final long start = System.currentTimeMillis();
        final Predicate[] predicates = step.getPredicates();
        if (predicates == null || predicates.length == 0 || predicates[0].getLength() != 1
                || predicates[0].getExpression(0) != comparison) {
            // another rewriter changed the predicate
            return null;
        }
        if (!(TypeAnnotationProvider.of(context.getBroker()) instanceof SchemaTypeIndexWorker worker)) {
            return null;
        }
        final List<AtomicValue> values = literalValues(context);
        if (values == null) {
            return null;
        }
        final int literalFamily = comparableFamily(values);
        if (literalFamily == TypedKeys.NO_FAMILY) {
            return null;
        }
        final DocumentSet docs = contextSet.getDocumentSet();
        final List<TypedScope> scopes = worker.typedScopes(docs);
        if (scopes == null) {
            return null;
        }
        final QName compared = leaf == Leaf.SELF ? step.getTest().getName() : leafName;
        for (final TypedScope scope : scopes) {
            final IndexSpec spec = scope.collection().getIndexConfiguration(context.getBroker());
            if (spec != null && (spec.getIndexByQName(compared) != null || spec.hasIndexesByPath())) {
                // a legacy range index answers the comparison itself
                return null;
            }
        }

        // the types whose values the literal compares with
        final List<short[]> selected = new ArrayList<>(scopes.size());
        final List<int[]> families = new ArrayList<>(scopes.size());
        for (final TypedScope scope : scopes) {
            final List<Short> ids = new ArrayList<>();
            final List<Integer> fams = new ArrayList<>();
            for (final short localTypeId : scope.table().allLocalIds()) {
                final int code = scope.table().codeOf(localTypeId);
                final QName name = code < 0 ? null : LocalTypeTable.typeNameOf(code);
                if (name == null || !scope.config().typedValueTypes().contains(name)) {
                    continue;
                }
                final long info = typeInfo(code);
                final int family = (int) (info >> 1);
                final boolean list = (info & 1) != 0;
                if (family != TypedKeys.NO_FAMILY && comparable(family, literalFamily) && !(list && valueComparison)) {
                    ids.add(localTypeId);
                    fams.add(family);
                }
            }
            final short[] idArray = new short[ids.size()];
            final int[] famArray = new int[ids.size()];
            for (int i = 0; i < idArray.length; i++) {
                idArray[i] = ids.get(i);
                famArray[i] = fams.get(i);
            }
            selected.add(idArray);
            families.add(famArray);
        }

        // the step's nodes, as the usual evaluation finds them
        final StructuralIndex structural = context.getBroker().getStructuralIndex();
        final NodeSelector selector = step.getAxis() == Constants.DESCENDANT_SELF_AXIS
                ? new DescendantOrSelfSelector(contextSet, step.getContextId())
                : new DescendantSelector(contextSet, step.getContextId());
        final NodeSet stepNodes = structural.findElementsByTagName(ElementValue.ELEMENT, docs, step.getTest().getName(), selector, step);
        if (stepNodes.isEmpty()) {
            return answered(context, step, start, stepNodes);
        }
        final NodeSet compareds = switch (leaf) {
            case SELF -> stepNodes;
            case CHILD -> structural.findElementsByTagName(ElementValue.ELEMENT, docs, leafName,
                    new ChildSelector(stepNodes, Expression.NO_CONTEXT_ID), step);
            case ATTRIBUTE -> structural.findElementsByTagName(ElementValue.ATTRIBUTE, docs, leafName,
                    new ChildSelector(stepNodes, Expression.NO_CONTEXT_ID), step);
        };
        if (compareds.isEmpty()) {
            // no step node has a node to compare: the predicate is false for all
            return answered(context, step, start, new NewArrayNodeSet());
        }

        try {
            final TypeIndexStore store = worker.store();
            // every compared node must be of a selected type, with an indexed value
            final NodeSet ofSelectedTypes = new NewArrayNodeSet();
            for (int s = 0; s < scopes.size(); s++) {
                final TypedScope scope = scopes.get(s);
                if (selected.get(s).length == 0) {
                    continue;
                }
                if (store.hasUnindexable(scope.collection().getId(), scope.docs(), selected.get(s))) {
                    return null;
                }
                ofSelectedTypes.addAll(store.nodesOfType(scope.docs(), selected.get(s)));
            }
            for (final NodeProxy node : compareds) {
                if (!ofSelectedTypes.contains(node)) {
                    return null;
                }
            }
            if (valueComparison && leaf == Leaf.CHILD && parents(compareds).getLength() != compareds.getLength()) {
                // a step node with two compared children: XPTY0004 (or a comparison of each, which only the step knows)
                return null;
            }

            // the compared nodes whose values may match
            final NodeSet matching = new NewArrayNodeSet();
            for (int s = 0; s < scopes.size(); s++) {
                final TypedScope scope = scopes.get(s);
                final short[] ids = selected.get(s);
                final int[] fams = families.get(s);
                for (int t = 0; t < ids.length; t++) {
                    for (final AtomicValue value : values) {
                        final List<byte[][]> ranges = ranges(fams[t], value, relation);
                        if (ranges == null) {
                            return null;
                        }
                        for (final byte[][] range : ranges) {
                            matching.addAll(store.typedRange(scope.collection().getId(), scope.docs(), new short[] {ids[t]},
                                    range[0], range[1]));
                        }
                    }
                }
            }
            final NodeSet targets = new NewArrayNodeSet();
            for (final NodeProxy node : matching) {
                if (compareds.contains(node)) {
                    targets.add(leaf == Leaf.SELF ? node
                            : new NodeProxy(null, node.getOwnerDocument(), node.getNodeId().getParentId()));
                }
            }
            final NodeSet result = new NewArrayNodeSet();
            if (!targets.isEmpty()) {
                for (final NodeProxy node : stepNodes) {
                    if (targets.contains(node)) {
                        result.add(node);
                    }
                }
            }
            return answered(context, step, start, result);
        } catch (final java.io.IOException e) {
            throw new XPathException(step, "Error reading the schema type index: " + e.getMessage(), e);
        }
    }

    private NodeSet answered(final XQueryContext context, final LocationStep step, final long start, final NodeSet result) {
        usedIndex = true;
        if (context.getProfiler().traceFunctions()) {
            context.getProfiler().traceIndexUsage(context, TypeLookupStep.INDEX_TYPE, step,
                    PerformanceStats.IndexOptimizationLevel.OPTIMIZED, System.currentTimeMillis() - start);
        }
        return result;
    }

    private static NodeSet parents(final NodeSet nodes) {
        final NodeSet parents = new NewArrayNodeSet();
        for (final NodeProxy node : nodes) {
            parents.add(new NodeProxy(null, node.getOwnerDocument(), node.getNodeId().getParentId()));
        }
        parents.removeDuplicates();
        return parents;
    }

    /**
     * @return the atomic values of the literal (of a user-defined type: of its base), or null if it cannot be looked
     *         up: an error, the empty sequence, more than one value in a value comparison, NaN
     */
    private @Nullable List<AtomicValue> literalValues(final XQueryContext context) {
        final Sequence sequence;
        try {
            sequence = literal.eval(null, null);
        } catch (final XPathException e) {
            // the comparison raises it
            return null;
        }
        if (sequence.isEmpty() || (valueComparison && sequence.getItemCount() != 1)) {
            return null;
        }
        final List<AtomicValue> values = new ArrayList<>(sequence.getItemCount());
        try {
            for (final SequenceIterator i = sequence.iterate(); i.hasNext(); ) {
                final Item item = i.nextItem();
                if (!(item instanceof AtomicValue atomic)) {
                    return null;
                }
                final AtomicValue value = atomic.unwrapUserType();
                if ((value instanceof DoubleValue d && Double.isNaN(d.getValue()))
                        || (value instanceof FloatValue f && Float.isNaN(f.getValue()))) {
                    return null;
                }
                final int family = TypedKeys.familyOf(value.getType());
                if ((family == TypedKeys.STRING || family == TypedKeys.ANY_URI) && context.getDefaultCollator() != null) {
                    return null;
                }
                values.add(value);
            }
        } catch (final XPathException e) {
            return null;
        }
        return values;
    }

    /** the family all values compare in (numbers: {@link TypedKeys#DECIMAL}), or {@link TypedKeys#NO_FAMILY} */
    private static int comparableFamily(final List<AtomicValue> values) {
        int result = TypedKeys.NO_FAMILY;
        for (final AtomicValue value : values) {
            int family = TypedKeys.familyOf(value.getType());
            if (family == TypedKeys.DOUBLE) {
                family = TypedKeys.DECIMAL;
            }
            if (family == TypedKeys.NO_FAMILY || (result != TypedKeys.NO_FAMILY && family != result)) {
                return TypedKeys.NO_FAMILY;
            }
            result = family;
        }
        return result;
    }

    /** whether values of a type family compare with literals of a family ({@link #comparableFamily}) */
    private static boolean comparable(final int typeFamily, final int literalFamily) {
        if (literalFamily == TypedKeys.DECIMAL) {
            return typeFamily == TypedKeys.DECIMAL || typeFamily == TypedKeys.DOUBLE;
        }
        return typeFamily == literalFamily;
    }

    /**
     * The family of the items of the typed values of a type and whether they are lists.
     *
     * @return {@code family << 1 | list}; the family is {@link TypedKeys#NO_FAMILY} if the items are not all of one
     */
    static long typeInfo(final int code) {
        final UserDefinedType user = UserTypeRegistry.get(code);
        if (user == null) {
            if (code == Type.NMTOKENS || code == Type.IDREFS || code == Type.ENTITIES) {
                return ((long) TypedKeys.STRING << 1) | 1;
            }
            return (long) TypedKeys.familyOf(code) << 1;
        }
        return switch (user.variety()) {
            case ATOMIC -> {
                int base = user.baseCode();
                while (UserTypeRegistry.isUserType(base)) {
                    base = UserTypeRegistry.get(base).baseCode();
                }
                yield (long) TypedKeys.familyOf(base) << 1;
            }
            case LIST -> {
                final long item = typeInfo(user.memberCodes()[0]);
                yield (item & 1) != 0 ? (long) TypedKeys.NO_FAMILY << 1 : item | 1;
            }
            case UNION -> {
                int family = TypedKeys.NO_FAMILY;
                for (final int member : user.memberCodes()) {
                    final long info = typeInfo(member);
                    final int f = (int) (info >> 1);
                    if ((info & 1) != 0 || f == TypedKeys.NO_FAMILY || (family != TypedKeys.NO_FAMILY && f != family)) {
                        yield (long) TypedKeys.NO_FAMILY << 1;
                    }
                    family = f;
                }
                yield (long) family << 1;
            }
            case COMPLEX_SIMPLE_CONTENT -> typeInfo(user.memberCodes()[0]);
            default -> (long) TypedKeys.NO_FAMILY << 1;
        };
    }

    /**
     * The inclusive key ranges ({low, high} prefixes, see {@link TypeIndexStore#typedRange}) which hold the keys of
     * every value of a family that may compare true with a literal value.
     *
     * @return the ranges (none if no value compares true), or null if the index cannot tell
     */
    static @Nullable List<byte[][]> ranges(final int family, final AtomicValue value, final Comparison relation) {
        try {
            return rangesOf(family, value, relation);
        } catch (final XPathException | RuntimeException e) {
            return null;
        }
    }

    private static @Nullable List<byte[][]> rangesOf(final int family, final AtomicValue value, final Comparison relation)
            throws XPathException {
        final int literalFamily = TypedKeys.familyOf(value.getType());
        final List<byte[][]> ranges = new ArrayList<>(2);
        switch (family) {
            case TypedKeys.DECIMAL -> {
                final byte[] all = {TypedKeys.DECIMAL};
                if (literalFamily == TypedKeys.DECIMAL) {
                    final byte[] key = TypedKeys.decimalKey(new BigDecimal(value.getStringValue()));
                    if (key == null) {
                        return null;
                    }
                    ranges.add(bounds(relation, all, key, key));
                } else if (literalFamily == TypedKeys.DOUBLE) {
                    // the decimal is converted: every decimal which converts to a value next to the literal
                    final double d = doubleOf(value);
                    if (Double.isInfinite(d)) {
                        return null;
                    }
                    final double low = value instanceof FloatValue ? Math.nextDown((float) d) : Math.nextDown(d);
                    final double high = value instanceof FloatValue ? Math.nextUp((float) d) : Math.nextUp(d);
                    final byte[] lowKey = Double.isInfinite(low) ? all : TypedKeys.decimalKey(new BigDecimal(low));
                    final byte[] highKey = Double.isInfinite(high) ? all : TypedKeys.decimalKey(new BigDecimal(high));
                    if (lowKey == null || highKey == null) {
                        return null;
                    }
                    ranges.add(bounds(relation, all, lowKey, highKey));
                } else {
                    return null;
                }
            }
            case TypedKeys.DOUBLE -> {
                final byte[] numbers = {TypedKeys.DOUBLE, TypedKeys.DOUBLE_NUMBER};
                if (literalFamily == TypedKeys.DOUBLE) {
                    final byte[] key = TypedKeys.doubleKey(doubleOf(value));
                    ranges.add(bounds(relation, numbers, key, key));
                } else if (literalFamily == TypedKeys.DECIMAL) {
                    // the decimal is converted to a float or a double: the values next to both
                    final BigDecimal decimal = new BigDecimal(value.getStringValue());
                    final double d = decimal.doubleValue();
                    final float f = decimal.floatValue();
                    final double low = Math.min(Math.nextDown(d), Math.nextDown(f));
                    final double high = Math.max(Math.nextUp(d), Math.nextUp(f));
                    ranges.add(bounds(relation, numbers, TypedKeys.doubleKey(low), TypedKeys.doubleKey(high)));
                } else {
                    return null;
                }
            }
            case TypedKeys.DATE, TypedKeys.DATE_TIME, TypedKeys.TIME -> {
                if (literalFamily != family) {
                    return null;
                }
                final XMLGregorianCalendar cal = ((AbstractDateTimeValue) value).getTrimmedCalendar();
                final BigDecimal seconds = TypedKeys.seconds(cal, family);
                if (seconds == null) {
                    return null;
                }
                final boolean aware = cal.getTimezone() != DatatypeConstants.FIELD_UNDEFINED;
                final byte[] same = {(byte) family, aware ? TypedKeys.TZ_AWARE : TypedKeys.TZ_LESS};
                final byte[] other = {(byte) family, aware ? TypedKeys.TZ_LESS : TypedKeys.TZ_AWARE};
                final byte[] key = TypedKeys.numberKey(same, seconds);
                // with and without timezone: the implicit timezone is at most 14 hours away
                final byte[] low = TypedKeys.numberKey(other, seconds.subtract(MAX_TZ_SECONDS));
                final byte[] high = TypedKeys.numberKey(other, seconds.add(MAX_TZ_SECONDS));
                if (key == null || low == null || high == null) {
                    return null;
                }
                ranges.add(bounds(relation, same, key, key));
                ranges.add(bounds(relation, other, low, high));
            }
            case TypedKeys.DAY_TIME_DURATION, TypedKeys.YEAR_MONTH_DURATION -> {
                // not answered: eXist compares day-time durations with fractional seconds inexactly (P1D = P1DT0.5S),
                // so a key range could miss a node the comparison matches; XML Schema 1.0 has no such elements anyway
                return null;
            }
            case TypedKeys.STRING, TypedKeys.ANY_URI -> {
                if (literalFamily != family) {
                    return null;
                }
                final byte[] key = TypedKeys.encode(value);
                if (key == null) {
                    return null;
                }
                if (TypedKeys.isTruncatedString(value.getStringValue())) {
                    // the key stands for every string with its prefix
                    final byte[] prefix = java.util.Arrays.copyOf(key, key.length - 1);
                    ranges.add(bounds(relation, new byte[] {(byte) family}, prefix, prefix));
                } else {
                    ranges.add(bounds(relation, new byte[] {(byte) family}, key, key));
                }
            }
            default -> {
                return null;
            }
        }
        return ranges;
    }

    private static double doubleOf(final AtomicValue value) {
        return value instanceof FloatValue f ? f.getValue() : ((DoubleValue) value).getValue();
    }

    /**
     * @param all  the prefix of every key the relation may match
     * @param low  the smallest key equal to the literal
     * @param high the largest key equal to the literal
     */
    private static byte[][] bounds(final Comparison relation, final byte[] all, final byte[] low, final byte[] high) {
        return switch (relation) {
            case EQ -> new byte[][] {low, high};
            case LT, LTEQ -> new byte[][] {all, high};
            default -> new byte[][] {low, all};
        };
    }

    @Override
    public boolean hasUsedIndex() {
        return usedIndex;
    }
}
