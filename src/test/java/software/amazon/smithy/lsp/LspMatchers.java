/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import software.amazon.smithy.lsp.document.Document;

public final class LspMatchers {
    private LspMatchers() {}

    public static Matcher<CompletionItem> hasLabel(String label) {
        return new CustomTypeSafeMatcher<CompletionItem>("a completion item with the label + `" + label + "`") {
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

    public static Matcher<TextEdit> makesEditedDocument(Document document, String expected) {
        return new CustomTypeSafeMatcher<TextEdit>("makes an edited document " + expected) {
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
                description.appendText("expected:\n'" + expected + "'\nbut was: \n'" + actual + "'\n");
            }
        };
    }

    public static Matcher<Range> hasText(Document document, Matcher<String> expected) {
        return new CustomTypeSafeMatcher<Range>("text in range") {
            @Override
            protected boolean matchesSafely(Range item) {
                CharSequence borrowed = document.borrowRange(item);
                if (borrowed == null) {
                    return false;
                }
                return expected.matches(borrowed.toString());
            }

            @Override
            public void describeMismatchSafely(Range range, Description description) {
                if (document.borrowRange(range) == null) {
                    description.appendText("text was null");
                } else {
                    description.appendDescriptionOf(expected)
                            .appendText("was " + document.borrowRange(range).toString());
                }
            }
        };
    }

    public static Matcher<Diagnostic> diagnosticWithMessage(Matcher<String> message) {
        return new CustomTypeSafeMatcher<Diagnostic>("has matching message") {
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
