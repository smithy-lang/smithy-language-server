/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.util.List;
import org.eclipse.lsp4j.Range;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.lsp.syntax.Syntax;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.loader.ModelSyntaxException;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.BooleanNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.NullNode;
import software.amazon.smithy.model.node.NumberNode;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;
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

    private ToSmithyNode(String path, Document document) {
        this.path = path;
        this.document = document;
    }

    static ValidatedResult<Node> toSmithyNode(BuildFile buildFile) {
        var toSmithyNode = new ToSmithyNode(buildFile.path(), buildFile.document());

        var smithyNode = toSmithyNode.toSmithyNode(buildFile.getParse().value());
        var events = toSmithyNode.getValidationEvents();

        return new ValidatedResult<>(smithyNode, events);
    }

    private List<ValidationEvent> getValidationEvents() {
        // The language server's parser isn't going to produce the same errors
        // because of its leniency. Reparsing like this does incur a cost, but
        // I think it's ok for now considering we get the added benefit of
        // having the same errors Smithy itself would produce.
        try {
            Node.parseJsonWithComments(document.copyText(), path);
            return List.of();
        } catch (ModelSyntaxException e) {
            return List.of(ValidationEvent.fromSourceException(e));
        }
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
            default -> null;
        };
    }

    private SourceLocation nodeStartSourceLocation(Syntax.Node node) {
        Range range = document.rangeBetween(node.start(), node.end());
        if (range == null) {
            range = LspAdapter.origin();
        }
        return LspAdapter.toSourceLocation(path, range);
    }
}
