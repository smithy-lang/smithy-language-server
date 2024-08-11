/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.lsp.project;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import software.amazon.smithy.build.model.MavenConfig;
import software.amazon.smithy.build.model.MavenRepository;
import software.amazon.smithy.build.model.SmithyBuildConfig;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.SmithyBuilder;
import software.amazon.smithy.utils.ToSmithyBuilder;

/**
 * Legacy build config that supports a subset of {@link SmithyBuildConfig}, in addition to
 * top-level {@code mavenRepositories} and {@code mavenDependencies} properties.
 */
public final class SmithyBuildExtensions implements ToSmithyBuilder<SmithyBuildExtensions> {
    private static final Logger LOGGER = Logger.getLogger(SmithyBuildExtensions.class.getName());

    private final List<String> imports;
    private final List<String> mavenRepositories;
    private final List<String> mavenDependencies;
    private MavenConfig maven;
    private final long lastModifiedInMillis;

    private SmithyBuildExtensions(Builder b) {
        this.mavenDependencies = ListUtils.copyOf(b.mavenDependencies);
        this.mavenRepositories = ListUtils.copyOf(b.mavenRepositories);
        this.imports = ListUtils.copyOf(b.imports);
        this.maven = b.maven;
        lastModifiedInMillis = b.lastModifiedInMillis;
    }

    public List<String> imports() {
        return imports;
    }

    public MavenConfig mavenConfig() {
        return maven;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public SmithyBuilder<SmithyBuildExtensions> toBuilder() {
        return builder()
                .mavenDependencies(mavenDependencies)
                .mavenRepositories(mavenRepositories)
                .maven(maven);
    }

    public long getLastModifiedInMillis() {
        return lastModifiedInMillis;
    }

    /**
     * Merges the MavenConfig from a SmithyBuildConfig into the extensions.
     * @param config SmithyBuildConfig
     */
    public void mergeMavenFromSmithyBuildConfig(SmithyBuildConfig config) {
        if (config.getMaven().isPresent()) {
            maven = config.getMaven().get();
        }
    }

    /**
     * @return This as {@link SmithyBuildConfig}
     */
    public SmithyBuildConfig asSmithyBuildConfig() {
        return SmithyBuildConfig.builder()
                .version("1")
                .imports(imports())
                .maven(mavenConfig())
                .lastModifiedInMillis(getLastModifiedInMillis())
                .build();
    }

    public static final class Builder implements SmithyBuilder<SmithyBuildExtensions> {
        private final List<String> mavenRepositories = new ArrayList<>();
        private final List<String> mavenDependencies = new ArrayList<>();
        private final List<String> imports = new ArrayList<>();
        private MavenConfig maven = MavenConfig.builder().build();
        private long lastModifiedInMillis = 0;

        @Override
        public SmithyBuildExtensions build() {
            return new SmithyBuildExtensions(this);
        }

        /**
         * Adds configuration from other instance to this builder.
         *
         * @param other configuration
         * @return builder
         */
        public Builder merge(SmithyBuildExtensions other) {
            MavenConfig.Builder mavenConfigBuilder = maven.toBuilder();

            List<String> dependencies = new ArrayList<>(maven.getDependencies());
            // Merge dependencies from other extension, preferring those defined on MavenConfig.
            if (other.mavenConfig().getDependencies().isEmpty()) {
                dependencies.addAll(other.mavenDependencies);
            } else {
                dependencies.addAll(other.mavenConfig().getDependencies());
            }
            mavenConfigBuilder.dependencies(dependencies);

            List<MavenRepository> repositories = new ArrayList<>(maven.getRepositories());
            // Merge repositories from other extension, preferring those defined on MavenConfig.
            if (other.mavenConfig().getRepositories().isEmpty()) {
                repositories.addAll(other.mavenRepositories.stream()
                        .map(repo -> MavenRepository.builder().url(repo).build())
                        .toList());
            } else {
                repositories.addAll(other.maven.getRepositories());
            }
            mavenConfigBuilder.repositories(repositories);

            maven = mavenConfigBuilder.build();
            imports.addAll(other.imports);

            return this;
        }

        /**
         * Adds resolvers to the builder.
         *
         * @param mavenRepositories list of maven-compatible repositories
         * @return builder
         *
         * @deprecated Use {@link MavenConfig.Builder#repositories(Collection)}
         */
        @Deprecated
        public Builder mavenRepositories(Collection<String> mavenRepositories) {
            MavenConfig config = maven;
            // If repositories have not been set on current config, set from mavenRepositories.
            if (config.getRepositories().isEmpty()) {
                config = config.toBuilder()
                        .repositories(mavenRepositories.stream()
                                .map(repo -> MavenRepository.builder().url(repo).build())
                                .collect(Collectors.toList()))
                        .build();
                LOGGER.warning("Read deprecated `mavenRepositories` in smithy-build.json. Update smithy-build.json to "
                    + "{\"maven\": {\"repositories\": [{\"url\": \"repo url\"}]}}");
            }

            this.maven = config;
            return this;
        }

        /**
         * Adds dependencies to the builder.
         *
         * @param mavenDependencies list of artifacts in the org:name:version format
         * @return builder
         *
         * @deprecated use {@link MavenConfig.Builder#dependencies(Collection)}
         */
        @Deprecated
        public Builder mavenDependencies(Collection<String> mavenDependencies) {
            MavenConfig config = maven;
            // If dependencies have not been set on current config, set from mavenDependencies.
            if (config.getDependencies().isEmpty()) {
                config = config.toBuilder()
                        .dependencies(mavenDependencies)
                        .build();
                LOGGER.warning("Read deprecated `mavenDependencies` in smithy-build.json. Update smithy-build.json to "
                        + "{\"maven\": {\"dependencies\": [\"dependencyA\", \"dependencyB\"]}}");
            }
            this.maven = config;
            return this;
        }

        /**
         * Adds a Maven configuration to the builder.
         * @param config Maven configuration containing dependencies and repositories.
         * @return builder
         */
        public Builder maven(MavenConfig config) {
            this.maven = MavenConfig.builder()
                    .dependencies(config.getDependencies())
                    .repositories(config.getRepositories())
                    .build();
            return this;
        }

        /**
         * Adds imports to the builder.
         *
         * @param imports list of imports (relative folders of smithy files)
         * @return builder
         */

        public Builder imports(Collection<String> imports) {
            this.imports.clear();
            this.imports.addAll(imports);
            return this;
        }

        /**
         * Adds import to the builder.
         *
         * @param imp import to add
         * @return builder
         */
        public Builder addImport(String imp) {
            this.imports.add(imp);
            return this;
        }

        public Builder lastModifiedInMillis(long lastModifiedInMillis) {
            this.lastModifiedInMillis = lastModifiedInMillis;
            return this;
        }
    }

    @Override
    public String toString() {
        return "SmithyBuildExtensions(repositories = " + maven.getRepositories().toString() + ", artifacts = "
                + maven.getDependencies().toString() + ", imports = " + imports + ")";
    }
}
