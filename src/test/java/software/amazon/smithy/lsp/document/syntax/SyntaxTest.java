/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.lsp.document.syntax;

import static org.junit.jupiter.api.Assertions.fail;
import static software.amazon.smithy.lsp.document.DocumentTest.safeString;

import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.document.Document;

public class SyntaxTest {
    @Test
    public void findsPath() {
        String text = safeString("""
                {
                    "foo": "bar"
                }""");
        Document document = Document.of(text);
        Syntax.Node value = Syntax.parseNode(document).value();
        NodePath path = SyntaxQuery.findNodePath(document, value, document.indexOfPosition(1, 4));

        assertPathMatches(path, NodePath.of(
                NodePath.OBJ,
                new NodePath.Key("foo"),
                NodePath.TERMINAL));
    }

    @Test
    public void findsWhenBroken() {
        String text = safeString("""
                {
                    "foo"
                }""");
        Document document = Document.of(text);
        Syntax.Node value = Syntax.parseNode(document).value();
        NodePath path = SyntaxQuery.findNodePath(document, value, document.indexOfPosition(1, 4));

        assertPathMatches(path, NodePath.of(
                NodePath.OBJ,
                new NodePath.Key("foo"),
                NodePath.TERMINAL));
    }

    private static void assertPathMatches(NodePath path, NodePath expected) {
        if (!path.matches(expected)) {
            fail("Expected path to match:\n" + expected.edges().toString() + "\nbut was:\n" + path.edges().toString());
        }
    }
}
