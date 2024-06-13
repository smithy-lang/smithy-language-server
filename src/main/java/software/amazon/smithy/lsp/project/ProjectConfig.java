/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import software.amazon.smithy.build.model.MavenConfig;

/**
 * A complete view of all a project's configuration that is needed to load it,
 * merged from all configuration sources.
 */
final class ProjectConfig {
    private final List<String> sources;
    private final List<String> imports;
    private final String outputDirectory;
    private final List<ProjectDependency> dependencies;
    private final MavenConfig mavenConfig;

    private ProjectConfig(Builder builder) {
        this.sources = builder.sources;
        this.imports = builder.imports;
        this.outputDirectory = builder.outputDirectory;
        this.dependencies = builder.dependencies;
        this.mavenConfig = builder.mavenConfig;
    }

    static ProjectConfig empty() {
        return builder().build();
    }

    static Builder builder() {
        return new Builder();
    }

    /**
     * @return All explicitly configured sources
     */
    public List<String> getSources() {
        return sources;
    }

    /**
     * @return All explicitly configured imports
     */
    public List<String> getImports() {
        return imports;
    }

    /**
     * @return The configured output directory, if one is present
     */
    public Optional<String> getOutputDirectory() {
        return Optional.ofNullable(outputDirectory);
    }

    /**
     * @return All configured external (non-maven) dependencies
     */
    public List<ProjectDependency> getDependencies() {
        return dependencies;
    }

    /**
     * @return The Maven configuration, if present
     */
    public Optional<MavenConfig> getMaven() {
        return Optional.ofNullable(mavenConfig);
    }

    static final class Builder {
        final List<String> sources = new ArrayList<>();
        final List<String> imports = new ArrayList<>();
        String outputDirectory;
        final List<ProjectDependency> dependencies = new ArrayList<>();
        MavenConfig mavenConfig;

        private Builder() {
        }

        public Builder sources(List<String> sources) {
            this.sources.clear();
            this.sources.addAll(sources);
            return this;
        }

        public Builder addSources(List<String> sources) {
            this.sources.addAll(sources);
            return this;
        }

        public Builder imports(List<String> imports) {
            this.imports.clear();
            this.imports.addAll(imports);
            return this;
        }

        public Builder addImports(List<String> imports) {
            this.imports.addAll(imports);
            return this;
        }

        public Builder outputDirectory(String outputDirectory) {
            this.outputDirectory = outputDirectory;
            return this;
        }

        public Builder dependencies(List<ProjectDependency> dependencies) {
            this.dependencies.clear();
            this.dependencies.addAll(dependencies);
            return this;
        }

        public Builder mavenConfig(MavenConfig mavenConfig) {
            this.mavenConfig = mavenConfig;
            return this;
        }

        public ProjectConfig build() {
            return new ProjectConfig(this);
        }
    }
}
