/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.syntax.Syntax;

/**
 * The language server's representation of a Smithy file.
 */
public sealed class SmithyFile implements ProjectFile permits IdlFile {
    private final String path;
    private final Document document;

    SmithyFile(String path, Document document) {
        this.path = path;
        this.document = document;
    }

    static SmithyFile create(String path, Document document) {
        // TODO: Make a better abstraction for loading an arbitrary project file
        if (path.endsWith(".smithy")) {
            Syntax.IdlParseResult parse = Syntax.parseIdl(document);
            return new IdlFile(path, document, parse);
        } else {
            return new SmithyFile(path, document);
        }
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public Document document() {
        return document;
    }

    /**
     * Reparse the underlying {@link #document()}.
     */
    public void reparse() {
        // Don't parse JSON files, at least for now
    }
}
