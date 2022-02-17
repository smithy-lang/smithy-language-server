/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

        Range namespace = new Range(blankPosition, blankPosition);
        Range useBlock = new Range(blankPosition, blankPosition);
        Set<String> imports = new HashSet<>();

        // First, we detect the namespace and use block in a very ugly way
        boolean collectUseBlock = true;
        int lineNumber = 0;
        for (String line : lines) {
            if (line.trim().startsWith("namespace ")) {
                if (namespace.getStart() == blankPosition) {
                    namespace.setStart(new Position(lineNumber, 0));
                    namespace.setEnd(new Position(lineNumber, line.length() - 1));
                }
            } else if (line.trim().startsWith("use ")) {
                String i = line.trim().split("use ", 2)[1].trim();
                if (useBlock.getStart() == blankPosition) {
                    imports.add(i);
                    useBlock.setStart(new Position(lineNumber, 0));
                    useBlock.setEnd(new Position(lineNumber, line.length()));
                } else if (collectUseBlock) {
                    imports.add(i);
                    useBlock.setEnd(new Position(lineNumber, line.length()));
                }
            } else if (line.trim().isEmpty()) {
                if (collectUseBlock) {
                    useBlock.setEnd(new Position(lineNumber, line.length()));
                }
            } else {
                if (!line.trim().startsWith("//")) {
                    collectUseBlock = false;
                }
            }

            lineNumber++;
        }

        boolean blankSeparated = false;
        // Next, we reduce the use block to the last use statement, ignoring newlines
        // It's important to do so to make sure we don't multiply newlines
        // unnecessarily.
        if (useBlock.getStart() != blankPosition) {
            while (lines.get(useBlock.getEnd().getLine()).trim().isEmpty()) {
                blankSeparated = true;
                int curLine = useBlock.getEnd().getLine();
                useBlock.getEnd().setLine(curLine - 1);
                useBlock.getEnd().setCharacter(lines.get(curLine - 1).length());
            }
        } else if (namespace.getStart() != blankPosition) {
            int namespaceLine = namespace.getStart().getLine();
            if (namespaceLine < lines.size() - 1) {
                blankSeparated = lines.get(namespaceLine + 1).trim().isEmpty();
            }
        }

        return new DocumentPreamble(namespace, useBlock, imports, blankSeparated);
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
