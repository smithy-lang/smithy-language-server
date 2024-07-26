/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.diagnostics;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticCodeDescription;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Range;
import software.amazon.smithy.lsp.document.DocumentVersion;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.lsp.protocol.LspAdapter;

/**
 * Utility class for creating different kinds of file diagnostics, that aren't
 * necessarily connected to model validation events.
 */
public final class SmithyDiagnostics {
    public static final String UPDATE_VERSION = "migrating-idl-1-to-2";
    public static final String DEFINE_VERSION = "define-idl-version";
    public static final String DETACHED_FILE = "detached-file";

    private static final DiagnosticCodeDescription UPDATE_VERSION_DESCRIPTION =
            new DiagnosticCodeDescription("https://smithy.io/2.0/guides/migrating-idl-1-to-2.html");

    private SmithyDiagnostics() {
    }

    /**
     * Creates a diagnostic for when a $version control statement hasn't been defined,
     * or when it has been defined for IDL 1.0.
     *
     * @param smithyFile The Smithy file to get a version diagnostic for
     * @return The version diagnostic associated with the Smithy file, or null
     *  if one doesn't exist
     */
    public static Diagnostic versionDiagnostic(SmithyFile smithyFile) {
        if (smithyFile.documentVersion().isPresent()) {
            DocumentVersion documentVersion = smithyFile.documentVersion().get();
            if (!documentVersion.version().startsWith("2")) {
                Diagnostic diagnostic = createDiagnostic(
                        documentVersion.range(), "You can upgrade to idl version 2.", UPDATE_VERSION);
                diagnostic.setCodeDescription(UPDATE_VERSION_DESCRIPTION);
                return diagnostic;
            }
        } else if (smithyFile.document() != null) {
            int end = smithyFile.document().lineEnd(0);
            Range range = LspAdapter.lineSpan(0, 0, end);
            return createDiagnostic(range, "You should define a version for your Smithy file", DEFINE_VERSION);
        }
        return null;
    }

    /**
     * Creates a diagnostic for when a Smithy file is not connected to a
     * Smithy project via smithy-build.json or other build file.
     *
     * @param smithyFile The Smithy file to get a detached diagnostic for
     * @return The detached diagnostic associated with the Smithy file
     */
    public static Diagnostic detachedDiagnostic(SmithyFile smithyFile) {
        Range range;
        if (smithyFile.document() == null) {
            range = LspAdapter.origin();
        } else {
            int end = smithyFile.document().lineEnd(0);
            range = LspAdapter.lineSpan(0, 0, end);
        }

        return createDiagnostic(range, "This file isn't attached to a project", DETACHED_FILE);
    }

    private static Diagnostic createDiagnostic(Range range, String title, String code) {
        return new Diagnostic(range, title, DiagnosticSeverity.Warning, "smithy-language-server", code);
    }
}
