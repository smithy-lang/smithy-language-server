/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.lsp.diagnostics;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;

import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.TestWorkspace;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectAndFile;
import software.amazon.smithy.lsp.project.ProjectFile;
import software.amazon.smithy.lsp.project.ProjectTest;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidationEvent;

public class SmithyDiagnosticsDiffTest {

    @Test
    public void routesDiffEventsToSmithyAndBuildFilesWithDiffSource() {
        TestWorkspace workspace = TestWorkspace.singleModel(
                "$version: \"2.0\"\nnamespace example\nstructure Foo {}\n");
        Project project = ProjectTest.load(workspace.getRoot());

        ProjectFile sourceFile = project.getProjectFile(workspace.getUri("main.smithy"));
        String buildPath = project.getAllBuildFilePaths().iterator().next();
        String buildUri = LspAdapter.toUri(buildPath);
        ProjectFile buildFile = project.getProjectFile(buildUri);

        // Simulate what the on-save diff stores: an event re-anchored to the source file (a
        // namespace-anchored removal) and one re-anchored to the build file (fallback).
        ValidationEvent atSource = diffEvent("example#Gone", sourceFile.path());
        ValidationEvent atBuild = diffEvent("other#Gone", buildPath);
        project.setDiffEvents(List.of(atSource, atBuild));

        List<Diagnostic> sourceDiagnostics = diffDiagnostics(
                new ProjectAndFile(workspace.getUri("main.smithy"), project, sourceFile));
        List<Diagnostic> buildDiagnostics = diffDiagnostics(
                new ProjectAndFile(buildUri, project, buildFile));

        // Each event lands on exactly the file it was anchored to.
        assertThat(messages(sourceDiagnostics), contains(containsString("example#Gone")));
        assertThat(messages(buildDiagnostics), contains(containsString("other#Gone")));
    }

    @Test
    public void reAnchoredRemovedShapeGetsNeutralRangeNotTopOfFileToken() {
        TestWorkspace workspace = TestWorkspace.singleModel(
                "$version: \"2.0\"\nnamespace example\nstructure Foo {}\n");
        Project project = ProjectTest.load(workspace.getRoot());

        ProjectFile sourceFile = project.getProjectFile(workspace.getUri("main.smithy"));
        // A removed shape re-anchored to the source file at the origin (1,1) keeps its shapeId.
        // The IDL range refiner must NOT "improve" this into the $version token at the top of the
        // file (finding #5); it should be the neutral point range at the origin.
        ValidationEvent removed = diffEvent("example#Gone", sourceFile.path());
        project.setDiffEvents(List.of(removed));

        List<Diagnostic> diagnostics = diffDiagnostics(
                new ProjectAndFile(workspace.getUri("main.smithy"), project, sourceFile));

        System.out.println("diagnostics: " + diagnostics);
        assertThat(diagnostics, contains(hasProperty("range", is(new Range(new Position(0, 0), new Position(0, 1))))));
    }

    private static List<Diagnostic> diffDiagnostics(ProjectAndFile projectAndFile) {
        return SmithyDiagnostics.getFileDiagnostics(projectAndFile, Severity.WARNING)
                .stream()
                .filter(d -> SmithyDiagnostics.DIFF_SOURCE.equals(d.getSource()))
                .collect(Collectors.toList());
    }

    private static List<String> messages(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(Diagnostic::getMessage).collect(Collectors.toList());
    }

    private static ValidationEvent diffEvent(String shapeId, String anchoredPath) {
        return ValidationEvent.builder()
                .id("RemovedShape")
                .severity(Severity.DANGER)
                .message("Removed shape `" + shapeId + "`")
                .shapeId(ShapeId.from(shapeId))
                .sourceLocation(new SourceLocation(anchoredPath, 1, 1))
                .build();
    }
}
