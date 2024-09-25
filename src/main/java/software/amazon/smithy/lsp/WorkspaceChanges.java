/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectChange;
import software.amazon.smithy.lsp.project.ProjectManager;
import software.amazon.smithy.lsp.protocol.LspAdapter;

/**
 * Aggregates changes to the workspace, including existing project changes and
 * new project additions.
 */
final class WorkspaceChanges {
    // smithy-build.json + .smithy-project.json + exts
    private final Map<String, ProjectChange> byProject = new HashMap<>();
    private final List<Path> newProjectRoots = new ArrayList<>();

    private WorkspaceChanges() {
    }

    static WorkspaceChanges computeWorkspaceChanges(
            List<FileEvent> events,
            ProjectManager projects,
            Set<Path> workspacePaths
    ) {
        WorkspaceChanges changes = new WorkspaceChanges();

        List<ProjectFileMatcher> projectFileMatchers = new ArrayList<>(projects.attachedProjects().size());
        projects.attachedProjects().forEach((projectName, project) ->
                projectFileMatchers.add(createProjectFileMatcher(projectName, project)));

        List<PathMatcher> workspaceBuildFileMatchers = new ArrayList<>(workspacePaths.size());
        workspacePaths.forEach(workspacePath ->
                workspaceBuildFileMatchers.add(FilePatterns.getWorkspaceBuildFilesPathMatcher(workspacePath)));

        for (FileEvent event : events) {
            changes.addEvent(event, projectFileMatchers, workspaceBuildFileMatchers);
        }

        return changes;
    }

    Map<String, ProjectChange> byProject() {
        return byProject;
    }

    List<Path> newProjectRoots() {
        return newProjectRoots;
    }

    private void addEvent(
            FileEvent event,
            List<ProjectFileMatcher> projectFileMatchers,
            List<PathMatcher> workspaceBuildFileMatchers
    ) {
        String changedUri = event.getUri();
        Path changedPath = Path.of(LspAdapter.toPath(changedUri));
        if (changedUri.endsWith(".smithy")) {
            for (ProjectFileMatcher projectFileMatcher : projectFileMatchers) {
                if (projectFileMatcher.smithyFileMatcher().matches(changedPath)) {
                    ProjectChange projectChange = byProject.computeIfAbsent(
                            projectFileMatcher.projectName(), ignored -> ProjectChange.empty());

                    switch (event.getType()) {
                        case Created -> projectChange.createdSmithyFileUris().add(changedUri);
                        case Deleted -> projectChange.deletedSmithyFileUris().add(changedUri);
                        default -> {
                        }
                    }
                    return;
                }
            }
        } else {
            for (ProjectFileMatcher projectFileMatcher : projectFileMatchers) {
                if (projectFileMatcher.buildFileMatcher().matches(changedPath)) {
                    byProject.computeIfAbsent(projectFileMatcher.projectName(), ignored -> ProjectChange.empty())
                            .changedBuildFileUris()
                            .add(changedUri);
                    return;
                }
            }

            // Only check if there's an added project. If there was a project we didn't match before, there's
            // not much we could do at this point anyway.
            if (event.getType() == FileChangeType.Created) {
                for (PathMatcher workspaceBuildFileMatcher : workspaceBuildFileMatchers) {
                    if (workspaceBuildFileMatcher.matches(changedPath)) {
                        Path newProjectRoot = changedPath.getParent();
                        this.newProjectRoots.add(newProjectRoot);
                        return;
                    }
                }
            }
        }
    }

    private record ProjectFileMatcher(String projectName, PathMatcher smithyFileMatcher, PathMatcher buildFileMatcher) {
    }

    private static ProjectFileMatcher createProjectFileMatcher(String projectName, Project project) {
        PathMatcher smithyFileMatcher = FilePatterns.getSmithyFilesPathMatcher(project);

        PathMatcher buildFileMatcher = FilePatterns.getProjectBuildFilesPathMatcher(project);
        return new ProjectFileMatcher(projectName, smithyFileMatcher, buildFileMatcher);
    }
}

