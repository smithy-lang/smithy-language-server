/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.ext.serverstatus;

import java.util.List;
import org.eclipse.lsp4j.jsonrpc.validation.NonNull;

/**
 * A snapshot of the server status, containing the projects it has open.
 * We can add more here later as we see fit.
 */
public class ServerStatus {
    @NonNull
    private final List<OpenProject> openProjects;

    public ServerStatus(@NonNull final List<OpenProject> openProjects) {
        this.openProjects = openProjects;
    }

    /**
     * @return The open projects tracked by the server
     */
    public List<OpenProject> openProjects() {
        return openProjects;
    }
}
