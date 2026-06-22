/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.lsp.project;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.TestWorkspace;
import software.amazon.smithy.lsp.diff.DiffConfig;
import software.amazon.smithy.model.validation.ValidationEvent;

public class DiffConfigLoadingTest {

    private static final String MODEL = "$version: \"2.0\"\nnamespace example\nstructure Foo {}\n";

    @Test
    public void loadsDiffConfigFromSmithyProjectJson() {
        TestWorkspace workspace = TestWorkspace.singleModel(MODEL);
        writeSmithyProjectJson(workspace.getRoot(), """
                {
                  "diff": {
                    "baseline": { "type": "maven", "coordinate": "com.example:model:1.0.0" },
                    "enabledEvaluators": ["CompatValidator"]
                  }
                }""");

        Project project = ProjectTest.load(workspace.getRoot());

        assertThat(project.diffConfig().isPresent(), is(true));
        DiffConfig diff = project.diffConfig().orElseThrow();
        assertThat(diff.baseline().coordinate(), is("com.example:model:1.0.0"));
        assertThat(diff.enabledEvaluators(), contains("CompatValidator"));
    }

    @Test
    public void absentDiffBlockMeansFeatureOff() {
        TestWorkspace workspace = TestWorkspace.singleModel(MODEL);

        Project project = ProjectTest.load(workspace.getRoot());

        assertThat(project.diffConfig().isEmpty(), is(true));
    }

    @Test
    public void malformedDiffConfigSurfacesAsConfigDiagnostic() {
        TestWorkspace workspace = TestWorkspace.singleModel(MODEL);
        writeSmithyProjectJson(workspace.getRoot(), """
                { "diff": { "baseline": { "type": "git", "coordinate": "origin/main" } } }""");

        Project project = ProjectTest.load(workspace.getRoot());

        assertThat(project.diffConfig().isEmpty(), is(true));
        assertThat(project.configEvents().stream().map(ValidationEvent::getMessage).toList(),
                hasItem(containsString("Unsupported diff baseline type 'git'")));
    }

    private static void writeSmithyProjectJson(Path root, String content) {
        try {
            Files.writeString(root.resolve(".smithy-project.json"), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
