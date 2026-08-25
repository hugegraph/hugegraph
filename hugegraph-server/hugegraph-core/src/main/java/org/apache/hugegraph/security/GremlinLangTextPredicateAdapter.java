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

package org.apache.hugegraph.security;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.script.Bindings;
import javax.script.ScriptContext;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.hugegraph.traversal.optimize.ConditionP;
import org.apache.tinkerpop.gremlin.jsr223.Customizer;
import org.apache.tinkerpop.gremlin.jsr223.GremlinLangCustomizer;
import org.apache.tinkerpop.gremlin.language.grammar.GremlinLexer;
import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.step.GValue;
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;

import com.github.benmanes.caffeine.cache.Cache;

final class GremlinLangTextPredicateAdapter {

    private static final String TEXT = "Text";
    private static final String CONTAINS = "contains";
    private static final String RESERVED_BINDING_PREFIX =
            "hugegraphTextContainsInternal";

    private final Cache<String, RewritePlan> plans;

    GremlinLangTextPredicateAdapter(Customizer... customizers) {
        this.plans = newPlanCache(customizers);
    }

    AdaptedScript adapt(String script, ScriptContext context) {
        if (!mightContainTextPredicate(script)) {
            return AdaptedScript.identity(script);
        }

        RewritePlan plan = this.plans == null ?
                           parse(script) :
                           this.plans.get(script,
                                          GremlinLangTextPredicateAdapter::parse);
        return plan.materialize(context);
    }

    static void restore(Traversal.Admin<?, ?> traversal) {
        TraversalHelper.applyTraversalRecursively(
                GremlinLangTextPredicateAdapter::restoreCurrentTraversal,
                traversal);
    }

    private static boolean mightContainTextPredicate(String script) {
        return script.contains(TEXT) && script.contains(CONTAINS);
    }

    private static RewritePlan parse(String script) {
        GremlinLexer lexer = new GremlinLexer(CharStreams.fromString(script));
        lexer.removeErrorListeners();
        lexer.addErrorListener(ThrowingErrorListener.INSTANCE);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        tokenStream.fill();

        List<Token> tokens = new ArrayList<>();
        for (Token token : tokenStream.getTokens()) {
            if (token.getType() != Token.EOF) {
                tokens.add(token);
            }
        }

        List<Occurrence> occurrences = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (!isTextContainsPrefix(tokens, i)) {
                continue;
            }
            Occurrence occurrence = match(tokens, i, occurrences.size());
            if (occurrence == null) {
                throw unsupportedTextContains();
            }
            occurrences.add(occurrence);
            i += 5;
        }

        if (occurrences.isEmpty()) {
            return RewritePlan.identity(script);
        }
        rejectReservedIdentifiers(tokens);

