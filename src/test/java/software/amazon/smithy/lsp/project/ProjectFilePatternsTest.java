/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.build.model.SmithyBuildConfig;
import software.amazon.smithy.lsp.TestWorkspace;
import software.amazon.smithy.lsp.UtilMatchers;
import software.amazon.smithy.utils.ListUtils;

public class ProjectFilePatternsTest {
    @Test
    public void createsPathMatchers() {
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

        Project project = ProjectLoader.load(workspace.getRoot(), new ProjectManager(), new HashSet<>()).unwrap();
        PathMatcher smithyMatcher = ProjectFilePatterns.getSmithyFilesPathMatcher(project);
        PathMatcher buildMatcher = ProjectFilePatterns.getBuildFilesPathMatcher(project);

        Path root = project.root();
        assertThat(smithyMatcher, UtilMatchers.canMatchPath(root.resolve("abc.smithy")));
        assertThat(smithyMatcher, UtilMatchers.canMatchPath(root.resolve("foo/bar/baz.smithy")));
        assertThat(smithyMatcher, UtilMatchers.canMatchPath(root.resolve("other/bar.smithy")));
        assertThat(buildMatcher, UtilMatchers.canMatchPath(root.resolve("smithy-build.json")));
        assertThat(buildMatcher, UtilMatchers.canMatchPath(root.resolve(".smithy-project.json")));
    }
}
