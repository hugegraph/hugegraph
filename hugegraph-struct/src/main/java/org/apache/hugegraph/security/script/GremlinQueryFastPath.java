/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.security.script;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import javax.script.Bindings;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.tinkerpop.gremlin.language.grammar.GremlinAntlrToJava;
import org.apache.tinkerpop.gremlin.language.grammar.GremlinLexer;
import org.apache.tinkerpop.gremlin.language.grammar.GremlinParser;
import org.apache.tinkerpop.gremlin.language.grammar.TraversalRootVisitor;
import org.apache.tinkerpop.gremlin.language.grammar.VariableResolver;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import groovy.lang.GString;

/** Parses complete single traversals without executing Groovy or retaining request bindings. */
final class GremlinQueryFastPath {

    static final Plan UNSUPPORTED = new Plan(null, "", Set.of(), Set.of());
    private static final Set<String> SOURCE_OPTIONS = Set.of("withBulk", "withPath");
    private static final Set<String> TERMINALS = Set.of("next()", "hasNext()", "tryNext()", "toList()",
                                                       "toSet()", "toBulkSet()", "iterate()");
    private static final BaseErrorListener ERRORS = new BaseErrorListener() {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object symbol, int line, int position,
                                String message, RecognitionException cause) {
            throw new ParseCancellationException(message);
        }
    };

    // Plans contain only parse trees. Every invocation gets a new visitor and traversal.
    private final Cache<String, Plan> plans = Caffeine.newBuilder().maximumSize(256)
            .expireAfterAccess(30, TimeUnit.MINUTES).recordStats().build();
    private final LongAdder executions = new LongAdder();

    Plan plan(String source, Bindings bindings) {
        // Preserve Groovy overloads/coercions: null has no runtime type, Bytecode expands arrays,
        // and the grammar's String argument visitor does not perform GString conversion.
        for (Object value : bindings.values()) {
            if (value == null || value.getClass().isArray() || value instanceof GString) {
                return UNSUPPORTED;
            }
        }
        Plan plan = this.plans.get(source, GremlinQueryFastPath::parse);
        // Groovy bindings can shadow imported enum names and classes. Gremlin grammar keywords cannot.
        for (String name : plan.keywords) {
            if (bindings.containsKey(name)) {
                return UNSUPPORTED;
            }
        }
        for (String name : plan.integerParameters) {
            Object value = bindings.get(name);
            if (!(value instanceof Integer || value instanceof Long ||
                  value instanceof Short || value instanceof Byte)) {
                return UNSUPPORTED;
            }
        }
        return plan;
    }

    Traversal.Admin<?, ?> bind(Plan plan, Bindings bindings, long deadline) throws Exception {
        ScriptExecutionBudget.check(deadline);
        Object source = bindings.get("g");
        if (!(source instanceof GraphTraversalSource)) {
            throw new IllegalArgumentException("SCRIPT_TRAVERSAL_SOURCE_DENIED");
        }
        GraphTraversalSource g = (GraphTraversalSource) source;
        int sourceInstructions = g.getBytecode().getSourceInstructions().size();
        GremlinAntlrToJava visitor = new GremlinAntlrToJava(g,
                new VariableResolver.DirectVariableResolver(bindings));
        // Visit only the root, never the query's terminal. The syntax gate excludes nested
        // terminals, transactions and IO; TinkerPop's allowed builders do not iterate the graph.
        // Keep the request's source and strategies, including its authorization proxy.
        Traversal.Admin<?, ?> traversal = new TraversalRootVisitor<>(visitor)
                .visitRootTraversal(plan.query.rootTraversal()).asAdmin();
        try {
            ScriptBytecodePolicy.validate(traversal.getBytecode(), sourceInstructions);
            ScriptExecutionBudget.check(deadline);
        } catch (RuntimeException | Error failure) {
            try {
                traversal.close();
            } catch (Exception closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            if (failure instanceof IllegalArgumentException &&
                "SCRIPT_BYTECODE_LIMIT".equals(failure.getMessage())) {
                // A valid Groovy query can exceed the native Bytecode structural budget.
                // No strategy or step has executed, so the caller may use Groovy.
                return null;
            }
            throw failure;
        }
        return traversal;
    }

    Object execute(Plan plan, Traversal.Admin<?, ?> traversal, long deadline) throws Exception {
        this.executions.increment();
        if (plan.terminal.isEmpty()) {
            return ScriptResults.prepare(traversal, deadline);
        }
        // Terminal results are checked after execution, as on the Groovy route. Inserting a guard step
        // before execution would change traversal semantics (e.g. profile() must be the last step).
        // No fallback is allowed once execution starts, including on an execution failure.
        Object value;
        try (Traversal.Admin<?, ?> result = traversal) {
            ScriptExecutionBudget.check(deadline);
            value = switch (plan.terminal) {
                case "next()" -> result.next();
                case "hasNext()" -> result.hasNext();
                case "tryNext()" -> result.tryNext();
                case "toList()" -> result.toList();
                case "toSet()" -> result.toSet();
                case "toBulkSet()" -> result.toBulkSet();
                case "iterate()" -> result.iterate();
                default -> throw new IllegalStateException("Unsupported terminal");
            };
        }
        // next() can itself return a traversal. It needs the same lazy guard as every Groovy result.
        return ScriptResults.prepare(value, deadline);
    }

    private static Plan parse(String source) {
        GremlinParser.QueryListContext list;
        try {
            GremlinLexer lexer = new GremlinLexer(CharStreams.fromString(source)) {
                @Override
                public void skip() {
                    // Gremlin 3.8's WS rule also matches literal '^' and 'L'. Never silently discard them.
                    String skipped = this.getText();
                    if (!skipped.startsWith("//") && !skipped.chars().allMatch(Character::isWhitespace)) {
                        throw new ParseCancellationException("Non-whitespace skipped by Gremlin lexer");
                    }
                    super.skip();
                }
            };
            lexer.removeErrorListeners();
            lexer.addErrorListener(ERRORS);
            GremlinParser parser = new GremlinParser(new CommonTokenStream(lexer));
            parser.removeErrorListeners();
            parser.addErrorListener(ERRORS);
            list = parser.queryList();
        } catch (ParseCancellationException | StackOverflowError unsupported) {
            return UNSUPPORTED;
        }
        if (list.query().size() != 1) {
            return UNSUPPORTED;
        }
        GremlinParser.QueryContext query = list.query(0);
        if (query.rootTraversal() == null) {
            return UNSUPPORTED;
        }
        String terminal = query.traversalTerminalMethod() == null ? "" :
                          query.traversalTerminalMethod().getText();
        if (!terminal.isEmpty() && !TERMINALS.contains(terminal)) {
            return UNSUPPORTED;
        }
        Deque<ParseTree> pending = new ArrayDeque<>();
        pending.push(query);
        Set<String> keywords = new HashSet<>();
        Set<String> integerParameters = new HashSet<>();
        int nodes = 0;
        while (!pending.isEmpty()) {
            ParseTree node = pending.pop();
            if (++nodes > 16384) {
                return UNSUPPORTED;
            }
            // Only syntax with matching Groovy semantics may take this route. Leave language-specific
            // literals (e.g. unsuffixed decimal Double vs Groovy BigDecimal) to the existing compiler.
            if (node instanceof GremlinParser.NullLiteralContext ||
                node instanceof GremlinParser.FloatLiteralContext ||
                node instanceof GremlinParser.GenericRangeLiteralContext ||
                node instanceof GremlinParser.GenericSetLiteralContext ||
                node instanceof GremlinParser.DateLiteralContext ||
                node instanceof GremlinParser.UuidLiteralContext ||
                node instanceof GremlinParser.TerminatedTraversalContext ||
                node instanceof GremlinParser.TraversalCardinalityContext ||
                node instanceof GremlinParser.TraversalOperatorContext ||
                node instanceof GremlinParser.TraversalGTypeContext ||
                node instanceof GremlinParser.TraversalDirectionShortContext) {
                return UNSUPPORTED;
            }
            if (node instanceof GremlinParser.IntegerLiteralContext) {
                if (!node.getText().matches("[+-]?(0|[1-9][0-9]*)[lL]?")) {
                    return UNSUPPORTED;
                }
                if (!(node.getParent() instanceof GremlinParser.NumericLiteralContext)) {
                    try {
                        // Upstream's typed argument visitors narrow with intValue()/longValue().
                        // Preserve Groovy overload resolution for large integers and explicit longs.
                        Integer.parseInt(node.getText());
                    } catch (NumberFormatException incompatible) {
                        return UNSUPPORTED;
                    }
                }
            }
            if (node instanceof GremlinParser.IntegerArgumentContext) {
                GremlinParser.VariableContext variable = ((GremlinParser.IntegerArgumentContext) node).variable();
                if (variable != null) {
                    integerParameters.add(variable.getText());
                }
            }
            if (node instanceof GremlinParser.StringLiteralContext ||
                node instanceof GremlinParser.StringNullableLiteralContext) {
                String literal = node.getText();
                if ("null".equals(literal) || literal.contains("\\") ||
                    (literal.startsWith("\"") && literal.contains("$"))) {
                    return UNSUPPORTED;
                }
            }
            if ((node instanceof GremlinParser.TraversalMergeContext ||
                 node instanceof GremlinParser.TraversalPickContext ||
                 node instanceof GremlinParser.TraversalDTContext ||
                 node instanceof GremlinParser.TraversalColumnContext ||
                 node instanceof GremlinParser.TraversalPopContext) && node.getChildCount() == 1) {
                return UNSUPPORTED;
            }
            if (node instanceof GremlinParser.MapKeyContext &&
                (node.getChild(0) instanceof GremlinParser.TraversalTLongContext ||
                 node.getChild(0) instanceof GremlinParser.TraversalDirectionLongContext)) {
                return UNSUPPORTED;
            }
            if (node instanceof TerminalNode) {
                TerminalNode token = (TerminalNode) node;
                String name = token.getText();
                if (token.getSymbol().getType() != GremlinLexer.Identifier &&
                    !"g".equals(name) && name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    keywords.add(name);
                }
            }
            if (node instanceof GremlinParser.TraversalSourceSelfMethodContext) {
                String name = ((ParserRuleContext) node).getStart().getText();
                // Groovy supports richer source state than the Bytecode data boundary (e.g. enums
                // and traversals). Keep that whole API on its existing path instead of duplicating it.
                if ("withSack".equals(name) || "withSideEffect".equals(name)) {
                    return UNSUPPORTED;
                }
                if (!SOURCE_OPTIONS.contains(name)) {
                    throw new IllegalArgumentException("SCRIPT_BYTECODE_SOURCE_OPTION_DENIED");
                }
            } else if (node instanceof GremlinParser.TraversalMethodContext ||
                       node instanceof GremlinParser.TraversalSourceSpawnMethodContext) {
                String name = ((ParserRuleContext) node).getStart().getText();
                if (!ScriptMethodPolicy.TRAVERSAL_STEPS.contains(name) && !"with".equals(name)) {
                    throw new IllegalArgumentException("SCRIPT_BYTECODE_STEP_DENIED");
                }
            }
            for (int i = node.getChildCount() - 1; i >= 0; i--) {
                pending.push(node.getChild(i));
            }
        }
        return new Plan(query, terminal, Set.copyOf(keywords), Set.copyOf(integerParameters));
    }

    Map<String, Long> metrics() {
        return Map.of("gremlinExecutions", this.executions.sum(),
                      "gremlinParseHits", this.plans.stats().hitCount(),
                      "gremlinParseMisses", this.plans.stats().missCount(),
                      "gremlinParseEntries", this.plans.estimatedSize());
    }

    void close() {
        this.plans.invalidateAll();
        this.plans.cleanUp();
    }

    static final class Plan {
        private final GremlinParser.QueryContext query;
        private final String terminal;
        private final Set<String> keywords;
        private final Set<String> integerParameters;

        private Plan(GremlinParser.QueryContext query, String terminal, Set<String> keywords,
                     Set<String> integerParameters) {
            this.query = query;
            this.terminal = terminal;
            this.keywords = keywords;
            this.integerParameters = integerParameters;
        }
    }
}
