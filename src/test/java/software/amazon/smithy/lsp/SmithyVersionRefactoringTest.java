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

package software.amazon.smithy.lsp;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.Test;
import software.amazon.smithy.lsp.codeactions.SmithyCodeActions;
import software.amazon.smithy.lsp.diagnostics.VersionDiagnostics;
import software.amazon.smithy.lsp.ext.Harness;
import software.amazon.smithy.lsp.ext.model.SmithyBuildExtensions;
import software.amazon.smithy.utils.MapUtils;

import static org.junit.Assert.assertEquals;

/**
 * This test suite test the generation of the correct {@link CodeAction} given {@link CodeActionParams}
 * but also the generation of the right version {@link org.eclipse.lsp4j.Diagnostic} given a file with
 * some content in it.
 */
public class SmithyVersionRefactoringTest {

    @Test
    public void noVersionCodeAction() throws Exception {
        String filename = "no-version.smithy";

        Map<String, String> files = MapUtils.ofEntries(
            MapUtils.entry(filename, "namespace test")
        );

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), files)) {
            Range range0 = new Range(new Position(0, 0), new Position(0, 0));

            CodeActionParams params = new CodeActionParams(
                new TextDocumentIdentifier(hs.file(filename).toURI().toString()),
                range0,
                new CodeActionContext(VersionDiagnostics.createVersionDiagnostics(hs.file(filename), Collections.emptyMap()))
            );
            List<CodeAction> result = SmithyCodeActions.versionCodeActions(params);
            assertEquals(1, result.size());
            assertEquals("Define the Smithy version", result.get(0).getTitle());
            // range is (0,0)
            assertEquals(range0, result.get(0).getEdit().getChanges().values().stream().findFirst().get().get(0).getRange());
        }
    }

    @Test
    public void outdatedVersionCodeAction() throws Exception {
        String filename = "old-version.smithy";

        Map<String, String> files = MapUtils.ofEntries(
            MapUtils.entry(filename, "$version: \"1\"\nnamespace test2")
        );

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), files)) {
            Range range0 = new Range(new Position(0, 0), new Position(0, 0));

            Range firstLineRange = new Range(new Position(0, 0), new Position(0, 13));
            CodeActionParams params = new CodeActionParams(
                new TextDocumentIdentifier(hs.file(filename).toURI().toString()),
                range0,
                new CodeActionContext(VersionDiagnostics.createVersionDiagnostics(hs.file(filename), Collections.emptyMap()))
            );
            List<CodeAction> result = SmithyCodeActions.versionCodeActions(params);
            assertEquals(1, result.size());
            assertEquals("Update the Smithy version to 2", result.get(0).getTitle());
            // range is where the diagnostic is found
            assertEquals(firstLineRange, result.get(0).getEdit().getChanges().values().stream().findFirst().get().get(0).getRange());
        }
    }

    @Test
    public void correctVersionCodeAction() throws Exception {
        String filename = "version.smithy";

        Map<String, String> files = MapUtils.ofEntries(
            MapUtils.entry(filename, "$version: \"2\"\nnamespace test2")
        );

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), files)) {
            Range range0 = new Range(new Position(0, 0), new Position(0, 0));

            CodeActionParams params = new CodeActionParams(
                new TextDocumentIdentifier(hs.file(filename).toURI().toString()),
                range0,
                new CodeActionContext(VersionDiagnostics.createVersionDiagnostics(hs.file(filename), Collections.emptyMap()))
            );
            List<CodeAction> result = SmithyCodeActions.versionCodeActions(params);
            assertEquals(0, result.size());
        }
    }
}