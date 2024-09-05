/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.handler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.HashSet;
import java.util.List;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.Registration;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.build.model.SmithyBuildConfig;
import software.amazon.smithy.lsp.TestWorkspace;
import software.amazon.smithy.lsp.UtilMatchers;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectLoader;
import software.amazon.smithy.lsp.project.ProjectManager;
import software.amazon.smithy.utils.ListUtils;

public class FileWatcherRegistrationHandlerTest {
    @Test
    public void createsCorrectRegistrations() {
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
        List<PathMatcher> matchers = FileWatcherRegistrationHandler.getSmithyFileWatcherRegistrations(List.of(project))
                .stream()
                .map(Registration::getRegisterOptions)
                .map(o -> (DidChangeWatchedFilesRegistrationOptions) o)
                .flatMap(options -> options.getWatchers().stream())
                .map(watcher -> watcher.getGlobPattern().getLeft())
                // The watcher glob patterns will look different between windows/unix, so turning
                // them into path matchers lets us do platform-agnostic assertions.
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .toList();

        assertThat(matchers, hasItem(UtilMatchers.canMatchPath(workspace.getRoot().resolve("foo/abc.smithy"))));
        assertThat(matchers, hasItem(UtilMatchers.canMatchPath(workspace.getRoot().resolve("foo/foo/abc/def.smithy"))));
        assertThat(matchers, hasItem(UtilMatchers.canMatchPath(workspace.getRoot().resolve("other/abc.smithy"))));
        assertThat(matchers, hasItem(UtilMatchers.canMatchPath(workspace.getRoot().resolve("other/foo/abc.smithy"))));
        assertThat(matchers, hasItem(UtilMatchers.canMatchPath(workspace.getRoot().resolve("abc.smithy"))));
    }
}
