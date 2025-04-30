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
import software.amazon.smithy.lsp.project.IdlFile;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.lsp.syntax.StatementView;
import software.amazon.smithy.lsp.syntax.Syntax;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.StructureShape;

/**
 * Handles completion requests for the Smithy IDL.
 */
public final class CompletionHandler {
    private final Project project;
    private final IdlFile smithyFile;

    public CompletionHandler(Project project, IdlFile smithyFile) {
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

        Syntax.IdlParseResult parseResult = smithyFile.getParse();
        int documentIndex = smithyFile.document().indexOfPosition(position);
        IdlPosition idlPosition = StatementView.createAt(parseResult, documentIndex)
                .map(IdlPosition::of)
                .orElse(null);

        if (cc.isCanceled() || idlPosition == null) {
            return Collections.emptyList();
        }

        CompleterContext context = CompleterContext.create(id, insertRange, project);

        return switch (idlPosition) {
            case IdlPosition.ControlKey ignored ->
                    new SimpleCompleter(context.withLiteralKind(CompletionItemKind.Constant))
                            .getCompletionItems(CompletionCandidates.BUILTIN_CONTROLS);

            case IdlPosition.MetadataKey ignored ->
                    new SimpleCompleter(context.withLiteralKind(CompletionItemKind.Field))
                            .getCompletionItems(CompletionCandidates.BUILTIN_METADATA);

            case IdlPosition.StatementKeyword ignored ->
                    new SimpleCompleter(context.withLiteralKind(CompletionItemKind.Keyword))
                            .getCompletionItems(CompletionCandidates.KEYWORD);

            case IdlPosition.Namespace ignored ->
                    new SimpleCompleter(context.withLiteralKind(CompletionItemKind.Module))
                            .getCompletionItems(CompletionCandidates.Custom.PROJECT_NAMESPACES);

            case IdlPosition.MetadataValue metadataValue -> metadataValueCompletions(metadataValue, context);

            case IdlPosition.MemberName memberName -> memberNameCompletions(memberName, context);

            default -> modelBasedCompletions(idlPosition, context);
        };
    }

    static Position getTokenPosition(CompletionParams params) {
        Position position = params.getPosition();
        CompletionContext context = params.getContext();
        if (context != null
            && context.getTriggerKind() == CompletionTriggerKind.Invoked
            && position.getCharacter() > 0) {
            position.setCharacter(position.getCharacter() - 1);
        }
        return position;
    }

    static Range getInsertRange(DocumentId id, Position position) {
        if (id == null || id.idSlice().isEmpty()) {
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
            CompleterContext context
    ) {
        var result = ShapeSearch.searchMetadataValue(metadataValue);
        Set<String> excludeKeys = result.getOtherPresentKeys();
        CompletionCandidates candidates = CompletionCandidates.fromSearchResult(result);
        return new SimpleCompleter(context.withExclude(excludeKeys)).getCompletionItems(candidates);
    }

    private List<CompletionItem> modelBasedCompletions(IdlPosition idlPosition, CompleterContext context) {
        if (project.modelResult().getResult().isEmpty()) {
            return List.of();
        }

        Model model = project.modelResult().getResult().get();

        if (idlPosition instanceof IdlPosition.ElidedMember elidedMember) {
            return elidedMemberCompletions(elidedMember, context, model);
        } else if (idlPosition instanceof IdlPosition.TraitValue traitValue) {
            return traitValueCompletions(traitValue, context, model);
        }

        CompletionCandidates candidates = CompletionCandidates.shapeCandidates(idlPosition);
        if (candidates instanceof CompletionCandidates.Shapes shapes) {
            return new ShapeCompleter(idlPosition, model, context).getCompletionItems(shapes);
        } else if (candidates != CompletionCandidates.NONE) {
            return new SimpleCompleter(context).getCompletionItems(candidates);
        }

        return List.of();
    }

    private List<CompletionItem> elidedMemberCompletions(
            IdlPosition.ElidedMember elidedMember,
            CompleterContext context,
            Model model
    ) {
        CompletionCandidates candidates = getElidableMemberCandidates(elidedMember, model);
        if (candidates == null) {
            return List.of();
        }

        Set<String> otherMembers = elidedMember.view().otherMemberNames();
        return new SimpleCompleter(context.withExclude(otherMembers)).getCompletionItems(candidates);
    }

    private List<CompletionItem> traitValueCompletions(
            IdlPosition.TraitValue traitValue,
            CompleterContext context,
            Model model
    ) {
        var result = ShapeSearch.searchTraitValue(traitValue, model);
        Set<String> excludeKeys = result.getOtherPresentKeys();
        var contextWithExclude = context.withExclude(excludeKeys);

        CompletionCandidates candidates = CompletionCandidates.fromSearchResult(result);
        if (candidates instanceof CompletionCandidates.Shapes shapes) {
            return new ShapeCompleter(traitValue, model, contextWithExclude).getCompletionItems(shapes);
        }

        return new SimpleCompleter(contextWithExclude).getCompletionItems(candidates);
    }

    private List<CompletionItem> memberNameCompletions(IdlPosition.MemberName memberName, CompleterContext context) {
        Syntax.Statement.ShapeDef shapeDef = memberName.view().nearestShapeDefBefore();

        if (shapeDef == null) {
            return List.of();
        }

        String shapeType = shapeDef.shapeType().stringValue();
        StructureShape shapeMembersDef = Builtins.getMembersForShapeType(shapeType);

        CompletionCandidates candidates = null;
        if (shapeMembersDef != null) {
            candidates = CompletionCandidates.membersCandidates(Builtins.MODEL, shapeMembersDef);
        }

        if (project.modelResult().getResult().isPresent()) {
            CompletionCandidates elidedCandidates = getElidableMemberCandidates(
                    memberName,
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

        Set<String> otherMembers = memberName.view().otherMemberNames();
        return new SimpleCompleter(context.withExclude(otherMembers)).getCompletionItems(candidates);
    }

    private CompletionCandidates getElidableMemberCandidates(IdlPosition idlPosition, Model model) {
        Set<String> memberNames = new HashSet<>();

        var forResourceAndMixins = idlPosition.view().nearestForResourceAndMixinsBefore();
        ShapeSearch.findResource(forResourceAndMixins.forResource(), idlPosition.view(), model)
                .ifPresent(resourceShape -> {
                    memberNames.addAll(resourceShape.getIdentifiers().keySet());
                    memberNames.addAll(resourceShape.getProperties().keySet());
                });
        ShapeSearch.findMixins(forResourceAndMixins.mixins(), idlPosition.view(), model)
                .forEach(mixinShape -> memberNames.addAll(mixinShape.getMemberNames()));

        if (memberNames.isEmpty()) {
            return null;
        }

        return new CompletionCandidates.ElidedMembers(memberNames);
    }
}
