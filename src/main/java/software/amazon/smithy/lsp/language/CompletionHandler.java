/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.CompletionTriggerKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import software.amazon.smithy.lsp.document.DocumentId;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.lsp.syntax.NodeCursor;
import software.amazon.smithy.lsp.syntax.Syntax;
import software.amazon.smithy.lsp.syntax.SyntaxSearch;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.StructureShape;

/**
 * Handles completion requests for the Smithy IDL.
 */
public final class CompletionHandler {
    private final Project project;
    private final SmithyFile smithyFile;

    public CompletionHandler(Project project, SmithyFile smithyFile) {
        this.project = project;
        this.smithyFile = smithyFile;
    }

    /**
     * @param params The request params
     * @return A list of possible completions
     */
    public List<CompletionItem> handle(CompletionParams params, CancelChecker cc) {
        // TODO: This method has to check for cancellation before using shared resources,
        //  and before performing expensive operations. If we have to change this, or do
        //  the same type of thing elsewhere, it would be nice to have some type of state
        //  machine abstraction or similar to make sure cancellation is properly checked.
        if (cc.isCanceled()) {
            return Collections.emptyList();
        }

        Position position = getTokenPosition(params);
        DocumentId id = smithyFile.document().copyDocumentId(position);
        Range insertRange = getInsertRange(id, position);

        if (cc.isCanceled()) {
            return Collections.emptyList();
        }

        IdlPosition idlPosition = IdlPosition.at(smithyFile, position).orElse(null);

        if (cc.isCanceled() || idlPosition == null) {
            return Collections.emptyList();
        }

        SimpleCompletions.Builder builder = SimpleCompletions.builder(id, insertRange).project(project);

        return switch (idlPosition) {
            case IdlPosition.ControlKey ignored -> builder
                    .literalKind(CompletionItemKind.Constant)
                    .buildSimpleCompletions()
                    .getCompletionItems(CompletionCandidates.BUILTIN_CONTROLS);

            case IdlPosition.MetadataKey ignored -> builder
                    .literalKind(CompletionItemKind.Field)
                    .buildSimpleCompletions()
                    .getCompletionItems(CompletionCandidates.BUILTIN_METADATA);

            case IdlPosition.StatementKeyword ignored -> builder
                    .literalKind(CompletionItemKind.Keyword)
                    .buildSimpleCompletions()
                    .getCompletionItems(CompletionCandidates.KEYWORD);

            case IdlPosition.Namespace ignored -> builder
                    .literalKind(CompletionItemKind.Module)
                    .buildSimpleCompletions()
                    .getCompletionItems(CompletionCandidates.Custom.PROJECT_NAMESPACES);

            case IdlPosition.MetadataValue metadataValue -> metadataValueCompletions(metadataValue, builder);

            case IdlPosition.MemberName memberName -> memberNameCompletions(memberName, builder);

            default -> modelBasedCompletions(idlPosition, builder);
        };
    }

    private static Position getTokenPosition(CompletionParams params) {
        Position position = params.getPosition();
        CompletionContext context = params.getContext();
        if (context != null
            && context.getTriggerKind() == CompletionTriggerKind.Invoked
            && position.getCharacter() > 0) {
            position.setCharacter(position.getCharacter() - 1);
        }
        return position;
    }

    private static Range getInsertRange(DocumentId id, Position position) {
        if (id == null || id.idSlice().isEmpty()) {
            // TODO: This is confusing
            // When we receive the completion request, we're always on the
            // character either after what has just been typed, or we're in
            // empty space and have manually triggered a completion. To account
            // for this when extracting the DocumentId the cursor is on, we move
            // the cursor back one. But when we're not on a DocumentId (as is the case here),
            // we want to insert any completion text at the current cursor position.
            Position point = new Position(position.getLine(), position.getCharacter() + 1);
            return LspAdapter.point(point);
        }
        return id.range();
    }

    private List<CompletionItem> metadataValueCompletions(
            IdlPosition.MetadataValue metadataValue,
            SimpleCompletions.Builder builder
    ) {
        var result = ShapeSearch.searchMetadataValue(metadataValue);
        Set<String> excludeKeys = getOtherPresentKeys(result);
        CompletionCandidates candidates = CompletionCandidates.fromSearchResult(result);
        return builder.exclude(excludeKeys).buildSimpleCompletions().getCompletionItems(candidates);
    }

    private Set<String> getOtherPresentKeys(NodeSearch.Result result) {
        Syntax.Node.Kvps terminalContainer;
        NodeCursor.Key terminalKey;
        switch (result) {
            case NodeSearch.Result.ObjectShape obj -> {
                terminalContainer = obj.node();
                terminalKey = null;
            }
            case NodeSearch.Result.ObjectKey key -> {
                terminalContainer = key.key().parent();
                terminalKey = key.key();
            }
            default -> {
                return null;
            }
        }

        Set<String> ignoreKeys = new HashSet<>();
        terminalContainer.kvps().forEach(kvp -> {
            String key = kvp.key().copyValueFrom(smithyFile.document());
            ignoreKeys.add(key);
        });

        if (terminalKey != null) {
            ignoreKeys.remove(terminalKey.name());
        }

        return ignoreKeys;
    }

