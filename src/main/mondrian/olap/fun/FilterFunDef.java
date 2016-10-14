/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2002-2005 Julian Hyde
// Copyright (C) 2005-2016 Pentaho and others
// All Rights Reserved.
*/
package mondrian.olap.fun;

import mondrian.calc.*;
import mondrian.calc.impl.*;
import mondrian.mdx.DimensionExpr;
import mondrian.mdx.HierarchyExpr;
import mondrian.mdx.LevelExpr;
import mondrian.mdx.ResolvedFunCall;
import mondrian.olap.*;
import mondrian.olap.type.MemberType;
import mondrian.olap.type.SetType;
import mondrian.server.Locus;
import mondrian.util.CancellationChecker;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Definition of the <code>Filter</code> MDX function.
 *
 * <p>Syntax:
 * <blockquote><code>Filter(&lt;Set&gt;, &lt;Search
 * Condition&gt;)</code></blockquote>
 *
 * @author jhyde
 * @since Mar 23, 2006
 */
class FilterFunDef extends FunDefBase {

    private static final String TIMING_NAME =
        FilterFunDef.class.getSimpleName();

    static final FilterFunDef instance = new FilterFunDef();

    private FilterFunDef() {
        super(
            "Filter",
            "Returns the set resulting from filtering a set based on a search condition.",
            "fxxb");
    }

    public Calc compileCall(final ResolvedFunCall call, ExpCompiler compiler) {
        // Ignore the caller's priority. We prefer to return iterable, because
        // it makes NamedSet.CurrentOrdinal work.
        List<ResultStyle> styles = compiler.getAcceptableResultStyles();
        if (call.getArg(0) instanceof ResolvedFunCall
            && ((ResolvedFunCall) call.getArg(0)).getFunName().equals("AS"))
        {
            styles = ResultStyle.ITERABLE_ONLY;
        }
        if (styles.contains(ResultStyle.ITERABLE)
            || styles.contains(ResultStyle.ANY))
        {
            return compileCallIterable(call, compiler);
        } else if (styles.contains(ResultStyle.LIST)
            || styles.contains(ResultStyle.MUTABLE_LIST))
        {
            return compileCallList(call, compiler);
        } else {
            throw ResultStyleException.generate(
                ResultStyle.ITERABLE_LIST_MUTABLELIST_ANY,
                styles);
        }
    }

    /**
     * Returns an IterCalc.
     *
     * <p>Here we would like to get either a IterCalc or ListCalc (mutable)
     * from the inner expression. For the IterCalc, its Iterator
     * can be wrapped with another Iterator that filters each element.
     * For the mutable list, remove all members that are filtered.
     *
     * @param call Call
     * @param compiler Compiler
     * @return Implementation of this function call in the Iterable result style
     */
    protected IterCalc compileCallIterable(
        final ResolvedFunCall call,
        ExpCompiler compiler)
    {
        // want iterable, mutable list or immutable list in that order
        Calc imlcalc = compiler.compileAs(
            call.getArg(0), null, ResultStyle.ITERABLE_LIST_MUTABLELIST);
        BooleanCalc bcalc = compiler.compileBoolean(call.getArg(1));
        Calc[] calcs = new Calc[] {imlcalc, bcalc};
        boolean existing = false;
        // CJArg should now handle this case
//        if (call.getArg(0) instanceof ResolvedFunCall) {
//            if (((ResolvedFunCall)call.getArg(0)).getFunName().equalsIgnoreCase("existing")) {
//                existing = true;
//            }
//        }

        // check returned calc ResultStyles
        checkIterListResultStyles(imlcalc);

        if (imlcalc.getResultStyle() == ResultStyle.ITERABLE) {
            return new IterIterCalc(call, calcs, existing);
        } else if (imlcalc.getResultStyle() == ResultStyle.LIST) {
            return new ImmutableIterCalc(call, calcs, existing);
        } else {
            return new MutableIterCalc(call, calcs, existing);
        }
    }

    private static abstract class BaseIterCalc extends AbstractIterCalc {
        boolean existing = false;
        protected BaseIterCalc(ResolvedFunCall call, Calc[] calcs, boolean existing) {
            super(call, calcs);
            this.existing = existing;
        }

