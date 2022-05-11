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

package software.amazon.smithy.lsp;

import java.io.File;
import java.net.URI;
import java.util.Optional;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;
import software.amazon.smithy.lsp.ext.Constants;
import software.amazon.smithy.lsp.ext.LspLog;

public class SmithyWorkspaceService implements WorkspaceService {
    private final Optional<SmithyTextDocumentService> tds;

    public SmithyWorkspaceService(Optional<SmithyTextDocumentService> tds) {
        this.tds = tds;
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {

        boolean buildFilesChanged = params.getChanges().stream().anyMatch(change -> {
            String filename = fileFromUri(change.getUri()).getName();
            return Constants.BUILD_FILES.contains(filename);
        });

        if (buildFilesChanged) {
            LspLog.println("Build files changed, rebuilding the project");
            this.tds.ifPresent(tds -> tds.getRoot().ifPresent(tds::createProject));
        }

    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        // TODO Auto-generated method stub

    }

    private File fileFromUri(String uri) {
        return new File(URI.create(uri));
    }

}
