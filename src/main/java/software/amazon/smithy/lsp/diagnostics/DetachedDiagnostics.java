/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.diagnostics;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Range;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.lsp.protocol.RangeAdapter;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Diagnostics for when a Smithy file is not connected to a Smithy project via
 * smithy-build.json or other build file.
 */
@SmithyInternalApi
public final class DetachedDiagnostics {
    public static final String DETACHED_FILE = "detached-file";

    private DetachedDiagnostics() {
    }

    /**
     * @param smithyFile The Smithy file to get a detached diagnostic for
     * @return The detached diagnostic associated with the Smithy file, or null
     *  if one doesn't exist (this occurs if the file doesn't have a document
     *  associated with it)
     */
    public static Diagnostic forSmithyFile(SmithyFile smithyFile) {
        if (smithyFile.document() == null) {
            return null;
        }
        int end = smithyFile.document().lineEnd(0);
        Range range = RangeAdapter.lineSpan(0, 0, end);
        return new Diagnostic(
                range,
                "This file isn't attached to a project",
                DiagnosticSeverity.Warning,
                "smithy-language-server",
                DETACHED_FILE
        );
    }
}
