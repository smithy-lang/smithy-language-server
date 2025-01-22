/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.WorkspaceFolder;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectAndFile;
import software.amazon.smithy.lsp.project.ProjectChange;
import software.amazon.smithy.lsp.project.ProjectFile;
import software.amazon.smithy.lsp.project.ProjectLoader;
import software.amazon.smithy.lsp.protocol.LspAdapter;

/**
 * Keeps track of the state of the server.
 */
public final class ServerState implements ManagedFiles {
    private static final Logger LOGGER = Logger.getLogger(ServerState.class.getName());

    private final Map<String, Project> projects;
    private final Set<Path> workspacePaths;
    private final Set<String> managedUris;
    private final FileTasks lifecycleTasks;

    /**
     * Create a new, empty server state.
     */
    public ServerState() {
        this.projects = new HashMap<>();
        this.workspacePaths = new HashSet<>();
        this.managedUris = new HashSet<>();
        this.lifecycleTasks = new FileTasks();
    }

    /**
     * @return All projects tracked by the server.
     */
    public Collection<Project> getAllProjects() {
        return projects.values();
    }

    /**
     * @return All files managed by the server, including their projects.
     */
    public Collection<ProjectAndFile> getAllManaged() {
        List<ProjectAndFile> allManaged = new ArrayList<>(managedUris.size());
        for (String uri : managedUris) {
            allManaged.add(findManaged(uri));
        }
        return allManaged;
    }

    /**
     * @return All workspace paths tracked by the server.
     */
    public Set<Path> workspacePaths() {
        return workspacePaths;
    }

    @Override
    public Document getManagedDocument(String uri) {
        if (managedUris.contains(uri)) {
            ProjectAndFile projectAndFile = findProjectAndFile(uri);
            if (projectAndFile != null) {
                return projectAndFile.file().document();
            }
        }

        return null;
    }

    FileTasks lifecycleTasks() {
        return lifecycleTasks;
    }

    Project findProjectByRoot(String root) {
        return projects.get(root);
    }

    ProjectAndFile findProjectAndFile(String uri) {
        for (Project project : projects.values()) {
            ProjectFile projectFile = project.getProjectFile(uri);
            if (projectFile != null) {
                return new ProjectAndFile(uri, project, projectFile);
            }
        }

        LOGGER.warning(() -> "Tried to unknown file: " + uri);

        return null;
    }

    ProjectAndFile findManaged(String uri) {
        if (managedUris.contains(uri)) {
            return findProjectAndFile(uri);
        }
        return null;
    }

    ProjectAndFile open(String uri, String text) {
        managedUris.add(uri);

        ProjectAndFile projectAndFile = findProjectAndFile(uri);
        if (projectAndFile != null) {
            projectAndFile.file().document().applyEdit(null, text);
        } else {
            createDetachedProject(uri, text);
            projectAndFile = findProjectAndFile(uri); // Note: This will always be present
        }

        return projectAndFile;
    }

    void close(String uri) {
        managedUris.remove(uri);

        ProjectAndFile projectAndFile = findProjectAndFile(uri);
        if (projectAndFile != null && projectAndFile.project().type() == Project.Type.DETACHED) {
            // Only cancel tasks for detached projects, since we're dropping the project
            lifecycleTasks.cancelTask(uri);
            projects.remove(uri);
        }
    }

    List<Exception> tryInitProject(Path root) {
        LOGGER.finest("Initializing project at " + root);
        lifecycleTasks.cancelAllTasks();

        String projectName = root.toString();
        try {
            Project updatedProject = ProjectLoader.load(root, this);

            if (updatedProject.type() == Project.Type.EMPTY) {
                removeProjectAndResolveDetached(projectName);
            } else {
                resolveDetachedProjects(projects.get(projectName), updatedProject);
                projects.put(projectName, updatedProject);
            }

            LOGGER.finest("Initialized project at " + root);
            return List.of();
        } catch (Exception e) {
            LOGGER.severe("Failed to load project at " + root);

            // If we overwrite an existing project with an empty one, we lose track of the state of tracked
            // files. Instead, we will just keep the original project before the reload failure.
            projects.computeIfAbsent(projectName, ignored -> Project.empty(root));

            return List.of(e);
        }
    }

