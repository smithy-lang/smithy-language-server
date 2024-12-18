/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.syntax;

import static org.junit.jupiter.api.Assertions.fail;
import static software.amazon.smithy.lsp.document.DocumentTest.safeString;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.document.Document;

public class SyntaxSearchTest {
    @Test
    public void findsNodeCursor() {
        String text = safeString("""
                {
                    "foo": "bar"
                }""");
        Document document = Document.of(text);
        Syntax.Node value = Syntax.parseNode(document).value();
        NodeCursor cursor = NodeCursor.create(value, document.indexOfPosition(1, 4));

        assertCursorMatches(cursor, new NodeCursor(List.of(
                new NodeCursor.Obj(null),
                new NodeCursor.Key("foo", null),
                new NodeCursor.Terminal(null)
        )));
    }

    @Test
    public void findsNodeCursorWhenBroken() {
        String text = safeString("""
                {
                    "foo"
                }""");
        Document document = Document.of(text);
        Syntax.Node value = Syntax.parseNode(document).value();
        NodeCursor cursor = NodeCursor.create(value, document.indexOfPosition(1, 4));

        assertCursorMatches(cursor, new NodeCursor(List.of(
                new NodeCursor.Obj(null),
                new NodeCursor.Key("foo", null),
                new NodeCursor.Terminal(null)
        )));
    }

    private static void assertCursorMatches(NodeCursor actual, NodeCursor expected) {
        if (!actual.toString().equals(expected.toString())) {
            fail("Expected cursor to match:\n" + expected + "\nbut was:\n" + actual);
        }
    }
}
