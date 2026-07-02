/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import software.amazon.smithy.build.model.MavenConfig;
import software.amazon.smithy.build.model.MavenRepository;
import software.amazon.smithy.lsp.diff.DiffConfig;

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
    private final List<Path> modelPaths;
    private final List<URL> resolvedDependencies;
    private final DiffConfig diffConfig;

    ProjectConfig(
            List<String> sources,
            List<String> imports,
            List<SmithyProjectJson.ProjectDependency> projectDependencies,
            MavenConfig maven,
            List<Path> modelPaths,
            List<URL> resolvedDependencies,
            DiffConfig diffConfig
    ) {
        this.sources = sources;
        this.imports = imports;
        this.projectDependencies = projectDependencies;
        this.maven = maven == null ? DEFAULT_MAVEN : maven;
        this.modelPaths = modelPaths;
        this.resolvedDependencies = resolvedDependencies;
        this.diffConfig = diffConfig;
    }

    private ProjectConfig() {
        this(List.of(), List.of(), List.of(), DEFAULT_MAVEN, List.of(), List.of(), null);
    }

    private ProjectConfig(Path modelPath) {
        this(List.of(), List.of(), List.of(), DEFAULT_MAVEN, List.of(modelPath), List.of(), null);
    }

    static ProjectConfig empty() {
        return new ProjectConfig();
    }

    static ProjectConfig detachedConfig(Path modelPath) {
        return new ProjectConfig(modelPath);
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

    Set<MavenRepository> mavenRepositories() {
        return ProjectConfigLoader.configuredMavenRepos(maven);
    }

    List<Path> modelPaths() {
        return modelPaths;
    }

    List<URL> resolvedDependencies() {
        return resolvedDependencies;
    }

    Optional<DiffConfig> diffConfig() {
        return Optional.ofNullable(diffConfig);
    }
}
