/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.util.HashMap;
import java.util.Map;
import org.eclipse.lsp4j.InitializeParams;
import software.amazon.smithy.lsp.protocol.UriAdapter;

/**
 * Manages open projects tracked by the server.
 */
public final class ProjectManager {
    private final Map<String, Project> detached = new HashMap<>();
    // TODO: Handle multiple main projects
    private Project mainProject;

    public ProjectManager() {
    }

    /**
     * @return The main project (the one with a smithy-build.json). Note that
     *  this will always be present after
     *  {@link org.eclipse.lsp4j.services.LanguageServer#initialize(InitializeParams)}
     *  is called. If there's no smithy-build.json, this is just an empty project.
     */
    public Project getMainProject() {
        return mainProject;
    }

    /**
     * @param updated The updated main project. Overwrites existing main project
     *                without doing a partial update
     */
    public void updateMainProject(Project updated) {
        this.mainProject = updated;
    }

    /**
     * @return A map of URIs of open files that aren't attached to the main project
     *  to their own detached projects. These projects contain only the file that
     *  corresponds to the key in the map.
     */
    public Map<String, Project> getDetachedProjects() {
        return detached;
    }

    /**
     * @param uri The URI of the file belonging to the project to get
     * @return The project the given {@code uri} belongs to
     */
    public Project getProject(String uri) {
        // We might be in a state where a file was added to the main project,
        // but was opened before the project loaded. This would result in it
        // being placed in a detached project. Removing it here is basically
        // like removing it lazily, although it does feel a little hacky.
        if (mainProject.getSmithyFiles().containsKey(UriAdapter.toPath(uri)) && detached.containsKey(uri)) {
            removeDetachedProject(uri);
        }

        if (detached.containsKey(uri)) {
            return detached.get(uri);
        }

        // TODO: Maybe this should take care of loading the detached project, so
        //  we can assume in other places that any given file always belongs to
        //  some project.
        return mainProject;
    }

    /**
     * @param uri The URI of the file to check
     * @return Whether the given {@code uri} is of a file in a detached project
     */
    public boolean isDetached(String uri) {
        return detached.containsKey(uri);
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
}
