/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
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
     * @param name Name of the project, should be path of the project directory
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
}
