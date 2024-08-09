/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.util.Set;

/**
 * File changes to a {@link Project}.
 *
 * @param changedBuildFileUris The uris of changed build files
 * @param createdSmithyFileUris The uris of created Smithy files
 * @param deletedSmithyFileUris The uris of deleted Smithy files
 */
public record ProjectChanges(
        Set<String> changedBuildFileUris,
        Set<String> createdSmithyFileUris,
        Set<String> deletedSmithyFileUris
) {
    /**
     * @return Whether there are any changed build files
     */
    public boolean hasChangedBuildFiles() {
        return !changedBuildFileUris.isEmpty();
    }

    /**
     * @return Whether there are any changed Smithy files
     */
    public boolean hasChangedSmithyFiles() {
        return !createdSmithyFileUris.isEmpty() || !deletedSmithyFileUris.isEmpty();
    }
}
