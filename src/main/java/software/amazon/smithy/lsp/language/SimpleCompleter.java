/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import software.amazon.smithy.lsp.project.IdlFile;
import software.amazon.smithy.lsp.util.StreamUtils;

/**
 * Maps simple {@link CompletionCandidates} to {@link CompletionItem}s.
 *
 * @param context The context for creating completions.
 * @param mapper The mapper used to map candidates to completion items.
 *               Defaults to {@link Mapper}
 *
 * @see ShapeCompleter for what maps {@link CompletionCandidates.Shapes}.
 */
record SimpleCompleter(CompleterContext context, Mapper mapper) {
    SimpleCompleter(CompleterContext context) {
        this(context, new Mapper(context));
    }

    List<CompletionItem> getCompletionItems(CompletionCandidates candidates) {
        Matcher matcher;
        if (context.exclude().isEmpty()) {
            matcher = new DefaultMatcher(context.matchToken());
        } else {
            matcher = new ExcludingMatcher(context.matchToken(), context.exclude());
        }

        return getCompletionItems(candidates, matcher);
    }

    private List<CompletionItem> getCompletionItems(CompletionCandidates candidates, Matcher matcher) {
        return switch (candidates) {
            case CompletionCandidates.Constant(var value)
                    when !value.isEmpty() && matcher.testConstant(value) -> List.of(mapper.constant(value));

            case CompletionCandidates.Literals(var literals) -> literals.stream()
                    .filter(matcher::testLiteral)
                    .map(mapper::literal)
                    .toList();

            case CompletionCandidates.Labeled(var labeled) -> labeled.entrySet().stream()
                    .filter(matcher::testLabeled)
                    .map(mapper::labeled)
                    .toList();

            case CompletionCandidates.Members(var members) -> members.entrySet().stream()
                    .filter(matcher::testMember)
                    .map(mapper::member)
                    .toList();

            case CompletionCandidates.ElidedMembers(var memberNames) -> memberNames.stream()
                    .filter(matcher::testElided)
                    .map(mapper::elided)
                    .toList();

            case CompletionCandidates.Custom custom -> getCompletionItems(customCandidates(custom), matcher);

            case CompletionCandidates.And(var one, var two) -> {
                List<CompletionItem> oneItems = getCompletionItems(one, matcher);
                List<CompletionItem> twoItems = getCompletionItems(two, matcher);
                List<CompletionItem> completionItems = new ArrayList<>(oneItems.size() + twoItems.size());
                completionItems.addAll(oneItems);
                completionItems.addAll(twoItems);
                yield completionItems;
            }

            default -> List.of();
        };
    }

    private CompletionCandidates customCandidates(CompletionCandidates.Custom custom) {
        return switch (custom) {
            case NAMESPACE_FILTER -> new CompletionCandidates.Labeled(Stream.concat(Stream.of("*"), streamNamespaces())
                    .collect(StreamUtils.toWrappedMap()));

            case VALIDATOR_NAME -> CompletionCandidates.VALIDATOR_NAMES;

            case PROJECT_NAMESPACES -> new CompletionCandidates.Literals(streamNamespaces().toList());
        };
    }

    private Stream<String> streamNamespaces() {
        return context().project().getAllSmithyFiles().stream()
                .map(smithyFile -> switch (smithyFile) {
                    case IdlFile idlFile -> idlFile.getParse().namespace().namespace();
                    default -> "";
                })
                .filter(namespace -> !namespace.isEmpty());
    }

    /**
     * Matches different kinds of completion candidates against the text of
     * whatever triggered the completion, used to filter out candidates.
     *
     * @apiNote LSP has support for client-side matching/filtering, but only when
     * the completion items don't have text edits. We use text edits to have more
     * control over the range the completion text will occupy, so we need to do
     * matching/filtering server-side.
     *
     * @see <a href="https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_completion">LSP Completion Docs</a>
     */
    private sealed interface Matcher {
        String matchToken();

        default boolean testConstant(String constant) {
            return test(constant);
        }

        default boolean testLiteral(String literal) {
            return test(literal);
        }

        default boolean testLabeled(Map.Entry<String, String> labeled) {
            return test(labeled.getKey()) || test(labeled.getValue());
        }

        default boolean testMember(Map.Entry<String, CompletionCandidates.Constant> member) {
            return test(member.getKey());
        }

        default boolean testElided(String memberName) {
            return test(memberName) || test("$" + memberName);
        }

        default boolean test(String s) {
            return s.toLowerCase().startsWith(matchToken());
        }
    }

    private record DefaultMatcher(String matchToken) implements Matcher {}

    private record ExcludingMatcher(String matchToken, Set<String> exclude) implements Matcher {
        @Override
        public boolean testElided(String memberName) {
            // Exclusion set doesn't contain member names with leading '$', so we don't
            // want to delegate to the regular `test` method
            return !exclude.contains(memberName)
                   && (Matcher.super.test(memberName) || Matcher.super.test("$" + memberName));
        }

        @Override
        public boolean test(String s) {
            return !exclude.contains(s) && Matcher.super.test(s);
        }
    }

    /**
     * Maps different kinds of completion candidates to {@link CompletionItem}s.
     */
    static class Mapper {
        private final Range insertRange;
        private final CompletionItemKind literalKind;

        Mapper(CompleterContext context) {
            this.insertRange = context.insertRange();
            this.literalKind = context.literalKind();
        }

        CompletionItem constant(String value) {
            return textEditCompletion(value, CompletionItemKind.Constant);
        }

        CompletionItem literal(String value) {
            return textEditCompletion(value, literalKind);
        }

        CompletionItem labeled(Map.Entry<String, String> entry) {
            return textEditCompletion(entry.getKey(), CompletionItemKind.EnumMember, entry.getValue());
        }

        CompletionItem member(Map.Entry<String, CompletionCandidates.Constant> entry) {
            String value = entry.getKey() + ": " + entry.getValue().value();
            return textEditCompletion(entry.getKey(), CompletionItemKind.Field, value);
        }

        CompletionItem elided(String memberName) {
            return textEditCompletion("$" + memberName, CompletionItemKind.Field);
        }

        protected CompletionItem textEditCompletion(String label, CompletionItemKind kind) {
            return textEditCompletion(label, kind, label);
        }

        protected CompletionItem textEditCompletion(String label, CompletionItemKind kind, String insertText) {
            CompletionItem item = new CompletionItem(label);
            item.setKind(kind);
            TextEdit textEdit = new TextEdit(insertRange, insertText);
            item.setTextEdit(Either.forLeft(textEdit));
            return item;
        }
    }

    static final class BuildFileMapper extends Mapper {
        BuildFileMapper(CompleterContext context) {
            super(context);
        }

        @Override
        CompletionItem member(Map.Entry<String, CompletionCandidates.Constant> entry) {
            String value = "\"" + entry.getKey() + "\": " + entry.getValue().value();
            return textEditCompletion(entry.getKey(), CompletionItemKind.Field, value);
        }
    }
}
