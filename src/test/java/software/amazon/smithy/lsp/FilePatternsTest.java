/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.build.model.SmithyBuildConfig;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectTest;
import software.amazon.smithy.utils.ListUtils;

public class FilePatternsTest {
    @Test
    public void createsProjectPathMatchers() {
        TestWorkspace workspace = TestWorkspace.builder()
                .withSourceDir(new TestWorkspace.Dir()
                        .withPath("foo")
                        .withSourceDir(new TestWorkspace.Dir()
                                .withPath("bar")
                                .withSourceFile("bar.smithy", "")
                                .withSourceFile("baz.smithy", ""))
                        .withSourceFile("baz.smithy", ""))
                .withSourceDir(new TestWorkspace.Dir()
                        .withPath("other")
                        .withSourceFile("other.smithy", ""))
                .withSourceFile("abc.smithy", "")
                .withConfig(SmithyBuildConfig.builder()
                        .version("1")
                        .sources(ListUtils.of("foo", "other/", "abc.smithy"))
                        .build())
                .build();

        Project project = ProjectTest.load(workspace.getRoot());
        PathMatcher smithyMatcher = FilePatterns.getSmithyFilesPathMatcher(project);
        PathMatcher buildMatcher = FilePatterns.getProjectBuildFilesPathMatcher(project);

        Path root = project.root();
        assertThat(smithyMatcher, UtilMatchers.canMatchPath(root.resolve("abc.smithy")));
        assertThat(smithyMatcher, UtilMatchers.canMatchPath(root.resolve("foo/bar/baz.smithy")));
        assertThat(smithyMatcher, UtilMatchers.canMatchPath(root.resolve("other/bar.smithy")));
        assertThat(buildMatcher, UtilMatchers.canMatchPath(root.resolve("smithy-build.json")));
        assertThat(buildMatcher, UtilMatchers.canMatchPath(root.resolve(".smithy-project.json")));
    }

    @Test
    public void createsWorkspacePathMatchers() throws IOException {
        Path workspaceRoot = Files.createTempDirectory("test");
        workspaceRoot.toFile().deleteOnExit();

        TestWorkspace fooWorkspace = TestWorkspace.builder()
                .withRoot(workspaceRoot)
                .withPath("foo")
                .build();

        // Set up a project outside the 'foo' root.
        workspaceRoot.resolve("bar").toFile().mkdir();
        workspaceRoot.resolve("bar/smithy-build.json").toFile().createNewFile();

        Project fooProject = ProjectTest.load(fooWorkspace.getRoot());

        PathMatcher fooBuildMatcher = FilePatterns.getProjectBuildFilesPathMatcher(fooProject);
        PathMatcher workspaceBuildMatcher = FilePatterns.getWorkspaceBuildFilesPathMatcher(workspaceRoot);

        assertThat(fooBuildMatcher, UtilMatchers.canMatchPath(workspaceRoot.resolve("foo/smithy-build.json")));
        assertThat(fooBuildMatcher, not(UtilMatchers.canMatchPath(workspaceRoot.resolve("bar/smithy-build.json"))));
        assertThat(workspaceBuildMatcher, UtilMatchers.canMatchPath(workspaceRoot.resolve("foo/smithy-build.json")));
        assertThat(workspaceBuildMatcher, UtilMatchers.canMatchPath(workspaceRoot.resolve("bar/smithy-build.json")));
    }
}
