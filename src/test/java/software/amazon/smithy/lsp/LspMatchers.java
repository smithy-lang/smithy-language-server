/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.util.Collection;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import software.amazon.smithy.lsp.document.Document;

/**
 * Hamcrest matchers for LSP4J types.
 */
public final class LspMatchers {
    private LspMatchers() {}

    public static Matcher<CompletionItem> hasLabel(String label) {
        return new CustomTypeSafeMatcher<>("a completion item with the label + `" + label + "`") {
            @Override
            protected boolean matchesSafely(CompletionItem item) {
                return item.getLabel().equals(label);
            }

            @Override
            public void describeMismatchSafely(CompletionItem item, Description description) {
                description.appendText("Expected completion item with label '"
                                       + label + "' but was '" + item.getLabel() + "'");
            }
        };
    }

    public static Matcher<CompletionItem> hasLabelAndEditText(String label, String editText) {
        return new CustomTypeSafeMatcher<>("label " + label + " editText " + editText) {
            @Override
            protected boolean matchesSafely(CompletionItem item) {
                return label.equals(item.getLabel())
                       && editText.trim().equals(item.getTextEdit().getLeft().getNewText().trim());
            }
        };
    }

    public static Matcher<TextEdit> makesEditedDocument(Document document, String expected) {
        return new CustomTypeSafeMatcher<>("makes an edited document " + expected) {
            @Override
            protected boolean matchesSafely(TextEdit item) {
                Document copy = document.copy();
                copy.applyEdit(item.getRange(), item.getNewText());
                return copy.copyText().equals(expected);
            }

            @Override
            public void describeMismatchSafely(TextEdit textEdit, Description description) {
                Document copy = document.copy();
                copy.applyEdit(textEdit.getRange(), textEdit.getNewText());
                String actual = copy.copyText();
                description.appendText(String.format("""
                        expected:
                        '%s'
                        but was:
                        '%s'
                        """, expected, actual));
            }
        };
    }

    public static Matcher<Collection<TextEdit>> togetherMakeEditedDocument(Document document, String expected) {
        return new CustomTypeSafeMatcher<>("make edited document " + expected) {
            @Override
            protected boolean matchesSafely(Collection<TextEdit> item) {
                Document copy = document.copy();
                for (TextEdit edit : item) {
                    copy.applyEdit(edit.getRange(), edit.getNewText());
                }
                return copy.copyText().equals(expected);
            }

            @Override
            public void describeMismatchSafely(Collection<TextEdit> item, Description description) {
                Document copy = document.copy();
                for (TextEdit edit : item) {
                    copy.applyEdit(edit.getRange(), edit.getNewText());
                }
                String actual = copy.copyText();
                description.appendText(String.format("""
                        expected:
                        '%s'
                        but was:
                        '%s'
                        """, expected, actual));
            }
        };
    }

    public static Matcher<Range> hasText(Document document, Matcher<String> expected) {
        return new CustomTypeSafeMatcher<>("text in range " + expected.toString()) {
            @Override
            protected boolean matchesSafely(Range item) {
                String actual = document.copyRange(item);
                return expected.matches(actual);
            }

            @Override
            public void describeMismatchSafely(Range range, Description description) {
                if (document.copyRange(range) == null) {
                    description.appendText("text was null");
                } else {
                    description.appendDescriptionOf(expected)
                            .appendText("was " + document.copyRange(range));
                }
            }
        };
    }

    public static Matcher<Diagnostic> diagnosticWithMessage(Matcher<String> message) {
        return new CustomTypeSafeMatcher<>("has matching message") {
            @Override
            protected boolean matchesSafely(Diagnostic item) {
                return message.matches(item.getMessage());
            }

            @Override
            public void describeMismatchSafely(Diagnostic event, Description description) {
                description.appendDescriptionOf(message).appendText("was " + event.getMessage());
            }
        };
    }
}
