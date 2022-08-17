/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.lsp.ext;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

public final class Document {

    public static Position blankPosition = new Position(-1, 0);
    public static Position startPosition = new Position(0, 0);

    private Document() {
    }

    /**
     * Identify positions of all parts of document preamble.
     *
     * @param lines lines of the source file
     * @return document preamble
     */
    public static DocumentPreamble detectPreamble(List<String> lines) {
        Range namespaceRange = new Range(blankPosition, blankPosition);
        Range useBlockRange = new Range(blankPosition, blankPosition);
        Set<String> imports = new HashSet<>();
        int firstUseStatementLine = 0;
        String firstUseStatement = "";
        int lastUseStatementLine = 0;
        String lastUseStatement = "";
        boolean collectUseBlock = true;
        int endOfPreamble = 0;
        Optional<String> currentNamespace = Optional.empty();
        Optional<String> idlVersion = Optional.empty();
        Optional<String> operationInputSuffix = Optional.empty();
        Optional<String> operationOutputSuffix = Optional.empty();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("namespace ")) {
                currentNamespace = Optional.of(line.substring(10));
                namespaceRange = getNamespaceRange(i, lines.get(i));
            } else if (line.startsWith("use ") && collectUseBlock) {
                imports.add(getImport(line));
                if (firstUseStatement.isEmpty()) {
                    firstUseStatementLine = i;
                    firstUseStatement = lines.get(i);
                }
                if (i > lastUseStatementLine || lastUseStatement.isEmpty()) {
                    lastUseStatementLine = i;
                    lastUseStatement = lines.get(i);
                }
            } else if (line.startsWith("$version:")) {
                idlVersion = getControlStatementValue(line, "version");
            } else if (line.startsWith("$operationInputSuffix:")) {
                operationInputSuffix = getControlStatementValue(line, "operationInputSuffix");
            } else if (line.startsWith("$operationOutputSuffix:")) {
                operationOutputSuffix = getControlStatementValue(line, "operationOutputSuffix");
            } else if (line.startsWith("//") || line.isEmpty()) {
                // Skip docs, empty lines and the version statement.
            } else {
                // Stop collecting use statements.
                collectUseBlock = false;
                if (endOfPreamble == 0) {
                    endOfPreamble = i - 1;
                }
            }
        }

        if (!firstUseStatement.isEmpty()) {
            useBlockRange = getUseBlockRange(firstUseStatementLine, firstUseStatement, lastUseStatementLine,
                    lastUseStatement);
        }

        boolean blankSeparated = lines.get(endOfPreamble).trim().isEmpty();

        return new DocumentPreamble(currentNamespace, namespaceRange, idlVersion, operationInputSuffix,
                operationOutputSuffix, useBlockRange, imports, blankSeparated);
    }

    private static Optional<String> getControlStatementValue(String line, String key) {
        String quotedValue = line.substring(key.length() + 2);
        return Optional.of(quotedValue.substring(2, quotedValue.length() - 1));
    }

    private static String getImport(String useStatement) {
        return useStatement.trim().split("use ", 2)[1].trim();
    }

    private static Range getUseBlockRange(int startLine, String startLineStatement,
                                          int endLine, String endLineStatement) {
        return new Range(getStartPosition(startLine, startLineStatement), new Position(endLine,
                endLineStatement.length()));
    }

    private static Range getNamespaceRange(int lineNumber, String content) {
        return new Range(getStartPosition(lineNumber, content), new Position(lineNumber, content.length()));
    }

    private static Position getStartPosition(int lineNumber, String content) {
        return new Position(lineNumber, getStartOffset(content));
    }

    private static int getStartOffset(String line) {
        int offset = 0;
        while (line.charAt(offset) == ' ') {
            offset++;
        }
        return offset;
    }

    /**
     * Constructs a text edit that inserts a statement (usually `use ...`) in the correct place
     * in the preamble.
     *
     * @param line     text to insert
     * @param preamble document preamble
     * @return a text edit
     */
    public static TextEdit insertPreambleLine(String line, DocumentPreamble preamble) {
        String trailingNewLine;
        if (!preamble.isBlankSeparated()) {
            trailingNewLine = "\n";
        } else {
            trailingNewLine = "";
        }
        // case 1 - there's no use block at all, so we need to insert the line directly
        // under namespace
        if (preamble.getUseBlockRange().getStart() == Document.blankPosition) {
            // case 1.a - there's no namespace - that means the document is invalid
            // so we'll just insert the line at the beginning of the document
            if (preamble.getNamespaceRange().getStart() == Document.blankPosition) {
                return new TextEdit(new Range(Document.startPosition, Document.startPosition), line + trailingNewLine);
            } else {
                Position namespaceEnd = preamble.getNamespaceRange().getEnd();
                namespaceEnd.setCharacter(namespaceEnd.getCharacter() + 1);
                return new TextEdit(new Range(namespaceEnd, namespaceEnd), "\n" + line + trailingNewLine);
            }
        } else {
            Position useBlockEnd = preamble.getUseBlockRange().getEnd();
            return new TextEdit(new Range(useBlockEnd, useBlockEnd), "\n" + line + trailingNewLine);
        }
    }


}
