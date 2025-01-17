/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import software.amazon.smithy.build.model.MavenConfig;
import software.amazon.smithy.model.validation.ValidationEvent;

/**
 * A complete view of all a project's configuration that is needed to load it,
 * merged from all configuration sources.
 */
final class ProjectConfig {
    private static final MavenConfig DEFAULT_MAVEN = MavenConfig.builder().build();

    private final List<String> sources;
    private final List<String> imports;
    private final List<SmithyProjectJson.ProjectDependency> projectDependencies;
    private final MavenConfig maven;
    private final BuildFiles buildFiles;
    private final List<Path> modelPaths;
    private final List<URL> resolvedDependencies;
    private final ReentrantLock eventsLock = new ReentrantLock();
    private List<ValidationEvent> events;

    ProjectConfig(
            List<String> sources,
            List<String> imports,
            List<SmithyProjectJson.ProjectDependency> projectDependencies,
            MavenConfig maven,
            BuildFiles buildFiles
    ) {
        this.sources = sources;
        this.imports = imports;
        this.projectDependencies = projectDependencies;
        this.maven = maven == null ? DEFAULT_MAVEN : maven;
        this.buildFiles = buildFiles;
        this.modelPaths = List.of();
        this.resolvedDependencies = List.of();
        this.events = List.of();
    }

    ProjectConfig(
            ProjectConfig config,
            List<Path> modelPaths,
            List<URL> resolvedDependencies,
            List<ValidationEvent> events
    ) {
        this.sources = config.sources;
        this.imports = config.imports;
        this.projectDependencies = config.projectDependencies;
        this.maven = config.maven;
        this.buildFiles = config.buildFiles;
        this.modelPaths = modelPaths;
        this.resolvedDependencies = resolvedDependencies;
        this.events = events;
    }

    static ProjectConfig empty() {
        return new ProjectConfig(
                List.of(), List.of(), List.of(), DEFAULT_MAVEN, BuildFiles.empty()
        );
    }

    static ProjectConfig detachedConfig(Path modelPath) {
        return new ProjectConfig(
                empty(),
                List.of(modelPath),
                List.of(),
                List.of()
        );
    }

    List<String> sources() {
        return sources;
    }

    List<String> imports() {
        return imports;
    }

    List<SmithyProjectJson.ProjectDependency> projectDependencies() {
        return projectDependencies;
    }

    MavenConfig maven() {
        return maven;
    }

    BuildFiles buildFiles() {
        return buildFiles;
    }

    List<Path> modelPaths() {
        return modelPaths;
    }

    List<URL> resolvedDependencies() {
        return resolvedDependencies;
    }

    List<ValidationEvent> events() {
        eventsLock.lock();
        try {
            return events;
        } finally {
            eventsLock.unlock();
        }
    }

    void validate() {
        var updatedEvents = ProjectConfigLoader.validateBuildFiles(buildFiles);

        eventsLock.lock();
        try {
            this.events = updatedEvents;
        } finally {
            eventsLock.unlock();
        }
    }
}
