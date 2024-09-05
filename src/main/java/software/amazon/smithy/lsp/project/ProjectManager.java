/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.WorkspaceFolder;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.protocol.LspAdapter;

/**
 * Manages open projects tracked by the server.
 */
public final class ProjectManager {
    private static final Logger LOGGER = Logger.getLogger(ProjectManager.class.getName());

    private final Map<String, Project> detached = new HashMap<>();
    private final Map<String, Project> attached = new HashMap<>();

    public ProjectManager() {
    }

    /**
     * @param name Name of the project, usually comes from {@link WorkspaceFolder#getName()}
     * @return The project with the given name, if it exists
     */
    public Project getProjectByName(String name) {
        return this.attached.get(name);
    }

    /**
     * @param name Name of the project to update
     * @param updated Project to update
     */
    public void updateProjectByName(String name, Project updated) {
        this.attached.put(name, updated);
    }

    /**
     * @param name Name of the project to remove
     * @return The removed project, if it exists
     */
    public Project removeProjectByName(String name) {
        return this.attached.remove(name);
    }

    /**
     * @return A map of URIs of open files that aren't attached to a tracked project
     *  to their own detached projects. These projects contain only the file that
     *  corresponds to the key in the map.
     */
    public Map<String, Project> detachedProjects() {
        return detached;
    }

    /**
     * @return A map of project names to projects tracked by the server
     */
    public Map<String, Project> attachedProjects() {
        return attached;
    }

    /**
     * @param uri The URI of the file belonging to the project to get
     * @return The project the given {@code uri} belongs to
     */
    public Project getProject(String uri) {
        String path = LspAdapter.toPath(uri);
        if (isDetached(uri)) {
            return detached.get(uri);
        }  else  {
            for (Project project : attached.values()) {
                if (project.smithyFiles().containsKey(path)) {
                    return project;
                }
            }

            LOGGER.warning(() -> "Tried getting project for unknown file: " + uri);

            return null;
        }
    }

    /**
     * Note: This is equivalent to {@code getProject(uri) == null}. If this is true,
     * there is also a corresponding {@link SmithyFile} in {@link Project#getSmithyFile(String)}.
     *
     * @param uri The URI of the file to check
     * @return True if the given URI corresponds to a file tracked by the server
     */
    public boolean isTracked(String uri) {
        return getProject(uri) != null;
    }

    /**
     * @param uri The URI of the file to check
     * @return Whether the given {@code uri} is of a file in a detached project
     */
    public boolean isDetached(String uri) {
        // We might be in a state where a file was added to a tracked project,
        // but was opened before the project loaded. This would result in it
        // being placed in a detached project. Removing it here is basically
        // like removing it lazily, although it does feel a little hacky.
        String path = LspAdapter.toPath(uri);
        Project nonDetached = getNonDetached(path);
        if (nonDetached != null && detached.containsKey(uri)) {
            removeDetachedProject(uri);
        }

        return detached.containsKey(uri);
    }

    private Project getNonDetached(String path) {
        for (Project project : attached.values()) {
            if (project.smithyFiles().containsKey(path)) {
                return project;
            }
        }
        return null;
    }

    /**
     * @param uri The URI of the file to create a detached project for
     * @param text The text of the file to create a detached project for
     * @return A new detached project of the given {@code uri} and {@code text}
     */
    public Project createDetachedProject(String uri, String text) {
        Project project = ProjectLoader.loadDetached(uri, text);
        detached.put(uri, project);
        return project;
    }

    /**
     * @param uri The URI of the file to remove a detached project for
     * @return The removed project, or null if none existed
     */
    public Project removeDetachedProject(String uri) {
        return detached.remove(uri);
    }

    /**
     * @param uri The URI of the file to get the document of
     * @return The {@link Document} corresponding to the given {@code uri}, if
     *  it exists in any projects, otherwise {@code null}.
     */
    public Document getDocument(String uri) {
        Project project = getProject(uri);
        if (project == null) {
            return null;
        }
        return project.getDocument(uri);
    }

    /**
     * Computes per-project file changes from the given file events.
     *
     * <p>>Note: if you have lots of projects, this will create a bunch of
     * garbage because most times you aren't getting multiple sets of large
     * updates to a project. Project changes are relatively rare, so this
     * shouldn't have a huge impact.
     *
     * @param events The file events to compute per-project file changes from
     * @return A map of project name to the corresponding project's changes
     */
    public Map<String, ProjectChanges> computeProjectChanges(List<FileEvent> events) {
        // Note: we could eagerly compute these and store them, but project changes are relatively rare,
        //  and doing it this way means we don't need to manage the state.
        Map<String, PathMatcher> projectSmithyFileMatchers = new HashMap<>(attachedProjects().size());
        Map<String, PathMatcher> projectBuildFileMatchers = new HashMap<>(attachedProjects().size());

        Map<String, ProjectChanges> changes = new HashMap<>(attachedProjects().size());

        attachedProjects().forEach((projectName, project) -> {
            projectSmithyFileMatchers.put(projectName, ProjectFilePatterns.getSmithyFilesPathMatcher(project));
            projectBuildFileMatchers.put(projectName, ProjectFilePatterns.getBuildFilesPathMatcher(project));

            // Need these to be hash sets so they are mutable
            changes.put(projectName, new ProjectChanges(new HashSet<>(), new HashSet<>(), new HashSet<>()));
        });

        for (FileEvent event : events) {
            String changedUri = event.getUri();
            Path changedPath = Path.of(LspAdapter.toPath(changedUri));
            if (changedUri.endsWith(".smithy")) {
                projectSmithyFileMatchers.forEach((projectName, matcher) -> {
                    if (matcher.matches(changedPath)) {
                        if (event.getType() == FileChangeType.Created) {
                            changes.get(projectName).createdSmithyFileUris().add(changedUri);
                        } else if (event.getType() == FileChangeType.Deleted) {
                            changes.get(projectName).deletedSmithyFileUris().add(changedUri);
                        }
                    }
                });
            } else {
                projectBuildFileMatchers.forEach((projectName, matcher) -> {
                    if (matcher.matches(changedPath)) {
                        changes.get(projectName).changedBuildFileUris().add(changedUri);
                    }
                });
            }
        }

        return changes;
    }
}
