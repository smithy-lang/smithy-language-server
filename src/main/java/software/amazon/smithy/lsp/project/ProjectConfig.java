/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import software.amazon.smithy.build.model.MavenConfig;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;

/**
 * A complete view of all a project's configuration that is needed to load it,
 * merged from all configuration sources.
 */
public final class ProjectConfig {
    private final List<String> sources;
    private final List<String> imports;
    private final String outputDirectory;
    private final List<ProjectDependency> dependencies;
    private final MavenConfig mavenConfig;
    private final Map<String, BuildFile> buildFiles;

    private ProjectConfig(Builder builder) {
        this.sources = builder.sources;
        this.imports = builder.imports;
        this.outputDirectory = builder.outputDirectory;
        this.dependencies = builder.dependencies;
        this.mavenConfig = builder.mavenConfig;
        this.buildFiles = builder.buildFiles;
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
    public List<String> sources() {
        return sources;
    }

    /**
     * @return All explicitly configured imports
     */
    public List<String> imports() {
        return imports;
    }

    /**
     * @return The configured output directory, if one is present
     */
    public Optional<String> outputDirectory() {
        return Optional.ofNullable(outputDirectory);
    }

    /**
     * @return All configured external (non-maven) dependencies
     */
    public List<ProjectDependency> dependencies() {
        return dependencies;
    }

    /**
     * @return The Maven configuration, if present
     */
    public Optional<MavenConfig> maven() {
        return Optional.ofNullable(mavenConfig);
    }

    /**
     * @return Map of path to each {@link BuildFile} loaded in the project
     */
    public Map<String, BuildFile> buildFiles() {
        return buildFiles;
    }

    static final class Builder {
        final List<String> sources = new ArrayList<>();
        final List<String> imports = new ArrayList<>();
        String outputDirectory;
        final List<ProjectDependency> dependencies = new ArrayList<>();
        MavenConfig mavenConfig;
        final Map<String, BuildFile> buildFiles = new HashMap<>();

        private Builder() {
        }

        static Builder load(BuildFile buildFile) {
            Node node = Node.parseJsonWithComments(buildFile.document().copyText(), buildFile.path());
            ObjectNode objectNode = node.expectObjectNode();
            ProjectConfig.Builder projectConfigBuilder = ProjectConfig.builder();
            objectNode.getArrayMember("sources").ifPresent(arrayNode ->
                    projectConfigBuilder.sources(arrayNode.getElementsAs(StringNode.class).stream()
                            .map(StringNode::getValue)
                            .collect(Collectors.toList())));
            objectNode.getArrayMember("imports").ifPresent(arrayNode ->
                    projectConfigBuilder.imports(arrayNode.getElementsAs(StringNode.class).stream()
                            .map(StringNode::getValue)
                            .collect(Collectors.toList())));
            objectNode.getStringMember("outputDirectory").ifPresent(stringNode ->
                    projectConfigBuilder.outputDirectory(stringNode.getValue()));
            objectNode.getArrayMember("dependencies").ifPresent(arrayNode ->
                    projectConfigBuilder.dependencies(arrayNode.getElements().stream()
                            .map(ProjectDependency::fromNode)
                            .collect(Collectors.toList())));
            return projectConfigBuilder;
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

        public Builder buildFiles(Map<String, BuildFile> buildFiles) {
            this.buildFiles.putAll(buildFiles);
            return this;
        }

        public ProjectConfig build() {
            return new ProjectConfig(this);
        }
    }
}
