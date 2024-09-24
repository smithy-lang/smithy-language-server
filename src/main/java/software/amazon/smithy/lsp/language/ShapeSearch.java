/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import software.amazon.smithy.lsp.document.DocumentId;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.lsp.syntax.NodeCursor;
import software.amazon.smithy.lsp.syntax.Syntax;
import software.amazon.smithy.lsp.syntax.SyntaxSearch;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.Prelude;
import software.amazon.smithy.model.shapes.ResourceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeIdSyntaxException;
import software.amazon.smithy.model.traits.IdRefTrait;

/**
 * Provides methods to search for shapes, using context and syntax specific
 * information, like the current {@link SmithyFile} or {@link IdlPosition}.
 */
final class ShapeSearch {
    private ShapeSearch() {
    }

    /**
     * Attempts to find a shape using a token, {@code nameOrId}.
     *
     * <p>When {@code nameOrId} does not contain a '#', this searches for shapes
     * either in {@code smithyFile}'s namespace, in {@code smithyFile}'s
     * imports, or the prelude, in that order. When {@code nameOrId} does contain
     * a '#', it is assumed to be a full shape id and is searched for directly.
     *
     * @param smithyFile The file {@code nameOrId} is within.
     * @param model The model to search.
     * @param nameOrId The name or shape id of the shape to find.
     * @return The shape, if found.
     */
    static Optional<Shape> findShape(SmithyFile smithyFile, Model model, String nameOrId) {
        return switch (nameOrId) {
            case String s when s.isEmpty() -> Optional.empty();
            case String s when s.contains("#") -> tryFrom(s).flatMap(model::getShape);
            case String s -> {
                Optional<Shape> fromCurrent = tryFromParts(smithyFile.namespace().toString(), s)
                        .flatMap(model::getShape);
                if (fromCurrent.isPresent()) {
                    yield fromCurrent;
                }

                for (String fileImport : smithyFile.imports()) {
                    Optional<Shape> imported = tryFrom(fileImport)
                            .filter(importId -> importId.getName().equals(s))
                            .flatMap(model::getShape);
                    if (imported.isPresent()) {
                        yield imported;
                    }
                }

                yield tryFromParts(Prelude.NAMESPACE, s).flatMap(model::getShape);
            }
            case null -> Optional.empty();
        };
    }

