/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import software.amazon.smithy.lsp.document.DocumentId;
import software.amazon.smithy.lsp.project.BuildFile;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.syntax.NodeCursor;
import software.amazon.smithy.model.shapes.Shape;

/**
 * Handles completions requests for {@link BuildFile}s.
 */
public final class BuildCompletionHandler {
    private final Project project;
    private final BuildFile buildFile;

    public BuildCompletionHandler(Project project, BuildFile buildFile) {
        this.project = project;
        this.buildFile = buildFile;
    }

    /**
     * @param params The request params
     * @return A list of possible completions
     */
    public List<CompletionItem> handle(CompletionParams params) {
        Position position = CompletionHandler.getTokenPosition(params);
        DocumentId id = buildFile.document().copyDocumentId(position);
        Range insertRange = CompletionHandler.getInsertRange(id, position);

        Shape buildFileShape = Builtins.getBuildFileShape(buildFile.type());

        if (buildFileShape == null) {
            return List.of();
        }

        NodeCursor cursor = NodeCursor.create(
                buildFile.getParse().value(),
                buildFile.document().indexOfPosition(position)
        );
        NodeSearch.Result searchResult = NodeSearch.search(cursor, Builtins.MODEL, buildFileShape);
        var candidates = CompletionCandidates.fromSearchResult(searchResult);

        var context = CompleterContext.create(id, insertRange, project)
                .withExclude(searchResult.getOtherPresentKeys());
        var mapper = new SimpleCompleter.BuildFileMapper(context);

        return new SimpleCompleter(context, mapper).getCompletionItems(candidates);
    }
}
