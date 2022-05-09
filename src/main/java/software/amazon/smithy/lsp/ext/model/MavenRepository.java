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

import software.amazon.smithy.utils.SmithyBuilder;
import software.amazon.smithy.utils.ToSmithyBuilder;

/**
 * MavenRepository stored in a {@link MavenConfig}.
 */
public final class MavenRepository implements ToSmithyBuilder<MavenRepository> {
    private final String url;

    private MavenRepository(Builder builder) {
        this.url = builder.url;
    }

    public String getUrl() {
        return url;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public SmithyBuilder<MavenRepository> toBuilder() {
        return builder().url(url);
    }

    /**
     * Builds a {@link MavenRepository}.
     */
    public static final class Builder implements SmithyBuilder<MavenRepository> {
        private String url;

        private Builder() {

        }

        /**
         * Builds the Maven repository.
         *
         * @return Returns the created Maven repository.
         */
        public MavenRepository build() {
            return new MavenRepository(this);
        }

        /**
         * Replaces the url of a Maven repository.
         * @param url String of the Maven repository.
         * @return Returns the builder.
         */
        public Builder url(String url) {
            this.url = url;
            return this;
        }
    }

    @Override
    public String toString() {
        return "MavenRepository(url = " + url + ")";
    }
}
