/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import software.amazon.smithy.lsp.document.Document;

/**
 * A file belonging to a Smithy project that the language server understands
 * and tracks.
 */
public sealed interface ProjectFile permits SmithyFile, BuildFile {
    /**
     * @return The absolute path of the file
     */
    String path();

    /**
     * @return The underlying document of the file
     */
    Document document();

    /**
     * Reparse the underlying document.
     */
    void reparse();
}
