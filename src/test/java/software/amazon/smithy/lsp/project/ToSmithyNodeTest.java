/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static software.amazon.smithy.lsp.SmithyMatchers.eventWithId;
import static software.amazon.smithy.lsp.SmithyMatchers.eventWithMessage;
import static software.amazon.smithy.lsp.SmithyMatchers.eventWithSourceLocation;
import static software.amazon.smithy.lsp.UtilMatchers.anOptionalOf;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.smithy.lsp.TextWithPositions;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.NodeType;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.validation.ValidatedResult;

public class ToSmithyNodeTest {
    @ParameterizedTest
    @MethodSource("differentNodeTypesProvider")
    public void convertsDifferentNodeTypes(String text, NodeType expectedNodeType) {
        BuildFile buildFile = BuildFile.create("foo", Document.of(text), BuildFileType.SMITHY_BUILD);
        ValidatedResult<Node> nodeResult = ToSmithyNode.toSmithyNode(buildFile);

        assertThat(nodeResult.getResult().map(Node::getType), anOptionalOf(equalTo(expectedNodeType)));
    }

    private static Stream<Arguments> differentNodeTypesProvider() {
        return Stream.of(
                Arguments.of("null", NodeType.NULL),
                Arguments.of("true", NodeType.BOOLEAN),
                Arguments.of("false", NodeType.BOOLEAN),
                Arguments.of("0", NodeType.NUMBER),
                Arguments.of("\"foo\"", NodeType.STRING),
                Arguments.of("[]", NodeType.ARRAY),
                Arguments.of("{}", NodeType.OBJECT)
        );
    }

    @Test
    public void skipsMissingElements() {
        var text = """
                {
                    "version": ,
                    "imports": [
                        ,
                        "foo"
                    ],
                    "projections": {
                        ,
                        "bar": {}
                    }
                }
                """;
        BuildFile buildFile = BuildFile.create("foo", Document.of(text), BuildFileType.SMITHY_BUILD);
        ValidatedResult<Node> nodeResult = ToSmithyNode.toSmithyNode(buildFile);

        ObjectNode node = nodeResult.getResult().get().expectObjectNode();
        assertThat(node.getStringMap().keySet(), containsInAnyOrder("imports", "projections"));

        List<String> imports = node.expectArrayMember("imports")
                .getElementsAs(elem -> elem.expectStringNode().getValue());
        assertThat(imports, containsInAnyOrder(equalTo("foo")));

        Set<String> projections = node.expectObjectMember("projections")
                .getStringMap()
                .keySet();
        assertThat(projections, containsInAnyOrder("bar"));
    }

    @Test
    public void emitsValidationEventsForParseErrors() {
        var twp = TextWithPositions.from("""
                {
                    "version": %,
                    "imports": []
                }
                """);
        Position eventPosition = twp.positions()[0];
        BuildFile buildFile = BuildFile.create("foo", Document.of(twp.text()), BuildFileType.SMITHY_BUILD);
        ValidatedResult<Node> nodeResult = ToSmithyNode.toSmithyNode(buildFile);

        assertThat(nodeResult.getValidationEvents(), containsInAnyOrder(allOf(
                eventWithId(equalTo("Model")),
                eventWithMessage(containsString("Error parsing JSON")),
                eventWithSourceLocation(equalTo(LspAdapter.toSourceLocation("foo", eventPosition)))
        )));
    }
}
