/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static software.amazon.smithy.lsp.UtilMatchers.canMatchPath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
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
        assertThat(smithyMatcher, canMatchPath(root.resolve("abc.smithy")));
        assertThat(smithyMatcher, canMatchPath(root.resolve("foo/bar/baz.smithy")));
        assertThat(smithyMatcher, canMatchPath(root.resolve("other/bar.smithy")));
        assertThat(buildMatcher, canMatchPath(root.resolve("smithy-build.json")));
        assertThat(buildMatcher, canMatchPath(root.resolve(".smithy-project.json")));
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

        assertThat(fooBuildMatcher, canMatchPath(workspaceRoot.resolve("foo/smithy-build.json")));
        assertThat(fooBuildMatcher, not(canMatchPath(workspaceRoot.resolve("bar/smithy-build.json"))));
        assertThat(workspaceBuildMatcher, canMatchPath(workspaceRoot.resolve("foo/smithy-build.json")));
        assertThat(workspaceBuildMatcher, canMatchPath(workspaceRoot.resolve("bar/smithy-build.json")));
    }

    @Test
    public void smithyFileWatchPatternsMatchCorrectSmithyFiles() {
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
        List<PathMatcher> matchers = FilePatterns.getSmithyFileWatchPathMatchers(project);

        assertThat(matchers, hasItem(canMatchPath(workspace.getRoot().resolve("foo/abc.smithy"))));
        assertThat(matchers, hasItem(canMatchPath(workspace.getRoot().resolve("foo/foo/abc/def.smithy"))));
        assertThat(matchers, hasItem(canMatchPath(workspace.getRoot().resolve("other/abc.smithy"))));
        assertThat(matchers, hasItem(canMatchPath(workspace.getRoot().resolve("other/foo/abc.smithy"))));
        assertThat(matchers, hasItem(canMatchPath(workspace.getRoot().resolve("abc.smithy"))));
    }

    @Test
    public void matchingAnyBuildFile() {
        PathMatcher global = FilePatterns.GLOBAL_BUILD_FILES_MATCHER;

        assertThat(global, canMatchPath(Path.of("/smithy-build.json")));
        assertThat(global, canMatchPath(Path.of("foo/bar/smithy-build.json")));
        assertThat(global, canMatchPath(Path.of("/foo/bar/smithy-build.json")));
        assertThat(global, not(canMatchPath(Path.of("/foo/bar/foo-smithy-build.json"))));
    }
}