    private List<CompletionItem> modelBasedCompletions(IdlPosition idlPosition, SimpleCompletions.Builder builder) {
        if (project.modelResult().getResult().isEmpty()) {
            return List.of();
        }

        Model model = project.modelResult().getResult().get();

        if (idlPosition instanceof IdlPosition.ElidedMember elidedMember) {
            return elidedMemberCompletions(elidedMember, model, builder);
        } else if (idlPosition instanceof IdlPosition.TraitValue traitValue) {
            return traitValueCompletions(traitValue, model, builder);
        }

        CompletionCandidates candidates = CompletionCandidates.shapeCandidates(idlPosition);
        if (candidates instanceof CompletionCandidates.Shapes shapes) {
            return builder.buildShapeCompletions(idlPosition, model).getCompletionItems(shapes);
        } else if (candidates != CompletionCandidates.NONE) {
            return builder.buildSimpleCompletions().getCompletionItems(candidates);
        }

        return List.of();
    }

    private List<CompletionItem> elidedMemberCompletions(
            IdlPosition.ElidedMember elidedMember,
            Model model,
            SimpleCompletions.Builder builder
    ) {
        CompletionCandidates candidates = getElidableMemberCandidates(elidedMember.statementIndex(), model);
        if (candidates == null) {
            return List.of();
        }

        Set<String> otherMembers = SyntaxSearch.otherMemberNames(
                elidedMember.smithyFile().document(),
                elidedMember.smithyFile().statements(),
                elidedMember.statementIndex());
        return builder.exclude(otherMembers).buildSimpleCompletions().getCompletionItems(candidates);
    }

    private List<CompletionItem> traitValueCompletions(
            IdlPosition.TraitValue traitValue,
            Model model,
            SimpleCompletions.Builder builder
    ) {
        var result = ShapeSearch.searchTraitValue(traitValue, model);
        Set<String> excludeKeys = getOtherPresentKeys(result);
        CompletionCandidates candidates = CompletionCandidates.fromSearchResult(result);
        return builder.exclude(excludeKeys).buildSimpleCompletions().getCompletionItems(candidates);
    }

    private List<CompletionItem> memberNameCompletions(
            IdlPosition.MemberName memberName,
            SimpleCompletions.Builder builder
    ) {
        Syntax.Statement.ShapeDef shapeDef = SyntaxSearch.closestShapeDefBeforeMember(
                smithyFile.statements(),
                memberName.statementIndex());

        if (shapeDef == null) {
            return List.of();
        }

        String shapeType = shapeDef.shapeType().copyValueFrom(smithyFile.document());
        StructureShape shapeMembersDef = Builtins.getMembersForShapeType(shapeType);

        CompletionCandidates candidates = null;
        if (shapeMembersDef != null) {
            candidates = CompletionCandidates.membersCandidates(Builtins.MODEL, shapeMembersDef);
        }

        if (project.modelResult().getResult().isPresent()) {
            CompletionCandidates elidedCandidates = getElidableMemberCandidates(
                    memberName.statementIndex(),
                    project.modelResult().getResult().get());

            if (elidedCandidates != null) {
                candidates = candidates == null
                        ? elidedCandidates
                        : new CompletionCandidates.And(candidates, elidedCandidates);
            }
        }

        if (candidates == null) {
            return List.of();
        }

        Set<String> otherMembers = SyntaxSearch.otherMemberNames(
                smithyFile.document(),
                smithyFile.statements(),
                memberName.statementIndex());
        return builder.exclude(otherMembers).buildSimpleCompletions().getCompletionItems(candidates);
    }

    private CompletionCandidates getElidableMemberCandidates(int statementIndex, Model model) {
        var resourceAndMixins = ShapeSearch.findForResourceAndMixins(
                SyntaxSearch.closestForResourceAndMixinsBeforeMember(smithyFile.statements(), statementIndex),
                smithyFile,
                model);

        Set<String> memberNames = new HashSet<>();

        if (resourceAndMixins.resource() != null) {
            memberNames.addAll(resourceAndMixins.resource().getIdentifiers().keySet());
            memberNames.addAll(resourceAndMixins.resource().getProperties().keySet());
        }

        resourceAndMixins.mixins()
                .forEach(mixinShape -> memberNames.addAll(mixinShape.getMemberNames()));

        if (memberNames.isEmpty()) {
            return null;
        }

        return new CompletionCandidates.ElidedMembers(memberNames);
    }
}
