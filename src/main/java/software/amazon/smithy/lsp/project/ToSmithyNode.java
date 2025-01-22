/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.util.ArrayList;
import java.util.List;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.lsp.syntax.Syntax;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.BooleanNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.NullNode;
import software.amazon.smithy.model.node.NumberNode;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.model.validation.ValidationEvent;

/**
 * Converts a {@link BuildFile#getParse()} into a Smithy {@link Node},
 * and turning any parse errors into {@link ValidationEvent}s.
 *
 * <p>Since the language server's parser is much more lenient than the regular
 * {@link Node} parser, the converted {@link Node} will contain only
 * the parts of the original text that make up valid {@link Node}s.
 */
final class ToSmithyNode {
    private final String path;
    private final Document document;
    private final List<ValidationEvent> events;

    private ToSmithyNode(String path, Document document) {
        this.path = path;
        this.document = document;
        this.events = new ArrayList<>();
    }

    static ValidatedResult<Node> toSmithyNode(BuildFile buildFile) {
        var toSmithyNode = new ToSmithyNode(buildFile.path(), buildFile.document());
        var smithyNode = toSmithyNode.toSmithyNode(buildFile.getParse().value());
        return new ValidatedResult<>(smithyNode, toSmithyNode.events);
    }

    private Node toSmithyNode(Syntax.Node syntaxNode) {
        if (syntaxNode == null) {
            return null;
        }

        SourceLocation sourceLocation = nodeStartSourceLocation(syntaxNode);
        return switch (syntaxNode) {
            case Syntax.Node.Obj obj -> {
                ObjectNode.Builder builder = ObjectNode.builder().sourceLocation(sourceLocation);
                for (Syntax.Node.Kvp kvp : obj.kvps().kvps()) {
                    String keyValue = kvp.key().stringValue();
                    StringNode key = new StringNode(keyValue, nodeStartSourceLocation(kvp.key()));
                    Node value = toSmithyNode(kvp.value());
                    if (value != null) {
                        builder.withMember(key, value);
                    }
                }
                yield builder.build();
            }
            case Syntax.Node.Arr arr -> {
                ArrayNode.Builder builder = ArrayNode.builder().sourceLocation(sourceLocation);
                for (Syntax.Node elem : arr.elements()) {
                    Node elemNode = toSmithyNode(elem);
                    if (elemNode != null) {
                        builder.withValue(elemNode);
                    }
                }
                yield builder.build();
            }
            case Syntax.Ident ident -> {
                String value = ident.stringValue();
                yield switch (value) {
                    case "true", "false" -> new BooleanNode(Boolean.parseBoolean(value), sourceLocation);
                    case "null" -> new NullNode(sourceLocation);
                    default -> null;
                };
            }
            case Syntax.Node.Str str -> new StringNode(str.stringValue(), sourceLocation);
            case Syntax.Node.Num num -> new NumberNode(num.value(), sourceLocation);
            case Syntax.Node.Err err -> {
                events.add(ValidationEvent.builder()
                        .id("ParseError")
                        .severity(Severity.ERROR)
                        .message(err.message())
                        .sourceLocation(sourceLocation)
                        .build());
                yield null;
            }
            default -> null;
        };
    }

    private SourceLocation nodeStartSourceLocation(Syntax.Node node) {
        return LspAdapter.toSourceLocation(path, document.rangeBetween(node.start(), node.end()));
    }
}