        StringBuilder rewritten = new StringBuilder(script.length());
        int cursor = 0;
        for (Occurrence occurrence : occurrences) {
            rewritten.append(script, cursor, occurrence.start());
            rewritten.append(occurrence.internalBinding());
            cursor = occurrence.end();
        }
        rewritten.append(script, cursor, script.length());
        return new RewritePlan(rewritten.toString(), occurrences);
    }

    private static Occurrence match(List<Token> tokens, int index,
                                    int occurrenceIndex) {
        if (index == 0 || index + 6 >= tokens.size()) {
            return null;
        }
        if (tokens.get(index - 1).getType() != GremlinLexer.COMMA ||
            tokens.get(index + 3).getType() != GremlinLexer.LPAREN ||
            tokens.get(index + 5).getType() != GremlinLexer.RPAREN ||
            tokens.get(index + 6).getType() != GremlinLexer.RPAREN) {
            return null;
        }

        Token argument = tokens.get(index + 4);
        if (!isString(argument) && !isIdentifier(argument)) {
            return null;
        }

        int outerLeftParen = matchingLeftParen(tokens, index + 6);
        if (outerLeftParen <= 0 ||
            tokens.get(outerLeftParen - 1).getType() !=
            GremlinLexer.K_HAS) {
            return null;
        }
        int commas = topLevelCommas(tokens, outerLeftParen + 1, index);
        if (commas != 1 && commas != 2) {
            return null;
        }

        String internalBinding = RESERVED_BINDING_PREFIX + occurrenceIndex;
        String literal = isString(argument) ?
                         decodeStringLiteral(argument.getText()) : null;
        String sourceBinding = isIdentifier(argument) ?
                               argument.getText() : null;
        int start = tokens.get(index).getStartIndex();
        int end = tokens.get(index + 5).getStopIndex() + 1;
        return new Occurrence(start, end, internalBinding,
                              literal, sourceBinding);
    }

    private static int matchingLeftParen(List<Token> tokens,
                                         int rightParen) {
        int depth = 0;
        for (int i = rightParen; i >= 0; i--) {
            int type = tokens.get(i).getType();
            if (type == GremlinLexer.RPAREN) {
                depth++;
            } else if (type == GremlinLexer.LPAREN && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int topLevelCommas(List<Token> tokens, int start,
                                      int end) {
        int depth = 0;
        int commas = 0;
        for (int i = start; i < end; i++) {
            int type = tokens.get(i).getType();
            if (type == GremlinLexer.LPAREN) {
                depth++;
            } else if (type == GremlinLexer.RPAREN) {
                depth--;
            } else if (type == GremlinLexer.COMMA && depth == 0) {
                commas++;
            }
        }
        return commas;
    }

    private static boolean isTextContainsPrefix(List<Token> tokens,
                                                int index) {
        return index + 2 < tokens.size() &&
               isIdentifier(tokens.get(index), TEXT) &&
               tokens.get(index + 1).getType() == GremlinLexer.DOT &&
               isIdentifier(tokens.get(index + 2), CONTAINS);
    }

    private static boolean isIdentifier(Token token, String value) {
        return isIdentifier(token) && value.equals(token.getText());
    }

    private static boolean isIdentifier(Token token) {
        return token.getType() == GremlinLexer.Identifier;
    }

    private static boolean isString(Token token) {
        return token.getType() == GremlinLexer.NonEmptyStringLiteral ||
               token.getType() == GremlinLexer.EmptyStringLiteral;
    }

    private static String decodeStringLiteral(String literal) {
        return StringEscapeUtils.unescapeJava(
                literal.substring(1, literal.length() - 1));
    }

    private static void rejectReservedIdentifiers(List<Token> tokens) {
        for (Token token : tokens) {
            if (isIdentifier(token) &&
                token.getText().startsWith(RESERVED_BINDING_PREFIX)) {
                throw new IllegalArgumentException(
                        "Gremlin query uses a reserved HugeGraph binding");
            }
        }
    }

    private static IllegalArgumentException unsupportedTextContains() {
        return new IllegalArgumentException(
                "Text.contains() is only supported as the final argument " +
                "of has(), with one String literal or String binding");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Cache<String, RewritePlan> newPlanCache(
            Customizer[] customizers) {
        for (Customizer customizer : customizers) {
            if (!(customizer instanceof GremlinLangCustomizer)) {
                continue;
            }
            GremlinLangCustomizer gremlinLang =
                    (GremlinLangCustomizer) customizer;
            if (!gremlinLang.isCacheEnabled()) {
                return null;
            }
            return (Cache<String, RewritePlan>)
                   gremlinLang.getCacheMaker().build();
        }
        return null;
    }

    private static void restoreCurrentTraversal(
            Traversal.Admin<?, ?> traversal) {
        for (Object step : traversal.getSteps()) {
            if (!(step instanceof HasContainerHolder)) {
                continue;
            }
            HasContainerHolder<?, ?> holder =
                    (HasContainerHolder<?, ?>) step;
            List<HasContainer> containers =
                    new ArrayList<>(holder.getHasContainers());
            for (HasContainer current : containers) {
                TextContainsMarker marker = marker(current.getPredicate(),
                                                   traversal);
                if (marker == null) {
                    continue;
                }
                holder.removeHasContainer(current);
                holder.addHasContainer(new HasContainer(
                        current.getKey(),
                        ConditionP.textContains(marker.value())));
            }
        }
    }

    private static TextContainsMarker marker(
            P<?> predicate, Traversal.Admin<?, ?> traversal) {
        if (predicate.getBiPredicate() != Compare.eq) {
            return null;
        }
        if (!predicate.isParameterized()) {
            Object value = predicate.getValue();
            return value instanceof TextContainsMarker ?
                   (TextContainsMarker) value : null;
        }

        for (GValue<?> value : predicate.getGValues()) {
            if (!value.isVariable() ||
                !value.getName().startsWith(RESERVED_BINDING_PREFIX) ||
                !(value.get() instanceof TextContainsMarker)) {
                continue;
            }
            TextContainsMarker current = currentMarker(traversal,
                                                       value.getName());
            if (current == null) {
                throw new IllegalStateException(
                        "Missing internal Text.contains() binding");
            }
            traversal.getGValueManager().pinVariable(value.getName());
            return current;
        }
        return null;
    }

    private static TextContainsMarker currentMarker(
            Traversal.Admin<?, ?> traversal, String name) {
        for (GValue<?> value : traversal.getGValueManager().getGValues()) {
            if (value.isVariable() && name.equals(value.getName()) &&
                value.get() instanceof TextContainsMarker) {
                return (TextContainsMarker) value.get();
            }
        }
        return null;
    }

    static final class AdaptedScript {

        private final String script;
        private final Map<String, Object> bindings;

        private AdaptedScript(String script, Map<String, Object> bindings) {
            this.script = script;
            this.bindings = bindings;
        }

        static AdaptedScript identity(String script) {
            return new AdaptedScript(script, Collections.emptyMap());
        }

        String script() {
            return this.script;
        }

        Map<String, Object> bindings() {
            return this.bindings;
        }
    }

    private static final class RewritePlan {

        private final String script;
        private final List<Occurrence> occurrences;

        private RewritePlan(String script, List<Occurrence> occurrences) {
            this.script = script;
            this.occurrences = List.copyOf(occurrences);
        }

        static RewritePlan identity(String script) {
            return new RewritePlan(script, Collections.emptyList());
        }

        AdaptedScript materialize(ScriptContext context) {
            if (this.occurrences.isEmpty()) {
                return AdaptedScript.identity(this.script);
            }
            rejectReservedBindings(context);
            Map<String, Object> bindings = new LinkedHashMap<>();
            for (Occurrence occurrence : this.occurrences) {
                String value = occurrence.resolve(context);
                bindings.put(occurrence.internalBinding(),
                             new TextContainsMarker(value));
            }
            return new AdaptedScript(this.script, bindings);
        }

        private static void rejectReservedBindings(ScriptContext context) {
            rejectReservedBindings(context.getBindings(
                    ScriptContext.ENGINE_SCOPE));
            rejectReservedBindings(context.getBindings(
                    ScriptContext.GLOBAL_SCOPE));
        }

        private static void rejectReservedBindings(Bindings bindings) {
            if (bindings == null) {
                return;
            }
            for (String name : bindings.keySet()) {
                if (name.startsWith(RESERVED_BINDING_PREFIX)) {
                    throw new IllegalArgumentException(
                            "Gremlin request contains a reserved " +
                            "HugeGraph binding");
                }
            }
        }
    }

    private static final class Occurrence {

        private final int start;
        private final int end;
        private final String internalBinding;
        private final String literal;
        private final String sourceBinding;

        private Occurrence(int start, int end, String internalBinding,
                           String literal, String sourceBinding) {
            this.start = start;
            this.end = end;
            this.internalBinding = internalBinding;
            this.literal = literal;
            this.sourceBinding = sourceBinding;
        }

        int start() {
            return this.start;
        }

        int end() {
            return this.end;
        }

        String internalBinding() {
            return this.internalBinding;
        }

        String resolve(ScriptContext context) {
            if (this.sourceBinding == null) {
                return this.literal;
            }
            Object value = context.getAttribute(this.sourceBinding);
            if (!(value instanceof String)) {
                throw new IllegalArgumentException(String.format(
                        "The Text.contains() binding '%s' must be a String",
                        this.sourceBinding));
            }
            return (String) value;
        }
    }

    private static final class TextContainsMarker implements Serializable {

        private static final long serialVersionUID = 1L;

        private final String value;

        private TextContainsMarker(String value) {
            this.value = value;
        }

        String value() {
            return this.value;
        }
    }

    private static final class ThrowingErrorListener
            extends BaseErrorListener {

        private static final ThrowingErrorListener INSTANCE =
                new ThrowingErrorListener();

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer,
                                Object offendingSymbol, int line,
                                int charPositionInLine, String message,
                                RecognitionException exception) {
            throw new IllegalArgumentException(String.format(
                    "Invalid Gremlin token at line %s, character %s: %s",
                    line, charPositionInLine, message), exception);
        }
    }
}
