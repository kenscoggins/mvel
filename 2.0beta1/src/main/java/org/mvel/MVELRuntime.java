package org.mvel;

import static org.mvel.DataConversion.canConvert;
import static org.mvel.Operator.*;
import static org.mvel.Soundex.soundex;
import org.mvel.ast.ASTNode;
import org.mvel.ast.LineLabel;
import org.mvel.compiler.CompiledExpression;
import org.mvel.debug.Debugger;
import org.mvel.debug.DebuggerContext;
import org.mvel.integration.VariableResolverFactory;
import org.mvel.integration.impl.ClassImportResolverFactory;
import org.mvel.util.ASTLinkedList;
import org.mvel.util.ExecutionStack;
import static org.mvel.util.ParseTools.containsCheck;
import static org.mvel.util.PropertyTools.isEmpty;
import static org.mvel.util.PropertyTools.similarity;
import org.mvel.util.Stack;

import static java.lang.String.valueOf;
import static java.lang.Thread.currentThread;

/**
 * This class contains the runtime for running compiled MVEL expressions.
 */
@SuppressWarnings({"CaughtExceptionImmediatelyRethrown"})
public class MVELRuntime {
    private static ThreadLocal<DebuggerContext> debuggerContext;

    /**
     * Main interpreter.
     *
     * @param debugger        -
     * @param expression      -
     * @param ctx             -
     * @param variableFactory -
     * @return -
     * @see org.mvel.MVEL
     */
    public static Object execute(boolean debugger, final CompiledExpression expression, final Object ctx, VariableResolverFactory variableFactory) {
        final ASTLinkedList node = new ASTLinkedList(expression.getInstructions().firstNode());

        if (expression.isImportInjectionRequired()) {
            variableFactory = new ClassImportResolverFactory(expression.getParserContext().getParserConfiguration(), variableFactory);
        }

        Stack stk = new ExecutionStack();
        Object v1, v2;

        ASTNode tk = null;
        Integer operator;

        try {
            while ((tk = node.nextNode()) != null) {
                if (tk.fields == -1) {
                    /**
                     * This may seem silly and redundant, however, when an MVEL script recurses into a block
                     * or substatement, a new runtime loop is entered.   Since the debugger state is not
                     * passed through the AST, it is not possible to forward the state directly.  So when we
                     * encounter a debugging symbol, we check the thread local to see if there is are registered
                     * breakpoints.  If we find them, we assume that we are debugging.
                     *
                     * The consequence of this of course, is that it's not ideal to compile expressions with
                     * debugging symbols which you plan to use in a production enviroment.
                     */
                    if (debugger || (debugger = hasDebuggerContext())) {
                        try {
                            debuggerContext.get().checkBreak((LineLabel) tk, variableFactory, expression);
                        }
                        catch (NullPointerException e) {
                            // do nothing for now.  this isn't as calus as it seems.   
                        }
                    }
                    continue;
                }
                else if (stk.isEmpty()) {
                    stk.push(tk.getReducedValueAccelerated(ctx, ctx, variableFactory));
                }

                switch (operator = tk.getOperator()) {
                    case NOOP:
                        continue;

                    case TERNARY:
                        if (!(Boolean) stk.pop()) {
                            //noinspection StatementWithEmptyBody
                            while (node.hasMoreNodes() && !node.nextNode().isOperator(TERNARY_ELSE)) ;
                        }
                        stk.clear();
                        continue;

                    case TERNARY_ELSE:
                        return stk.pop();

                    case END_OF_STMT:
                        /**
                         * If the program doesn't end here then we wipe anything off the stack that remains.
                         * Althought it may seem like intuitive stack optimizations could be leveraged by
                         * leaving hanging values on the stack,  trust me it's not a good idea.
                         */
                        if (node.hasMoreNodes()) {
                            stk.clear();
                        }

                        continue;
                }

                stk.push(node.nextNode().getReducedValueAccelerated(ctx, ctx, variableFactory), operator);

                try {
                    while (stk.size() > 1) {
                        switch ((Integer) stk.pop()) {
                            case CHOR:
                                v1 = stk.pop();
                                v2 = stk.pop();

                                if (!isEmpty(v2) || !isEmpty(v1)) {
                                    stk.clear();
                                    stk.push(!isEmpty(v2) ? v2 : v1);
                                }
                                else stk.push(null);
                                break;

                            case INSTANCEOF:
                                if ((v1 = stk.pop()) instanceof Class)
                                    stk.push(((Class) v1).isInstance(stk.pop()));
                                else
                                    stk.push(currentThread().getContextClassLoader().loadClass(valueOf(v1)).isInstance(stk.pop()));
                                break;

                            case CONVERTABLE_TO:
                                if ((v1 = stk.pop()) instanceof Class)
                                    stk.push(canConvert((stk.pop()).getClass(), (Class) v1));
                                else
                                    stk.push(canConvert((stk.pop()).getClass(), currentThread().getContextClassLoader().loadClass(valueOf(v1))));
                                break;

                            case CONTAINS:
                                stk.push(containsCheck(stk.peek2(), stk.pop2()));
                                break;

                            case SOUNDEX:
                                stk.push(soundex(valueOf(stk.pop())).equals(soundex(valueOf(stk.pop()))));
                                break;

                            case SIMILARITY:
                                stk.push(similarity(valueOf(stk.pop()), valueOf(stk.pop())));
                                break;

                        }
                    }
                }
                catch (ClassCastException e) {
                    throw new CompileException("syntax error or incomptable types", e);
                }
                catch (CompileException e) {
                    throw e;
                }
                catch (Exception e) {
                    throw new CompileException("failed to compile sub expression", e);
                }
            }

            return stk.pop();
        }
        catch (NullPointerException e) {
            if (tk != null && tk.isOperator() && !node.hasMoreNodes()) {
                throw new CompileException("incomplete statement: "
                        + tk.getName() + " (possible use of reserved keyword as identifier: " + tk.getName() + ")");
            }
            else {
                throw e;
            }
        }
    }

