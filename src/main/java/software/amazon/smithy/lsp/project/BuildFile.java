/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import software.amazon.smithy.lsp.document.Document;

/**
 * The language server's representation of a smithy-build.json
 * .smithy-project.json file.
 */
public final class BuildFile implements ProjectFile {
    private final String path;
    private final Document document;

    BuildFile(String path, Document document) {
        this.path = path;
        this.document = document;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public Document document() {
        return document;
    }
}
