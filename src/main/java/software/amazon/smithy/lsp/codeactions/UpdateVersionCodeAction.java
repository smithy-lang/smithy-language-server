/*
 * Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.lsp.codeactions;

import java.util.List;
import java.util.Map;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;

public final class UpdateVersionCodeAction {
    private static final int LATEST_VERSION = 2;

    private UpdateVersionCodeAction() {

    }

    /**
     * Build a CodeAction instance that will update the $version statement at of a file.
     * @param fileUri path to the file to update
     * @param versionStatementRange ranger where the $version statement is defined
     * @return a CodeAction with a single TextEdit in it
     */
    public static CodeAction build(String fileUri, Range versionStatementRange) {
        CodeAction codeAction = new CodeAction("Update the Smithy version to " + LATEST_VERSION);
        codeAction.setKind(SmithyCodeActions.SMITHY_UPDATE_VERSION);
        WorkspaceEdit wEdit = new WorkspaceEdit();
        TextEdit edit = new TextEdit(versionStatementRange, "$version: \"" + LATEST_VERSION + "\"");
        Map<String, List<TextEdit>> changes = Map.of(fileUri, List.of(edit));
        wEdit.setChanges(changes);
        codeAction.setEdit(wEdit);
        return codeAction;
    }
}