    /**
     * Register a debugger breakpoint.
     *
     * @param source - the source file the breakpoint is registered in
     * @param line   - the line number of the breakpoint
     */
    public static void registerBreakpoint(String source, int line) {
        ensureDebuggerContext();
        debuggerContext.get().registerBreakpoint(source, line);
    }

    /**
     * Remove a specific breakpoint.
     *
     * @param source - the source file the breakpoint is registered in
     * @param line   - the line number of the breakpoint to be removed
     */
    public static void removeBreakpoint(String source, int line) {
        if (hasDebuggerContext()) {
            debuggerContext.get().removeBreakpoint(source, line);
        }
    }

    private static boolean hasDebuggerContext() {
        return debuggerContext != null && debuggerContext.get() != null;
    }

    private static void ensureDebuggerContext() {
        if (debuggerContext == null) debuggerContext = new ThreadLocal<DebuggerContext>();
        if (debuggerContext.get() == null) debuggerContext.set(new DebuggerContext());
    }

    /**
     * Reset all the currently registered breakpoints.
     */
    public static void clearAllBreakpoints() {
        if (hasDebuggerContext()) {
            debuggerContext.get().clearAllBreakpoints();
        }
    }

    public static boolean hasBreakpoints() {
        return hasDebuggerContext() && debuggerContext.get().hasBreakpoints();
    }

    /**
     * Sets the Debugger instance to handle breakpoints.   A debugger may only be registered once per thread.
     * Calling this method more than once will result in the second and subsequent calls to simply fail silently.
     * To re-register the Debugger, you must call {@link #resetDebugger}
     *
     * @param debugger - debugger instance
     */
    public static void setThreadDebugger(Debugger debugger) {
        ensureDebuggerContext();
        debuggerContext.get().setDebugger(debugger);
    }

    /**
     * Reset all information registered in the debugger, including the actual attached Debugger and registered
     * breakpoints.
     */
    public static void resetDebugger() {
        debuggerContext = null;
    }
}