    private static Optional<ShapeId> tryFrom(String id) {
        try {
            return Optional.of(ShapeId.from(id));
        } catch (ShapeIdSyntaxException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<ShapeId> tryFromParts(String namespace, String name) {
        try {
            return Optional.of(ShapeId.fromParts(namespace, name));
        } catch (ShapeIdSyntaxException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Attempts to find the shape referenced by {@code id} at {@code idlPosition} in {@code model}.
     *
     * @param idlPosition The position of the potential shape reference.
     * @param model The model to search for shapes in.
     * @param id The identifier at {@code idlPosition}.
     * @return The shape, if found.
     */
    static Optional<? extends Shape> findShapeDefinition(IdlPosition idlPosition, Model model, DocumentId id) {
        return switch (idlPosition) {
            case IdlPosition.TraitValue traitValue -> {
                var result = searchTraitValue(traitValue, model);
                if (result instanceof NodeSearch.Result.TerminalShape(var s, var m) && s.hasTrait(IdRefTrait.class)) {
                    yield findShape(idlPosition.smithyFile(), m, id.copyIdValue());
                } else if (result instanceof NodeSearch.Result.ObjectKey(var key, var container, var m)
                           && !container.isMapShape()) {
                    yield container.getMember(key.name());
                }
                yield Optional.empty();
            }

            case IdlPosition.NodeMemberTarget nodeMemberTarget -> {
                var result = searchNodeMemberTarget(nodeMemberTarget);
                if (result instanceof NodeSearch.Result.TerminalShape(Shape shape, var ignored)
                    && shape.hasTrait(IdRefTrait.class)) {
                    yield findShape(nodeMemberTarget.smithyFile(), model, id.copyIdValue());
                }
                yield Optional.empty();
            }

            // Note: This could be made more specific, at least for mixins
            case IdlPosition.ElidedMember elidedMember ->
                    findElidedMemberParent(elidedMember, model, id);

            case IdlPosition pos when pos.isEasyShapeReference() ->
                    findShape(pos.smithyFile(), model, id.copyIdValue());

            default -> Optional.empty();
        };
    }

    record ForResourceAndMixins(ResourceShape resource, List<Shape> mixins) {}

    static ForResourceAndMixins findForResourceAndMixins(
            SyntaxSearch.ForResourceAndMixins forResourceAndMixins,
            SmithyFile smithyFile,
            Model model
    ) {
        ResourceShape resourceShape = null;
        if (forResourceAndMixins.forResource() != null) {
            String resourceNameOrId = forResourceAndMixins.forResource()
                    .resource()
                    .copyValueFrom(smithyFile.document());

            resourceShape = findShape(smithyFile, model, resourceNameOrId)
                    .flatMap(Shape::asResourceShape)
                    .orElse(null);
        }
        List<Shape> mixins = List.of();
        if (forResourceAndMixins.mixins() != null) {
            mixins = new ArrayList<>(forResourceAndMixins.mixins().mixins().size());
            for (Syntax.Ident ident : forResourceAndMixins.mixins().mixins()) {
                String mixinNameOrId = ident.copyValueFrom(smithyFile.document());
                findShape(smithyFile, model, mixinNameOrId).ifPresent(mixins::add);
            }
        }

        return new ForResourceAndMixins(resourceShape, mixins);
    }

    /**
     * @param elidedMember The elided member position
     * @param model The model to search in
     * @param id The identifier of the elided member
     * @return The shape the elided member comes from, if found.
     */
    static Optional<? extends Shape> findElidedMemberParent(
            IdlPosition.ElidedMember elidedMember,
            Model model,
            DocumentId id
    ) {
        var forResourceAndMixins = findForResourceAndMixins(
                SyntaxSearch.closestForResourceAndMixinsBeforeMember(
                        elidedMember.smithyFile().statements(),
                        elidedMember.statementIndex()),
                elidedMember.smithyFile(),
                model);

        String searchToken = id.copyIdValueForElidedMember();

        // TODO: Handle ambiguity
        Optional<ResourceShape> foundResource = Optional.ofNullable(forResourceAndMixins.resource())
                .filter(shape -> shape.getIdentifiers().containsKey(searchToken)
                                 || shape.getProperties().containsKey(searchToken));
        if (foundResource.isPresent()) {
            return foundResource;
        }

        return forResourceAndMixins.mixins()
                .stream()
                .filter(shape -> shape.getAllMembers().containsKey(searchToken))
                .findFirst();
    }

    /**
     * @param traitValue The trait value position
     * @param model The model to search in
     * @return The shape that {@code traitValue} is being applied to, if found.
     */
    static Optional<Shape> findTraitTarget(IdlPosition.TraitValue traitValue, Model model) {
        Syntax.Statement.ShapeDef shapeDef = SyntaxSearch.closestShapeDefAfterTrait(
                traitValue.smithyFile().statements(),
                traitValue.statementIndex());

        if (shapeDef == null) {
            return Optional.empty();
        }

        String shapeName = shapeDef.shapeName().copyValueFrom(traitValue.smithyFile().document());
        return findShape(traitValue.smithyFile(), model, shapeName);
    }

    /**
     * @param shape The shape to check
     * @return Whether {@code shape} is represented as an object in a
     *  {@link software.amazon.smithy.lsp.syntax.Syntax.Node}.
     */
    static boolean isObjectShape(Shape shape) {
        return switch (shape.getType()) {
            case STRUCTURE, UNION, MAP -> true;
            default -> false;
        };
    }

    /**
     * @param metadataValue The metadata value position
     * @return The result of searching from the given metadata value within the
     *  {@link Builtins} model.
     */
    static NodeSearch.Result searchMetadataValue(IdlPosition.MetadataValue metadataValue) {
        String metadataKey = metadataValue.metadata().key().copyValueFrom(metadataValue.smithyFile().document());
        Shape metadataValueShapeDef = Builtins.getMetadataValue(metadataKey);
        if (metadataValueShapeDef == null) {
            return NodeSearch.Result.NONE;
        }

        NodeCursor cursor = NodeCursor.create(
                metadataValue.smithyFile().document(),
                metadataValue.metadata().value(),
                metadataValue.documentIndex());
        var dynamicTargets = DynamicMemberTarget.forMetadata(metadataKey, metadataValue.smithyFile());
        return NodeSearch.search(cursor, Builtins.MODEL, metadataValueShapeDef, dynamicTargets);
    }

    /**
     * @param nodeMemberTarget The node member target position
     * @return The result of searching from the given node member target value
     *  within the {@link Builtins} model.
     */
    static NodeSearch.Result searchNodeMemberTarget(IdlPosition.NodeMemberTarget nodeMemberTarget) {
        Syntax.Statement.ShapeDef shapeDef = SyntaxSearch.closestShapeDefBeforeMember(
                nodeMemberTarget.smithyFile().statements(),
                nodeMemberTarget.statementIndex());

        if (shapeDef == null) {
            return NodeSearch.Result.NONE;
        }

        String shapeType = shapeDef.shapeType().copyValueFrom(nodeMemberTarget.smithyFile().document());
        String memberName = nodeMemberTarget.nodeMemberDef()
                .name()
                .copyValueFrom(nodeMemberTarget.smithyFile().document());
        Shape memberShapeDef  = Builtins.getMemberTargetForShapeType(shapeType, memberName);

        if (memberShapeDef == null) {
            return NodeSearch.Result.NONE;
        }

        // This is a workaround for the case when you just have 'operations: <nothing>'.
        // Alternatively, we could add an 'empty' Node value, if this situation comes up
        // elsewhere.
        //
        // TODO: Note that searchTraitValue has to do a similar thing, but parsing
        //  trait values always yields at least an empty Kvps, so it is kind of the same.
        if (nodeMemberTarget.nodeMemberDef().value() == null) {
            return new NodeSearch.Result.TerminalShape(memberShapeDef, Builtins.MODEL);
        }

        NodeCursor cursor = NodeCursor.create(
                nodeMemberTarget.smithyFile().document(),
                nodeMemberTarget.nodeMemberDef().value(),
                nodeMemberTarget.documentIndex());
        return NodeSearch.search(cursor, Builtins.MODEL, memberShapeDef);
    }

    /**
     * @param traitValue The trait value position
     * @param model The model to search
     * @return The result of searching from {@code traitValue} within {@code model}.
     */
    static NodeSearch.Result searchTraitValue(IdlPosition.TraitValue traitValue, Model model) {
        String traitName = traitValue.traitApplication().id().copyValueFrom(traitValue.smithyFile().document());
        Optional<Shape> maybeTraitShape = findShape(traitValue.smithyFile(), model, traitName);
        if (maybeTraitShape.isEmpty()) {
            return NodeSearch.Result.NONE;
        }

        Shape traitShape = maybeTraitShape.get();
        NodeCursor cursor = NodeCursor.create(
                traitValue.smithyFile().document(),
                traitValue.traitApplication().value(),
                traitValue.documentIndex());
        if (cursor.isTerminal() && isObjectShape(traitShape)) {
            // In this case, we've just started to type '@myTrait(foo)', which to the parser looks like 'foo' is just
            // an identifier. But this would mean you don't get member completions when typing the first trait value
            // member, so we can modify the node path to make it _look_ like it's actually a key
            cursor.edges().addFirst(new NodeCursor.Obj(new Syntax.Node.Kvps()));
        }

        var dynamicTargets = DynamicMemberTarget.forTrait(traitShape, traitValue);
        return NodeSearch.search(cursor, model, traitShape, dynamicTargets);
    }
}
