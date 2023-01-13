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

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticCodeDescription;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import software.amazon.smithy.lsp.Utils;

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


    /**
     * Produces a diagnostic for each file which w/o a `$version` control statement or
     * file which have a `$version` control statement, but it is out dated.
     *
     * Before looking into a file, we look into `temporaryContents` to make sure
     * it's not an open buffer currently being modified. If it is, we should use this content
     * rather than what's on disk for this specific file. This avoids showing diagnostic for
     * content that's on disk but different from what's in the buffer.
     *
     * @param f a smithy file to inspect
     * @param temporaryContents a map of file to content (represent opened file that are not saved)
     * @return a list of PublishDiagnosticsParams
     */
    public static List<Diagnostic> createVersionDiagnostics(File f, Map<File, String> temporaryContents) {
        // number of line to read in which we expect the $version statement
        int n = 5;
        String editedContent = temporaryContents.get(f);

        List<Utils.NumberedLine> lines;
        try {
            lines = editedContent == null ? Utils.readFirstNLines(f, n) : Utils.contentFirstNLines(editedContent, n);
        } catch (IOException e) {
            return Collections.emptyList();
        }

        Optional<Utils.NumberedLine> version =
            lines.stream().filter(nl -> nl.getContent().startsWith("$version")).findFirst();
        Stream<Diagnostic> diagStream = version.map(nl -> {
            // version is set, its 1
            if (nl.getContent().contains("\"1\"")) {
                return Stream.of(
                    VersionDiagnostics.updateVersion(
                        new Range(
                            new Position(nl.getLineNumber(), 0),
                            new Position(nl.getLineNumber(), nl.getContent().length())
                        )
                    )
                );
            } else {
                // version is set, it is not 1
                return Stream.<Diagnostic>empty();
            }
        }).orElseGet(() -> {
            // we use the first line to show the diagnostic, as the $version is at the top of the file
            // if 0 is used, only the first _word_ is highlighted by the IDE(vscode). It also means that
            // you can only apply the code action if you position your cursor at the very start of the file.
            Integer firstLineLength = lines.stream()
                .findFirst().map(nl -> nl.getContent().length())
                .orElse(0);
            return Stream.of(// version is not set
                VersionDiagnostics.defineVersion(new Range(new Position(0, 0), new Position(0, firstLineLength)))
            );
        });
        return diagStream.collect(Collectors.toList());
    }
}
