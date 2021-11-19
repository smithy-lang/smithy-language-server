/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.lsp.ext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.SmithyBuilder;
import software.amazon.smithy.utils.ToSmithyBuilder;

public final class SmithyBuildExtensions implements ToSmithyBuilder<SmithyBuildExtensions> {
    private final List<String> mavenRepositories;
    private final List<String> mavenDependencies;

    private SmithyBuildExtensions(Builder b) {
        this.mavenDependencies = ListUtils.copyOf(b.mavenDependencies);
        this.mavenRepositories = ListUtils.copyOf(b.mavenRepositories);
    }

    public List<String> getMavenDependencies() {
        return mavenDependencies;
    }

    public List<String> getMavenRepositories() {
        return mavenRepositories;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public SmithyBuilder<SmithyBuildExtensions> toBuilder() {
        return builder().mavenDependencies(mavenDependencies).mavenRepositories(mavenRepositories);
    }

    public static final class Builder implements SmithyBuilder<SmithyBuildExtensions> {
        private final List<String> mavenRepositories = new ArrayList<>();
        private final List<String> mavenDependencies = new ArrayList<>();

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
            mavenDependencies.addAll(other.mavenDependencies);
            mavenRepositories.addAll(other.mavenRepositories);

            return this;
        }

        /**
         * Adds resolvers to the builder.
         *
         * @param mavenRepositories list of maven-compatible repositories
         * @return builder
         */
        public Builder mavenRepositories(Collection<String> mavenRepositories) {
            this.mavenRepositories.clear();
            this.mavenRepositories.addAll(mavenRepositories);
            return this;
        }

        /**
         * Adds dependencies to the builder.
         *
         * @param mavenDependencies list of artifacts in the org:name:version format
         * @return builder
         */

        public Builder mavenDependencies(Collection<String> mavenDependencies) {
            this.mavenDependencies.clear();
            this.mavenDependencies.addAll(mavenDependencies);
            return this;
        }
    }

    @Override
    public String toString() {
        return "SmithyBuildExtensions(repositories=" + mavenRepositories.toString() + ",artifacts="
                + mavenDependencies.toString() + ")";
    }
}
