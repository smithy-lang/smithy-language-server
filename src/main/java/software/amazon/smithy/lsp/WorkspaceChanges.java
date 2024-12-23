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
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectChange;
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

    static WorkspaceChanges computeWorkspaceChanges(List<FileEvent> events, ServerState state) {
        WorkspaceChanges changes = new WorkspaceChanges();

        List<ProjectFileMatcher> projectFileMatchers = new ArrayList<>();
        state.getAllProjects().forEach(project -> {
            if (project.type() == Project.Type.NORMAL) {
                projectFileMatchers.add(createProjectFileMatcher(project.root().toString(), project));
            }
        });

        List<PathMatcher> workspaceBuildFileMatchers = new ArrayList<>(state.workspacePaths().size());
        state.workspacePaths().forEach(workspacePath ->
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
        for (ProjectFileMatcher projectFileMatcher : projectFileMatchers) {
            if (projectFileMatcher.smithyFileMatcher().matches(changedPath)) {
                addSmithyFileChange(event.getType(), changedUri, projectFileMatcher.projectName());
                return;
            } else if (projectFileMatcher.buildFileMatcher().matches(changedPath)) {
                addBuildFileChange(changedUri, projectFileMatcher.projectName());
                return;
            }
        }

        // If no project matched, maybe there's an added project.
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

    private void addSmithyFileChange(FileChangeType changeType, String changedUri, String projectName) {
        ProjectChange projectChange = byProject.computeIfAbsent(
                projectName, ignored -> ProjectChange.empty());

        switch (changeType) {
            case Created -> projectChange.createdSmithyFileUris().add(changedUri);
            case Deleted -> projectChange.deletedSmithyFileUris().add(changedUri);
            default -> {
            }
        }
    }

    private void addBuildFileChange(String changedUri, String projectName) {
        ProjectChange projectChange = byProject.computeIfAbsent(
                projectName, ignored -> ProjectChange.empty());

        projectChange.changedBuildFileUris().add(changedUri);
    }

    private record ProjectFileMatcher(String projectName, PathMatcher smithyFileMatcher, PathMatcher buildFileMatcher) {
    }

    private static ProjectFileMatcher createProjectFileMatcher(String projectName, Project project) {
        PathMatcher smithyFileMatcher = FilePatterns.getSmithyFilesPathMatcher(project);

        PathMatcher buildFileMatcher = FilePatterns.getProjectBuildFilesPathMatcher(project);
        return new ProjectFileMatcher(projectName, smithyFileMatcher, buildFileMatcher);
    }
}