        public TupleIterable evaluateIterable(Evaluator evaluator) {
            evaluator.getTiming().markStart(TIMING_NAME);
            try {
                ResolvedFunCall call = (ResolvedFunCall) exp;
                // Use a native evaluator, if more efficient.
                // TODO: Figure this out at compile time.
                SchemaReader schemaReader = evaluator.getSchemaReader();
                NativeEvaluator nativeEvaluator =
                    schemaReader.getNativeSetEvaluator(
                        call.getFunDef(),
                        call.getArgs(),
                        evaluator,
                        this);
                if (nativeEvaluator != null) {
                    return (TupleIterable)
                        nativeEvaluator.execute(ResultStyle.ITERABLE);
                } else {
                    TupleIterable tuples = evaluateIterableLevels(evaluator);
                    return tuples != null ? tuples : makeIterable(evaluator, getCalcs()[0], (BooleanCalc) getCalcs()[1]);
                }
            } finally {
                evaluator.getTiming().markEnd(TIMING_NAME);
            }
        }

        private TupleIterable evaluateIterableLevels(Evaluator evaluator) {
            if (!MondrianProperties.instance().EnableNativeFilter.get()) {
                return null;
            }

            final HashSet<String> supported = new HashSet<>(
                Arrays.asList(new String[] { "members", "allmembers" }));
            ResolvedFunCall call = (ResolvedFunCall) exp;
            Exp[] args = call.getArgs();
            ResolvedFunCall arg0 = FunUtil.extractResolvedFunCall(args[0]);
            if (arg0 == null || arg0.getArgCount() != 1
                || !supported.contains(arg0.getFunName().toLowerCase()))
            {
                return null;
            }

            Hierarchy hierarchy = null;
            if (arg0.getArg(0) instanceof HierarchyExpr) {
                hierarchy = ((HierarchyExpr)arg0.getArg(0)).getHierarchy();
            } else if (arg0.getArg(0) instanceof DimensionExpr) {
                Dimension dimension = ((DimensionExpr)arg0.getArg(0)).getDimension();
                if (dimension.getHierarchies().length == 1) {
                    hierarchy = dimension.getHierarchies()[0];
                }
            }

            if (hierarchy == null) {
                return null;
            }

            ExpCompiler expCompiler = evaluator.getQuery().createCompiler();
            TupleList result = new UnaryTupleList();
            List<Member> members = result.slice(0);
            for (Level level : hierarchy.getLevels()) {
                int save = evaluator.savepoint();
                try {
                    TupleIterable tuples =
                        evaluateForLevel(
                            level, call.getFunDef(), args.clone(),
                            arg0.getFunName(), evaluator, expCompiler);
                    if (tuples == null) {
                        return null;
                    } else {
                        for (Member m : tuples.slice(0)) {
                            members.add(m);
                        }
                    }
                } finally {
                    evaluator.restore(save);
                }
            }
            return FunUtil.hierarchizeTupleList(result, false);
        }

        private TupleIterable evaluateForLevel(
            Level level, FunDef funDef, Exp[] args,
            String membersFunName, Evaluator evaluator,
            ExpCompiler expCompiler)
        {
            Exp[] levelArgs = new Exp[] { new LevelExpr(level) };
            FunDef levelFun = expCompiler.getValidator()
                .getDef(levelArgs, membersFunName, Syntax.Property);
            args[0] = new ResolvedFunCall(
                levelFun, levelArgs, new SetType(MemberType.Unknown));
            if (level.isAll()) {
                Calc imlcalc = expCompiler.compileAs(
                    args[0], null, ResultStyle.ITERABLE_LIST_MUTABLELIST);
                return makeIterable(evaluator, imlcalc, (BooleanCalc) getCalcs()[1]);
            }
            NativeEvaluator nativeEvaluator = evaluator.getSchemaReader()
                .getNativeSetEvaluator(funDef, args, evaluator, this);
            if (nativeEvaluator != null) {
                return (TupleIterable) nativeEvaluator.execute(ResultStyle.ITERABLE);
            } else {
                return null;
            }
        }

        protected abstract TupleIterable makeIterable(Evaluator evaluator, Calc calc, BooleanCalc bcalc);

        public boolean dependsOn(Hierarchy hierarchy) {
            if (existing) {
                // native evaluation would wipe out the context
                // if we don't depend on everything.
                return true;
            } else {
                return anyDependsButFirst(getCalcs(), hierarchy);
            }
        }
    }

    private static class MutableIterCalc extends BaseIterCalc {
        MutableIterCalc(ResolvedFunCall call, Calc[] calcs, boolean existing) {
            super(call, calcs, existing);
            assert calcs[0] instanceof ListCalc;
            assert calcs[1] instanceof BooleanCalc;
        }

