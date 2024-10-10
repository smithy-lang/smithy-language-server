/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.util.HashSet;
import java.util.Set;

/**
 * File changes to a {@link Project}.
 *
 * @param changedBuildFileUris The uris of changed build files
 * @param createdSmithyFileUris The uris of created Smithy files
 * @param deletedSmithyFileUris The uris of deleted Smithy files
 */
public record ProjectChange(
        Set<String> changedBuildFileUris,
        Set<String> createdSmithyFileUris,
        Set<String> deletedSmithyFileUris
) {
    /**
     * @return An empty and mutable set of project changes
     */
    public static ProjectChange empty() {
        return new ProjectChange(
                new HashSet<>(),
                new HashSet<>(),
                new HashSet<>());
    }
}
