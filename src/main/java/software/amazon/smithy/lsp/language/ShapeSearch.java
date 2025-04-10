/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import software.amazon.smithy.lsp.document.DocumentId;
import software.amazon.smithy.lsp.document.DocumentImports;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.lsp.syntax.NodeCursor;
import software.amazon.smithy.lsp.syntax.StatementView;
import software.amazon.smithy.lsp.syntax.Syntax;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.Prelude;
import software.amazon.smithy.model.shapes.MapShape;
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
     * either in {@code idlParse}'s imports, in {@code idlParse}'s namespace, or
     * the prelude, in that order. When {@code nameOrId} does contain a '#', it
     * is assumed to be a full shape id and is searched for directly.
     *
     * @param parseResult The parse result of the file {@code nameOrId} is within.
     * @param nameOrId    The name or shape id of the shape to find.
     * @param model       The model to search.
     * @return The shape, if found.
     */
    static Optional<Shape> findShape(Syntax.IdlParseResult parseResult, String nameOrId, Model model) {
        return switch (nameOrId) {
            case null -> Optional.empty();

            case String s when s.isEmpty() -> Optional.empty();

            case String s when s.contains("#") -> tryFrom(s, model);

            default -> fromImports(parseResult.imports(), nameOrId, model)
                    .or(() -> tryFromRelative(parseResult.namespace().namespace(), nameOrId, model))
                    .or(() -> tryFromRelative(Prelude.NAMESPACE, nameOrId, model));
        };
    }

    private static Optional<Shape> fromImports(DocumentImports imports, String nameOrId, Model model) {
        if (imports.imports().isEmpty()) {
            return Optional.empty();
        }

        if (nameOrId.contains("$")) {
            // Relative member id, so it could be a member of an imported shape
            String[] split = nameOrId.split("\\$");
            String containerName = split[0];
            String memberName = split[1];
            String matchString = "#" + containerName;
            for (String fileImport : imports.imports()) {
                if (fileImport.endsWith(matchString)) {
                    return tryWithMember(fileImport, memberName, model);
                }
            }
        } else {
            String matchString = "#" + nameOrId;
            for (String fileImport : imports.imports()) {
                if (fileImport.endsWith(matchString)) {
                    return tryFrom(fileImport, model);
                }
            }
        }

        return Optional.empty();
    }

    private static Optional<Shape> tryFrom(String id, Model model) {
        try {
            ShapeId shapeId = ShapeId.from(id);
            return model.getShape(shapeId);
        } catch (ShapeIdSyntaxException e) {
            return Optional.empty();
        }
    }

    private static Optional<Shape> tryWithMember(String rootId, String memberName, Model model) {
        try {
            ShapeId shapeId = ShapeId.from(rootId).withMember(memberName);
            return model.getShape(shapeId);
        } catch (ShapeIdSyntaxException e) {
            return Optional.empty();
        }
    }

    private static Optional<Shape> tryFromRelative(String namespace, String name, Model model) {
        try {
            ShapeId shapeId = ShapeId.fromRelative(namespace, name);
            return model.getShape(shapeId);
        } catch (ShapeIdSyntaxException e) {
            return Optional.empty();
        }
    }

    /**
     * Attempts to find the shape referenced by {@code id} at {@code idlPosition} in {@code model}.
     *
     * @param idlPosition The position of the potential shape reference.
     * @param id          The identifier at {@code idlPosition}.
     * @param model       The model to search for shapes in.
     * @return The shape, if found.
     */
    static Optional<? extends Shape> findShapeDefinition(IdlPosition idlPosition, DocumentId id, Model model) {
        return switch (idlPosition) {
            case IdlPosition.TraitValue traitValue -> findShapeDefinitionInTrait(traitValue, id, model);

            case IdlPosition.NodeMemberTarget nodeMemberTarget ->
                    findShapeDefinitionInNodeMemberTarget(nodeMemberTarget, id, model);

            // Note: This could be made more specific, at least for mixins
            case IdlPosition.ElidedMember elidedMember ->
                    findElidedMemberParent(elidedMember, id, model);

            case IdlPosition.MemberName memberName -> {
                var parentDef = memberName.view().nearestShapeDefBefore();
                if (parentDef == null) {
                    yield Optional.empty();
                }
                var relativeId = parentDef.shapeName().stringValue() + "$" + memberName.name();
                yield findShape(memberName.view().parseResult(), relativeId, model);
            }

            case IdlPosition pos when pos.isRootShapeReference() ->
                    findShape(pos.view().parseResult(), id.copyIdValue(), model);

            default -> Optional.empty();
        };
    }

    private static Optional<? extends Shape> findShapeDefinitionInTrait(
            IdlPosition.TraitValue traitValue,
            DocumentId id,
            Model model
    ) {
        var result = searchTraitValue(traitValue, model);
        return switch (result) {
            case NodeSearch.Result.TerminalShape terminal when terminal.isIdRef() ->
                    findShape(traitValue.view().parseResult(), id.copyIdValue(), model);

            case NodeSearch.Result.ObjectKey objectKey when !objectKey.containerShape().isMapShape() ->
                    objectKey.containerShape().getMember(objectKey.key().name());

            default -> Optional.empty();
        };
    }

    private static Optional<? extends Shape> findShapeDefinitionInNodeMemberTarget(
            IdlPosition.NodeMemberTarget nodeMemberTarget,
            DocumentId id,
            Model model
    ) {
        var result = searchNodeMemberTarget(nodeMemberTarget);
        if (result instanceof NodeSearch.Result.TerminalShape terminal && terminal.isIdRef()) {
            return findShape(nodeMemberTarget.view().parseResult(), id.copyIdValue(), model);
        }
        return Optional.empty();
    }

    static Optional<Shape> getShapeReference(IdlPosition idlPosition, DocumentId id, Model model) {
        Optional<Shape> shape = switch (idlPosition) {
            case IdlPosition.TraitValue traitValue -> traitValueReference(traitValue, id, model);

            case IdlPosition.NodeMemberTarget nodeMemberTarget ->
                    nodeMemberTargetReference(nodeMemberTarget, id, model);

            case IdlPosition pos when pos.isRootShapeReference() -> {
                String nameOrId = id.copyIdValue();
                yield findShape(pos.view().parseResult(), nameOrId, model);
            }

            default -> Optional.empty();
        };

        return shape.filter(s -> !s.isMemberShape());
    }

    private static Optional<Shape> traitValueReference(IdlPosition.TraitValue traitValue, DocumentId id, Model model) {
        // Find the shape corresponding to the given traitValue position.
        var searchResult = ShapeSearch.searchTraitValue(traitValue, model);

        // We only care about results that could be shape refs, so trait members
        // or idRefs.
        return switch (searchResult) {
            case NodeSearch.Result.TerminalShape terminal when terminal.isIdRef() -> {
                String nameOrId = id.copyIdValue();
                yield findShape(traitValue.view().parseResult(), nameOrId, model);
            }

            case NodeSearch.Result.ObjectKey objectKey -> {
                if (objectKey.containerShape() instanceof MapShape mapShape) {
                    if (mapShape.getKey().getMemberTrait(model, IdRefTrait.class).isPresent()) {
                        String nameOrId = id.copyIdValue();
                        yield findShape(traitValue.view().parseResult(), nameOrId, model);
                    }
                }
                yield Optional.empty();
            }

            default -> Optional.empty();
        };
    }

    private static Optional<Shape> nodeMemberTargetReference(
            IdlPosition.NodeMemberTarget target,
            DocumentId id,
            Model model
    ) {
        var searchResult = ShapeSearch.searchNodeMemberTarget(target);
        return switch (searchResult) {
            // The cursor is on some node value nested within a member of a service, resource, or operation
            // shape. When this value is supposed to represent a shape id, provide refs for that id.
            case NodeSearch.Result.TerminalShape terminal when terminal.isIdRef() -> {
                String nameOrId = id.copyIdValue();
                yield findShape(target.view().parseResult(), nameOrId, model);
            }

            // The cursor is on some key of a node nested within a member of a service or resource shape.
            // We want to provide refs when the key is a service closure shape rename.
            case NodeSearch.Result.ObjectKey objectKey -> {
                var containerId = objectKey.containerShape().getId();
                if (Builtins.SERVICE_RENAME_ID.equals(containerId)) {
                    yield findShape(target.view().parseResult(), objectKey.key().name(), model);
                } else {
                    yield Optional.empty();
                }
            }
            default -> Optional.empty();
        };
    }

    /**
     * @param forResource The nullable for-resource statement.
     * @param view A statement view containing the for-resource statement.
     * @param model The model to search in.
     * @return A resource shape matching the given for-resource statement, if found.
     */
    static Optional<ResourceShape> findResource(
            Syntax.Statement.ForResource forResource,
            StatementView view,
            Model model
    ) {
        if (forResource != null) {
            String resourceNameOrId = forResource.resource().stringValue();
            return findShape(view.parseResult(), resourceNameOrId, model)
                    .flatMap(Shape::asResourceShape);
        }
        return Optional.empty();
    }

    /**
     * @param mixins The nullable mixins statement.
     * @param view The statement view containing the mixins statement.
     * @param model The model to search in.
     * @return A list of the mixin shapes matching those in the mixin statement.
     */
    static List<Shape> findMixins(Syntax.Statement.Mixins mixins, StatementView view, Model model) {
        if (mixins != null) {
            List<Shape> mixinShapes = new ArrayList<>(mixins.mixins().size());
            for (Syntax.Ident ident : mixins.mixins()) {
                String mixinNameOrId = ident.stringValue();
                findShape(view.parseResult(), mixinNameOrId, model).ifPresent(mixinShapes::add);
            }
            return mixinShapes;
        }
        return List.of();
    }

    /**
     * @param elidedMember The elided member position
     * @param id           The identifier of the elided member
     * @param model        The model to search in
     * @return The shape the elided member comes from, if found.
     */
    static Optional<? extends Shape> findElidedMemberParent(
            IdlPosition.ElidedMember elidedMember,
            DocumentId id,
            Model model
    ) {
        var view = elidedMember.view();
        var forResourceAndMixins = view.nearestForResourceAndMixinsBefore();

        String searchToken = id.copyIdValueForElidedMember();

        // TODO: Handle ambiguity
        Optional<ResourceShape> foundResource = findResource(forResourceAndMixins.forResource(), view, model)
                .filter(shape -> shape.getIdentifiers().containsKey(searchToken)
                                 || shape.getProperties().containsKey(searchToken));
        if (foundResource.isPresent()) {
            return foundResource;
        }

        return findMixins(forResourceAndMixins.mixins(), view, model)
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
        Syntax.Statement.ShapeDef shapeDef = traitValue.view().nearestShapeDefAfter();

        if (shapeDef == null) {
            return Optional.empty();
        }

        String shapeName = shapeDef.shapeName().stringValue();
        return findShape(traitValue.view().parseResult(), shapeName, model);
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
        String metadataKey = metadataValue.metadata().key().stringValue();
        Shape metadataValueShapeDef = Builtins.getMetadataValue(metadataKey);
        if (metadataValueShapeDef == null) {
            return NodeSearch.Result.NONE;
        }

        NodeCursor cursor = NodeCursor.create(
                metadataValue.metadata().value(),
                metadataValue.view().documentIndex());
        var dynamicTargets = DynamicMemberTarget.forMetadata(metadataKey);
        return NodeSearch.search(cursor, Builtins.MODEL, metadataValueShapeDef, dynamicTargets);
    }

    /**
     * @param nodeMemberTarget The node member target position
     * @return The result of searching from the given node member target value
     *  within the {@link Builtins} model.
     */
    static NodeSearch.Result searchNodeMemberTarget(IdlPosition.NodeMemberTarget nodeMemberTarget) {
        Syntax.Statement.ShapeDef shapeDef = nodeMemberTarget.view().nearestShapeDefBefore();

        if (shapeDef == null) {
            return NodeSearch.Result.NONE;
        }

        String shapeType = shapeDef.shapeType().stringValue();
        String memberName = nodeMemberTarget.nodeMember().name().stringValue();
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
        if (nodeMemberTarget.nodeMember().value() == null) {
            return new NodeSearch.Result.TerminalShape(memberShapeDef, null, Builtins.MODEL);
        }

        NodeCursor cursor = NodeCursor.create(
                nodeMemberTarget.nodeMember().value(),
                nodeMemberTarget.view().documentIndex());
        return NodeSearch.search(cursor, Builtins.MODEL, memberShapeDef);
    }

    /**
     * @param traitValue The trait value position
     * @param model The model to search
     * @return The result of searching from {@code traitValue} within {@code model}.
     */
    static NodeSearch.Result searchTraitValue(IdlPosition.TraitValue traitValue, Model model) {
        String traitName = traitValue.application().id().stringValue();
        Optional<Shape> maybeTraitShape = findShape(traitValue.view().parseResult(), traitName, model);
        if (maybeTraitShape.isEmpty()) {
            return NodeSearch.Result.NONE;
        }

        Shape traitShape = maybeTraitShape.get();
        NodeCursor cursor = NodeCursor.create(
                traitValue.application().value(),
                traitValue.view().documentIndex());
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