        protected TupleIterable makeIterable(Evaluator evaluator, Calc calc, BooleanCalc bcalc) {
            evaluator.getTiming().markStart(TIMING_NAME);
            final int savepoint = evaluator.savepoint();
            try {
                ListCalc lcalc = (ListCalc) calc;
                TupleList list = lcalc.evaluateList(evaluator);

                // make list mutable; guess selectivity .5
                TupleList result =
                    TupleCollections.createList(
                        list.getArity(), list.size() / 2);
                evaluator.setNonEmpty(false);
                TupleCursor cursor = list.tupleCursor();
                int currentIteration = 0;
                CancellationChecker cancellationChecker = new CancellationChecker(
                    evaluator.getQuery().getStatement().getCurrentExecution());
                while (cursor.forward()) {
                    cancellationChecker.check(currentIteration++);
                    cursor.setContext(evaluator);
                    if (bcalc.evaluateBoolean(evaluator)) {
                        result.addCurrent(cursor);
                    }
                }
                return result;
            } finally {
                evaluator.restore(savepoint);
                evaluator.getTiming().markEnd(TIMING_NAME);
            }
        }
    }

    private static class ImmutableIterCalc extends BaseIterCalc {
        ImmutableIterCalc(ResolvedFunCall call, Calc[] calcs, boolean existing) {
            super(call, calcs, existing);
            assert calcs[0] instanceof ListCalc;
            assert calcs[1] instanceof BooleanCalc;
        }

        protected TupleIterable makeIterable(Evaluator evaluator, Calc calc, BooleanCalc bcalc) {
            ListCalc lcalc = (ListCalc) calc;
            TupleList members = lcalc.evaluateList(evaluator);

            // Not mutable, must create new list
            TupleList result = members.cloneList(members.size() / 2);
            final int savepoint = evaluator.savepoint();
            try {
                evaluator.setNonEmpty(false);
                TupleCursor cursor = members.tupleCursor();
                int currentIteration = 0;
                CancellationChecker cancellationChecker = new CancellationChecker(
                    evaluator.getQuery().getStatement().getCurrentExecution());
                while (cursor.forward()) {
                    cancellationChecker.check(currentIteration++);
                    cursor.setContext(evaluator);
                    if (bcalc.evaluateBoolean(evaluator)) {
                        result.addCurrent(cursor);
                    }
                }
                return result;
            } finally {
                evaluator.restore(savepoint);
            }
        }
    }

    private static class IterIterCalc
        extends BaseIterCalc
    {
        IterIterCalc(ResolvedFunCall call, Calc[] calcs, boolean existing) {
            super(call, calcs, existing);
            assert calcs[0] instanceof IterCalc;
            assert calcs[1] instanceof BooleanCalc;
        }

        protected TupleIterable makeIterable(Evaluator evaluator, final Calc calc, final BooleanCalc bcalc) {
            IterCalc icalc = (IterCalc) calc;

            // This does dynamics, just in time,
            // as needed filtering
            final TupleIterable iterable =
                icalc.evaluateIterable(evaluator);
            final Evaluator evaluator2 = evaluator.push();
            evaluator2.setNonEmpty(false);
            return new AbstractTupleIterable(iterable.getArity()) {
                public TupleCursor tupleCursor() {
                    return new AbstractTupleCursor(iterable.getArity()) {
                        final TupleCursor cursor = iterable.tupleCursor();

                        public boolean forward() {
                            int currentIteration = 0;
                            CancellationChecker cancellationChecker =
                                new CancellationChecker(Locus.peek().execution);
                            while (cursor.forward()) {
                                cancellationChecker.check(currentIteration++);
                                cursor.setContext(evaluator2);
                                if (bcalc.evaluateBoolean(evaluator2)) {
                                    return true;
                                }
                            }
                            return false;
                        }

                        public List<Member> current() {
                            return cursor.current();
                        }
                    };
                }
            };
        }
    }


