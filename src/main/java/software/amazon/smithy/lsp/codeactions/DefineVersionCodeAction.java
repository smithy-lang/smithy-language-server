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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;

public final class DefineVersionCodeAction {
    private static final int DEFAULT_VERSION = 1;

    private DefineVersionCodeAction() {

    }

    /**
     * Build a CodeAction instance that will add a $version statement at
     * the top of the file.
     * @param fileUri path to the file to update
     * @return a CodeAction with a single TextEdit in it
     */
    public static CodeAction build(String fileUri) {
        CodeAction codeAction = new CodeAction("Define the Smithy version");
        codeAction.setKind(SmithyCodeActions.SMITHY_DEFINE_VERSION);
        WorkspaceEdit wEdit = new WorkspaceEdit();
        TextEdit edit = new TextEdit(
            new Range(new Position(0, 0), new Position(0, 0)),
            "$version: \"" + DEFAULT_VERSION + "\"\n\n"
        );
        Map<String, List<TextEdit>> changes = Collections.singletonMap(fileUri, Collections.singletonList(edit));
        wEdit.setChanges(changes);
        codeAction.setEdit(wEdit);
        return codeAction;
    }
}