    void loadWorkspace(WorkspaceFolder workspaceFolder) {
        Path workspaceRoot = Paths.get(URI.create(workspaceFolder.getUri()));
        workspacePaths.add(workspaceRoot);
        try {
            List<Path> projectRoots = ProjectRootVisitor.findProjectRoots(workspaceRoot);
            for (Path root : projectRoots) {
                tryInitProject(root);
            }
        } catch (IOException e) {
            LOGGER.severe(e.getMessage());
        }
    }

    void removeWorkspace(WorkspaceFolder folder) {
        Path workspaceRoot = Paths.get(URI.create(folder.getUri()));
        workspacePaths.remove(workspaceRoot);

        // Have to do the removal separately, so we don't modify project.attachedProjects()
        // while iterating through it
        List<String> projectsToRemove = new ArrayList<>();
        for (var entry : projects.entrySet()) {
            if (entry.getValue().type() == Project.Type.NORMAL && entry.getValue().root().startsWith(workspaceRoot)) {
                projectsToRemove.add(entry.getKey());
            }
        }

        for (String projectName : projectsToRemove) {
            removeProjectAndResolveDetached(projectName);
        }
    }

    List<Exception> applyFileEvents(List<FileEvent> events) {
        List<Exception> errors = new ArrayList<>();

        var changes = WorkspaceChanges.computeWorkspaceChanges(events, this);

        for (var entry : changes.byProject().entrySet()) {
            String projectRoot = entry.getKey();
            ProjectChange projectChange = entry.getValue();

            Project project = findProjectByRoot(projectRoot);

            if (!projectChange.changedBuildFileUris().isEmpty()) {
                // Note: this will take care of removing projects when build files are
                // deleted
                errors.addAll(tryInitProject(project.root()));
            } else {
                Set<String> createdUris = projectChange.createdSmithyFileUris();
                Set<String> deletedUris = projectChange.deletedSmithyFileUris();

                project.updateFiles(createdUris, deletedUris);

                // If any file was previously opened and created a detached project, remove them
                for (String createdUri : createdUris) {
                    projects.remove(createdUri);
                }
            }
        }

        for (var newProjectRoot : changes.newProjectRoots()) {
            errors.addAll(tryInitProject(newProjectRoot));
        }

        return errors;
    }

    private void removeProjectAndResolveDetached(String projectName) {
        Project removedProject = projects.remove(projectName);
        if (removedProject != null) {
            resolveDetachedProjects(removedProject, Project.empty(removedProject.root()));
        }
    }

    private void resolveDetachedProjects(Project oldProject, Project updatedProject) {
        // This is a project reload, so we need to resolve any added/removed files
        // that need to be moved to or from detachedProjects projects.
        if (oldProject != null) {
            Set<String> currentProjectSmithyPaths = oldProject.getAllSmithyFilePaths();
            Set<String> updatedProjectSmithyPaths = updatedProject.getAllSmithyFilePaths();

            Set<String> addedPaths = new HashSet<>(updatedProjectSmithyPaths);
            addedPaths.removeAll(currentProjectSmithyPaths);
            for (String addedPath : addedPaths) {
                String addedUri = LspAdapter.toUri(addedPath);
                projects.remove(addedUri); // Remove any detached projects
            }

            Set<String> removedPaths = new HashSet<>(currentProjectSmithyPaths);
            removedPaths.removeAll(updatedProjectSmithyPaths);
            for (String removedPath : removedPaths) {
                String removedUri = LspAdapter.toUri(removedPath);
                // Only move to a detached project if the file is managed
                if (managedUris.contains(removedUri)) {
                    Document removedDocument = oldProject.getProjectFile(removedUri).document();
                    createDetachedProject(removedUri, removedDocument.copyText());
                }
            }
        }
    }

    private void createDetachedProject(String uri, String text) {
        Project project = ProjectLoader.loadDetached(uri, text);
        projects.put(uri, project);
    }
}
