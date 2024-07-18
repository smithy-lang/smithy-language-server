/*
 * Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.lsp.diagnostics;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticCodeDescription;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Range;
import software.amazon.smithy.lsp.document.DocumentVersion;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.lsp.protocol.RangeAdapter;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Diagnostics for when a $version control statement hasn't been defined, or when
 * it has been defined for IDL 1.0.
 */
@SmithyInternalApi
public final class VersionDiagnostics {
    public static final String SMITHY_UPDATE_VERSION = "migrating-idl-1-to-2";
    public static final String SMITHY_DEFINE_VERSION = "define-idl-version";

    private static final DiagnosticCodeDescription SMITHY_UPDATE_VERSION_CODE_DIAGNOSTIC =
            new DiagnosticCodeDescription("https://smithy.io/2.0/guides/migrating-idl-1-to-2.html");

    private VersionDiagnostics() {

    }

    private static Diagnostic build(String title, String code, Range range) {
        return new Diagnostic(
            range,
            title,
            DiagnosticSeverity.Warning,
            "Smithy LSP",
            code
        );
    }

    /**
     * @param smithyFile The Smithy file to check for a version diagnostic
     * @return Whether the given {@code smithyFile} has a version diagnostic
     */
    public static boolean hasVersionDiagnostic(SmithyFile smithyFile) {
        return smithyFile.documentVersion()
                .map(documentVersion -> documentVersion.version().charAt(0) != '2')
                .orElse(true);
    }

    /**
     * @param smithyFile The Smithy file to get a version diagnostic for
     * @return The version diagnostic associated with the Smithy file, or null
     *  if one doesn't exist
     */
    public static Diagnostic forSmithyFile(SmithyFile smithyFile) {
        // TODO: This can be cached
        Diagnostic diagnostic = null;
        if (smithyFile.documentVersion().isPresent()) {
            DocumentVersion documentVersion = smithyFile.documentVersion().get();
            if (!documentVersion.version().toString().startsWith("2")) {
                diagnostic = build(
                        "You can upgrade to version 2.",
                        SMITHY_UPDATE_VERSION,
                        documentVersion.range());
                diagnostic.setCodeDescription(SMITHY_UPDATE_VERSION_CODE_DIAGNOSTIC);
            }
        } else if (smithyFile.document() != null) {
            int end = smithyFile.document().lineEnd(0);
            Range range = RangeAdapter.lineSpan(0, 0, end);
            diagnostic = build(
                    "You should define a version for your Smithy file.",
                    SMITHY_DEFINE_VERSION,
                    range);
        }
        return diagnostic;
    }
}
