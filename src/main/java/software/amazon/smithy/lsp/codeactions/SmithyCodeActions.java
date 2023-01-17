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

package software.amazon.smithy.lsp.codeactions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Diagnostic;
import software.amazon.smithy.lsp.diagnostics.VersionDiagnostics;

public final class SmithyCodeActions {
    public static final String SMITHY_UPDATE_VERSION = "smithyUpdateVersion";
    public static final String SMITHY_DEFINE_VERSION = "smithyDefineVersion";

    private SmithyCodeActions() {

    }

    public static List<String> all() {
        return Arrays.asList(SMITHY_UPDATE_VERSION, SMITHY_DEFINE_VERSION);
    }

    /**
     * Given the `CodeActionParams` argument, compute a list of {@link CodeAction} that
     * are available related to the `$version` statement.
     *
     * Actions include:
     * * defining the version
     * * updating the version
     *
     * This particular implementation relies on specific {@link Diagnostic} to be available through the
     * {@link CodeActionParams}.
     * @param params {@link CodeActionParams} coming from
     * {@link org.eclipse.lsp4j.services.TextDocumentService#codeAction(CodeActionParams)}
     * @return a list of {@link CodeAction} that can be applied
     */
    public static List<CodeAction> versionCodeActions(CodeActionParams params) {
        ArrayList<CodeAction> actions = new ArrayList<>();

        String fileUri = params.getTextDocument().getUri();
        boolean defineVersion = params.getContext().getDiagnostics().stream()
                .anyMatch(diagnosticCodePredicate(VersionDiagnostics.SMITHY_DEFINE_VERSION));
        if (defineVersion) {
            actions.add(DefineVersionCodeAction.build(fileUri));
        }
        Optional<Diagnostic> updateVersionDiagnostic = params.getContext().getDiagnostics().stream()
                .filter(diagnosticCodePredicate(VersionDiagnostics.SMITHY_UPDATE_VERSION)).findFirst();
        if (updateVersionDiagnostic.isPresent()) {
            actions.add(
                UpdateVersionCodeAction.build(fileUri, updateVersionDiagnostic.get().getRange())
            );
        }

        return actions;
    }

    private static Predicate<Diagnostic> diagnosticCodePredicate(String code) {
        return d ->
                d.getCode() != null
                        && d.getCode().isLeft()
                        && d.getCode().getLeft().equals(code);
    }
}
