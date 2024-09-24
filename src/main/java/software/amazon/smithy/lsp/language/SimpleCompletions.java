/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import software.amazon.smithy.lsp.document.DocumentId;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.util.StreamUtils;
import software.amazon.smithy.model.Model;

final class SimpleCompletions {
    private final Project project;
    private final Matcher matcher;
    private final Mapper mapper;

    private SimpleCompletions(Project project, Matcher matcher, Mapper mapper) {
        this.project = project;
        this.matcher = matcher;
        this.mapper = mapper;
    }

    List<CompletionItem> getCompletionItems(Candidates candidates) {
        return switch (candidates) {
            case Candidates.Constant(var value)
                    when !value.isEmpty() && matcher.testConstant(value) -> List.of(mapper.constant(value));

            case Candidates.Literals(var literals) -> literals.stream()
                    .filter(matcher::testLiteral)
                    .map(mapper::literal)
                    .toList();

            case Candidates.Labeled(var labeled) -> labeled.entrySet().stream()
                    .filter(matcher::testLabeled)
                    .map(mapper::labeled)
                    .toList();

            case Candidates.Members(var members) -> members.entrySet().stream()
                    .filter(matcher::testMember)
                    .map(mapper::member)
                    .toList();

            case Candidates.ElidedMembers(var memberNames) -> memberNames.stream()
                    .filter(matcher::testElided)
                    .map(mapper::elided)
                    .toList();

            case Candidates.Custom custom
                    // TODO: Need to get rid of this stupid null check
                    when project != null -> getCompletionItems(customCandidates(custom));

            case Candidates.And(var one, var two) -> {
                List<CompletionItem> oneItems = getCompletionItems(one);
                List<CompletionItem> twoItems = getCompletionItems(two);
                List<CompletionItem> completionItems = new ArrayList<>(oneItems.size() + twoItems.size());
                completionItems.addAll(oneItems);
                completionItems.addAll(twoItems);
                yield completionItems;
            }
            default -> List.of();
        };
    }

    private Candidates customCandidates(Candidates.Custom custom) {
        return switch (custom) {
            case NAMESPACE_FILTER -> new Candidates.Labeled(Stream.concat(Stream.of("*"), streamNamespaces())
                    .collect(StreamUtils.toWrappedMap()));

            case VALIDATOR_NAME -> Candidates.VALIDATOR_NAMES;

            case PROJECT_NAMESPACES -> new Candidates.Literals(streamNamespaces().toList());
        };
    }

    private Stream<String> streamNamespaces() {
        return project.smithyFiles().values().stream()
                .map(smithyFile -> smithyFile.namespace().toString())
                .filter(namespace -> !namespace.isEmpty());
    }

    static Builder builder(DocumentId id, Range insertRange) {
        return new Builder(id, insertRange);
    }

    static final class Builder {
        private final DocumentId id;
        private final Range insertRange;
        private Project project = null;
        private Set<String> exclude = null;
        private CompletionItemKind literalKind = CompletionItemKind.Field;

        private Builder(DocumentId id, Range insertRange) {
            this.id = id;
            this.insertRange = insertRange;
        }

        Builder project(Project project) {
            this.project = project;
            return this;
        }

        Builder exclude(Set<String> exclude) {
            this.exclude = exclude;
            return this;
        }

        Builder literalKind(CompletionItemKind literalKind) {
            this.literalKind = literalKind;
            return this;
        }

        SimpleCompletions buildSimpleCompletions() {
            Matcher matcher = getMatcher(id, exclude);
            Mapper mapper = new Mapper(insertRange, literalKind);
            return new SimpleCompletions(project, matcher, mapper);
        }

        ShapeCompletions buildShapeCompletions(IdlPosition idlPosition, Model model) {
            return ShapeCompletions.create(idlPosition, model, getMatchToken(id), insertRange);
        }
    }

    private static Matcher getMatcher(DocumentId id, Set<String> exclude) {
        String matchToken = getMatchToken(id);
        if (exclude == null || exclude.isEmpty()) {
            return new DefaultMatcher(matchToken);
        } else {
            return new ExcludingMatcher(matchToken, exclude);
        }
    }

    private static String getMatchToken(DocumentId id) {
        return id != null
                ? id.copyIdValue().toLowerCase()
                : "";
    }

    private sealed interface Matcher extends Predicate<String> {
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

        default boolean testMember(Map.Entry<String, Candidates.Constant> member) {
            return test(member.getKey());
        }

        default boolean testElided(String memberName) {
            return test(memberName) || test("$" + memberName);
        }

        @Override
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

    private record Mapper(Range insertRange, CompletionItemKind literalKind) {
        CompletionItem constant(String value) {
            return textEditCompletion(value, CompletionItemKind.Constant);
        }

        CompletionItem literal(String value) {
            return textEditCompletion(value, CompletionItemKind.Field);
        }

        CompletionItem labeled(Map.Entry<String, String> entry) {
            return textEditCompletion(entry.getKey(), CompletionItemKind.EnumMember, entry.getValue());
        }

        CompletionItem member(Map.Entry<String, Candidates.Constant> entry) {
            String value = entry.getKey() + ": " + entry.getValue().value();
            return textEditCompletion(entry.getKey(), CompletionItemKind.Field, value);
        }

        CompletionItem elided(String memberName) {
            return textEditCompletion("$" + memberName, CompletionItemKind.Field);
        }

        private CompletionItem textEditCompletion(String label, CompletionItemKind kind) {
            return textEditCompletion(label, kind, label);
        }

        private CompletionItem textEditCompletion(String label, CompletionItemKind kind, String insertText) {
            CompletionItem item = new CompletionItem(label);
            item.setKind(kind);
            TextEdit textEdit = new TextEdit(insertRange, insertText);
            item.setTextEdit(Either.forLeft(textEdit));
            return item;
        }
    }
}
