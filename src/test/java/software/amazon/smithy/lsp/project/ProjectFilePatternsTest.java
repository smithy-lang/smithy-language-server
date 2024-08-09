/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.build.model.SmithyBuildConfig;
import software.amazon.smithy.lsp.TestWorkspace;
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
        assertThat(smithyMatcher.matches(root.resolve("abc.smithy")), is(true));
        assertThat(smithyMatcher.matches(root.resolve("foo/bar/baz.smithy")), is(true));
        assertThat(smithyMatcher.matches(root.resolve("other/bar.smithy")), is(true));
        assertThat(buildMatcher.matches(root.resolve("smithy-build.json")), is(true));
        assertThat(buildMatcher.matches(root.resolve(".smithy-project.json")), is(true));
    }
}
