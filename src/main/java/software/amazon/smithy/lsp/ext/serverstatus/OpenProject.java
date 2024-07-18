/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.ext.serverstatus;

import java.util.List;
import org.eclipse.lsp4j.jsonrpc.validation.NonNull;

/**
 * A snapshot of a project the server has open.
 */
public class OpenProject {
    @NonNull
    private final String root;
    @NonNull
    private final List<String> files;
    private final boolean isDetached;

    /**
     * @param root The root URI of the project
     * @param files The list of all file URIs tracked by the project
     * @param isDetached Whether the project is detached
     */
    public OpenProject(@NonNull final String root, @NonNull final List<String> files, boolean isDetached) {
        this.root = root;
        this.files = files;
        this.isDetached = isDetached;
    }

    /**
     * @return The root directory of the project
     */
    public String root() {
        return root;
    }

    /**
     * @return The list of all file URIs tracked by the project
     */
    public List<String> files() {
        return files;
    }

    /**
     * @return Whether the project is detached - tracking just a single open file
     */
    public boolean isDetached() {
        return isDetached;
    }
}
