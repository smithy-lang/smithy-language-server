/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.syntax.Syntax;

public record InlayHintHandler(Document document,
                               List<Syntax.Statement> statements,
                               Range hintRange) {

    private static final String OPERATION_TYPE = "operation";
    private static final String INPUT_TYPE = "input";
    private static final String OUTPUT_TYPE = "output";
    private static final String DEFAULT_INPUT_SUFFIX = "Input";
    private static final String DEFAULT_OUTPUT_SUFFIX = "Output";
    private static final String OPERATION_INPUT_SUFFIX = "operationInputSuffix";
    private static final String OPERATION_OUTPUT_SUFFIX = "operationOutputSuffix";

    /**
     * Main public handle function in the handler class.
     *
     * @return A list of Inlay hints
     */
    public List<InlayHint> handle() {
        return processInlayHints();
    }

    private IOSuffix getIOSuffix(ListIterator<Syntax.Statement> iterator) {
        // Default value for IO Suffix
        String inputSuffix = DEFAULT_INPUT_SUFFIX;
        String outputSuffix = DEFAULT_OUTPUT_SUFFIX;

        while (iterator.hasNext()) {
            var statement = iterator.next();
            // Pattern match used for the following two statement to cast them to ideal Statement or Node type.
            if (statement instanceof Syntax.Statement.Control control) {
                if (control.value() instanceof Syntax.Node.Str str) {
                    String key = control.key().stringValue();
                    String suffix = str.stringValue();
                    if (key.equals(OPERATION_INPUT_SUFFIX)) {
                        inputSuffix = suffix;
                    } else if (key.equals(OPERATION_OUTPUT_SUFFIX)) {
                        outputSuffix = suffix;
                    }
                }
            } else if (statement instanceof Syntax.Statement.ShapeDef) {
                // Customized suffix can only appear at the head of file. Once hit the shapedef statement, we can break.
                iterator.previous();
                break;
            }
        }
        return new IOSuffix(inputSuffix, outputSuffix);
    }

    private boolean coveredByRange(Syntax.Statement statement, int rangeStart, int rangeEnd) {
        // Check if the statement is totally or partially covered by range.
        return statement.end() >= rangeStart && statement.start() <= rangeEnd;
    }

    private List<InlayHint> processInlayHints() {
        List<InlayHint> inlayHints = new ArrayList<>();
        ListIterator<Syntax.Statement> iterator = statements.listIterator();
        IOSuffix ioSuffix = getIOSuffix(iterator);
        // Convert the window range into document character index.
        int rangeStartIndex = document.indexOfPosition(hintRange.getStart());
        int rangeEndIndex = document.indexOfPosition(hintRange.getEnd());
        String lastOperationName = "";
        while (iterator.hasNext()) {
            var statement = iterator.next();
            if (statement instanceof Syntax.Statement.ShapeDef shapeDef
                    && shapeDef.shapeType().stringValue().equals(OPERATION_TYPE)) {
                lastOperationName = shapeDef.shapeName().stringValue();
            }
            if (statement instanceof Syntax.Statement.InlineMemberDef inlineMemberDef) {
                if (!coveredByRange(statement, rangeStartIndex, rangeEndIndex)) {
                    continue;
                }

                String inlayHintLabel = switch (inlineMemberDef.name().stringValue()) {
                    case INPUT_TYPE -> lastOperationName + ioSuffix.inputSuffix();
                    case OUTPUT_TYPE -> lastOperationName + ioSuffix.outputSuffix();
                    default -> null;
                };

                if (inlayHintLabel == null) {
                    continue;
                }

                Position position = document.positionAtIndex(inlineMemberDef.end());
                InlayHint inlayHint = new InlayHint(position, Either.forLeft(inlayHintLabel));
                inlayHints.add(inlayHint);
            }
        }
        return inlayHints;
    }

    private record IOSuffix(String inputSuffix, String outputSuffix) {
    }
}
