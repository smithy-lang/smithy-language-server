/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.handler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.Registration;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.build.model.SmithyBuildConfig;
import software.amazon.smithy.lsp.TestWorkspace;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectLoader;
import software.amazon.smithy.lsp.project.ProjectManager;
import software.amazon.smithy.utils.ListUtils;

public class FileWatcherRegistrationHandlerTest {
    @Test
    public void createsCorrectRegistrations() {
        TestWorkspace workspace = TestWorkspace.builder()
                .withSourceDir(new TestWorkspace.Dir()
                        .path("foo")
                        .withSourceDir(new TestWorkspace.Dir()
                                .path("bar")
                                .withSourceFile("bar.smithy", "")
                                .withSourceFile("baz.smithy", ""))
                        .withSourceFile("baz.smithy", ""))
                .withSourceDir(new TestWorkspace.Dir()
                        .path("other")
                        .withSourceFile("other.smithy", ""))
                .withSourceFile("abc.smithy", "")
                .withConfig(SmithyBuildConfig.builder()
                        .version("1")
                        .sources(ListUtils.of("foo", "other/", "abc.smithy"))
                        .build())
                .build();

        Project project = ProjectLoader.load(workspace.getRoot(), new ProjectManager(), new HashSet<>()).unwrap();
        List<String> watcherPatterns = FileWatcherRegistrationHandler.getSmithyFileWatcherRegistrations(project)
                .stream()
                .map(Registration::getRegisterOptions)
                .map(o -> (DidChangeWatchedFilesRegistrationOptions) o)
                .flatMap(options -> options.getWatchers().stream())
                .map(watcher -> watcher.getGlobPattern().getLeft())
                .collect(Collectors.toList());

        assertThat(watcherPatterns, containsInAnyOrder(
                endsWith("foo/**/*.{smithy,json}"),
                endsWith("other/**/*.{smithy,json}"),
                endsWith("abc.smithy")));
    }
}
