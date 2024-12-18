/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.util.concurrent.locks.ReentrantLock;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.syntax.Syntax;

public final class IdlFile extends SmithyFile {
    private final ReentrantLock idlParseLock = new ReentrantLock();
    private Syntax.IdlParseResult parseResult;

    IdlFile(String path, Document document, Syntax.IdlParseResult parseResult) {
        super(path, document);
        this.parseResult = parseResult;
    }

    @Override
    public void reparse() {
        Syntax.IdlParseResult parse = Syntax.parseIdl(document());

        idlParseLock.lock();
        try {
            this.parseResult = parse;
        } finally {
            idlParseLock.unlock();
        }
    }

    /**
     * @return The latest computed {@link Syntax.IdlParseResult} of this Smithy file
     * @apiNote Don't call this method over and over. {@link Syntax.IdlParseResult} is
     * immutable so just call this once and use the returned value.
     */
    public Syntax.IdlParseResult getParse() {
        idlParseLock.lock();
        try {
            return parseResult;
        } finally {
            idlParseLock.unlock();
        }
    }
}
