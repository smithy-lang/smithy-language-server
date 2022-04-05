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

package software.amazon.smithy.lsp.ext.model;

import java.util.Collection;
import java.util.List;
import software.amazon.smithy.utils.BuilderRef;
import software.amazon.smithy.utils.SmithyBuilder;
import software.amazon.smithy.utils.ToSmithyBuilder;

/**
 * MavenConfig stored in {@link SmithyBuildExtensions}.
 */
public final class MavenConfig implements ToSmithyBuilder<MavenConfig> {
    private final List<String> dependencies;
    private final List<MavenRepository> repositories;

    private MavenConfig(Builder builder) {
        this.dependencies = builder.dependencies.copy();
        this.repositories = builder.repositories.copy();
    }

    public List<String> getDependencies() {
        return dependencies;
    }

    public List<MavenRepository> getRepositories() {
        return repositories;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Builder toBuilder() {
        return builder()
                .dependencies(dependencies)
                .repositories(repositories);
    }

    /**
     * Builds a {@link MavenConfig}.
     */
    public static final class Builder implements SmithyBuilder<MavenConfig> {
        private final BuilderRef<List<String>> dependencies = BuilderRef.forList();
        private final BuilderRef<List<MavenRepository>> repositories = BuilderRef.forList();

        private Builder() {
        }

        /**
         * Builds the Maven config.
         *
         * @return Returns the created Maven config.
         */
        public MavenConfig build() {
            return new MavenConfig(this);
        }

        /**
         * Replaces the dependencies of the Maven config.
         *
         * @param dependencies Dependencies to set.
         * @return Returns the builder.
         */
        public Builder dependencies(Collection<String> dependencies) {
            this.dependencies.clear();
            this.dependencies.get().addAll(dependencies);
            return this;
        }

        /**
         * Replaces the repositories of the Maven config.
         *
         * @param repositories Repositories to set.
         * @return Returns the builder.
         */
        public Builder repositories(Collection<MavenRepository> repositories) {
            this.repositories.clear();
            this.repositories.get().addAll(repositories);
            return this;
        }
    }
}