    /**
     * Returns a ListCalc.
     *
     * @param call Call
     * @param compiler Compiler
     * @return Implementation of this function call in the List result style
     */
    protected ListCalc compileCallList(
        final ResolvedFunCall call,
        ExpCompiler compiler)
    {
        Calc ilcalc = compiler.compileList(call.getArg(0), false);
        BooleanCalc bcalc = compiler.compileBoolean(call.getArg(1));
        Calc[] calcs = new Calc[] {ilcalc, bcalc};

        // Note that all of the ListCalc's return will be mutable
        switch (ilcalc.getResultStyle()) {
        case LIST:
            return new ImmutableListCalc(call, calcs);
        case MUTABLE_LIST:
            return new MutableListCalc(call, calcs);
        }
        throw ResultStyleException.generateBadType(
            ResultStyle.MUTABLELIST_LIST,
            ilcalc.getResultStyle());
    }

    private static abstract class BaseListCalc extends AbstractListCalc {
        protected BaseListCalc(ResolvedFunCall call, Calc[] calcs) {
            super(call, calcs);
        }

        public TupleList evaluateList(Evaluator evaluator) {
            ResolvedFunCall call = (ResolvedFunCall) exp;
            // Use a native evaluator, if more efficient.
            // TODO: Figure this out at compile time.
            SchemaReader schemaReader = evaluator.getSchemaReader();
            NativeEvaluator nativeEvaluator =
                schemaReader.getNativeSetEvaluator(
                    call.getFunDef(), call.getArgs(), evaluator, this);
            if (nativeEvaluator != null) {
                return (TupleList) nativeEvaluator.execute(
                    ResultStyle.ITERABLE);
            } else {
                return makeList(evaluator);
            }
        }
        protected abstract TupleList makeList(Evaluator evaluator);

        public boolean dependsOn(Hierarchy hierarchy) {
            return anyDependsButFirst(getCalcs(), hierarchy);
        }
    }

    private static class MutableListCalc extends BaseListCalc {
        MutableListCalc(ResolvedFunCall call, Calc[] calcs) {
            super(call, calcs);
            assert calcs[0] instanceof ListCalc;
            assert calcs[1] instanceof BooleanCalc;
        }

        protected TupleList makeList(Evaluator evaluator) {
            Calc[] calcs = getCalcs();
            ListCalc lcalc = (ListCalc) calcs[0];
            BooleanCalc bcalc = (BooleanCalc) calcs[1];
            TupleList members0 = lcalc.evaluateList(evaluator);

            // make list mutable;
            // for capacity planning, guess selectivity = .5
            TupleList result = members0.cloneList(members0.size() / 2);
            final int savepoint = evaluator.savepoint();
            try {
                evaluator.setNonEmpty(false);
                final TupleCursor cursor = members0.tupleCursor();
                int currentIteration = 0;
                CancellationChecker cancellationChecker = new CancellationChecker(
                    evaluator.getQuery().getStatement().getCurrentExecution());
                while (cursor.forward()) {
                    cancellationChecker.check(currentIteration++);
                    cursor.setContext(evaluator);
                    if (bcalc.evaluateBoolean(evaluator)) {
                        result.addCurrent(cursor);
                    }
                }
                return result;
            } finally {
                evaluator.restore(savepoint);
            }
        }
    }

    private static class ImmutableListCalc extends BaseListCalc {
        ImmutableListCalc(ResolvedFunCall call, Calc[] calcs) {
            super(call, calcs);
            assert calcs[0] instanceof ListCalc;
            assert calcs[1] instanceof BooleanCalc;
        }

        protected TupleList makeList(Evaluator evaluator) {
            evaluator.getTiming().markStart(TIMING_NAME);
            final int savepoint = evaluator.savepoint();
            try {
                Calc[] calcs = getCalcs();
                ListCalc lcalc = (ListCalc) calcs[0];
                BooleanCalc bcalc = (BooleanCalc) calcs[1];
                TupleList members0 = lcalc.evaluateList(evaluator);

                // Not mutable, must create new list;
                // for capacity planning, guess selectivity = .5
                TupleList result = members0.cloneList(members0.size() / 2);
                evaluator.setNonEmpty(false);
                final TupleCursor cursor = members0.tupleCursor();
                int currentIteration = 0;
                CancellationChecker cancellationChecker =
                    new CancellationChecker(evaluator.getQuery()
                        .getStatement().getCurrentExecution());
                while (cursor.forward()) {
                    cancellationChecker.check(currentIteration++);
                    cursor.setContext(evaluator);
                    if (bcalc.evaluateBoolean(evaluator)) {
                        result.addCurrent(cursor);
                    }
                }
                return result;
            } finally {
                evaluator.restore(savepoint);
                evaluator.getTiming().markEnd(TIMING_NAME);
            }
        }
    }
}

// End FilterFunDef.java
