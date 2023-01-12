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
     * Build a diagnostic for an outdated Smithy version.
     * @param range range where the $version statement is found
     * @return a Diagnostic with a code that refer to the codeAction to take
     */
    public static Diagnostic updateVersion(Range range) {
        Diagnostic diag = build(
            "You can upgrade to version 2.",
            SMITHY_UPDATE_VERSION,
            range
        );
        diag.setCodeDescription(SMITHY_UPDATE_VERSION_CODE_DIAGNOSTIC);
        return diag;
    }

    /**
     * Build a diagnostic for a missing Smithy version.
     * @param range range where the $version is expected to be
     * @return a Diagnostic with a code that refer to the codeAction to take
     */
    public static Diagnostic defineVersion(Range range) {
        return build(
            "You should define a version for your Smithy file.",
            SMITHY_DEFINE_VERSION,
            range
        );
    }
}
