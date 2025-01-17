/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.util.concurrent.locks.ReentrantLock;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.syntax.Syntax;

/**
 * The language server's representation of a smithy-build.json
 * .smithy-project.json file.
 */
public final class BuildFile implements ProjectFile {
    private final String path;
    private final Document document;
    private final BuildFileType type;
    private final ReentrantLock parseLock = new ReentrantLock();
    private Syntax.NodeParseResult parseResult;

    private BuildFile(
            String path,
            Document document,
            BuildFileType type,
            Syntax.NodeParseResult parseResult
    ) {
        this.path = path;
        this.document = document;
        this.type = type;
        this.parseResult = parseResult;
    }

    static BuildFile create(String path, Document document, BuildFileType type) {
        Syntax.NodeParseResult parseResult = Syntax.parseNode(document);
        return new BuildFile(path, document, type, parseResult);
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public Document document() {
        return document;
    }

    @Override
    public void reparse() {
        Syntax.NodeParseResult updatedParse = Syntax.parseNode(document());

        parseLock.lock();
        try {
            this.parseResult = updatedParse;
        } finally {
            parseLock.unlock();
        }
    }

    public BuildFileType type() {
        return type;
    }

    /**
     * @return The latest computed {@link Syntax.NodeParseResult} of this build file
     * @apiNote Don't call this method over and over. {@link Syntax.NodeParseResult}
     *  is immutable so just call this once and use the returned value.
     */
    public Syntax.NodeParseResult getParse() {
        parseLock.lock();
        try {
            return parseResult;
        } finally {
            parseLock.unlock();
        }
    }
}
