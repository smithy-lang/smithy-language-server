/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.Set;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Range;
import software.amazon.smithy.lsp.document.DocumentId;
import software.amazon.smithy.lsp.project.Project;

/**
 * Simple POJO capturing common properties that completers need.
 */
final class CompleterContext {
    private final String matchToken;
    private final Range insertRange;
    private final Project project;
    private Set<String> exclude = Set.of();
    private CompletionItemKind literalKind = CompletionItemKind.Field;

    private CompleterContext(String matchToken, Range insertRange, Project project) {
        this.matchToken = matchToken;
        this.insertRange = insertRange;
        this.project = project;
    }

    /**
     * @param id The id at the cursor position.
     * @param insertRange The range to insert completion text in.
     * @param project The project the completion was triggered in.
     * @return A new completer context.
     */
    static CompleterContext create(DocumentId id, Range insertRange, Project project) {
        String matchToken = getMatchToken(id);
        return new CompleterContext(matchToken, insertRange, project);
    }

    private static String getMatchToken(DocumentId id) {
        return id != null
                ? id.copyIdValue().toLowerCase()
                : "";
    }

    /**
     * @return The token to match candidates against.
     */
    String matchToken() {
        return matchToken;
    }

    /**
     * @return The range to insert completion text.
     */
    Range insertRange() {
        return insertRange;
    }

    /**
     * @return The project the completion was triggered in.
     */
    Project project() {
        return project;
    }

    /**
     * @return The set of tokens to exclude.
     */
    Set<String> exclude() {
        return exclude;
    }

    CompleterContext withExclude(Set<String> exclude) {
        this.exclude = exclude;
        return this;
    }

    /**
     * @return The kind of completion to use for {@link CompletionCandidates.Literals},
     *  which will be displayed in the client.
     */
    CompletionItemKind literalKind() {
        return literalKind;
    }

    CompleterContext withLiteralKind(CompletionItemKind literalKind) {
        this.literalKind = literalKind;
        return this;
    }
}
