/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import software.amazon.smithy.lsp.project.IdlFile;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.lsp.syntax.StatementView;
import software.amazon.smithy.lsp.syntax.Syntax;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.IdRefTrait;

/**
 * Collects references to a shape across a project or within a specific file.
 */
final class References {
    private final Model model;
    private final Shape shape;
    private final ShapeReferencesNodeWalker traitValueWalker;
    private final ShapeReferencesNodeWalker nodeMemberWalker;
    private final List<FileReferences> fileReferences = new ArrayList<>();
    private final List<DefinitionReference> definitionReferences = new ArrayList<>();
    private List<Syntax.Statement.Use> pendingUseRefs = new ArrayList<>();
    private List<Syntax.Node.Str> pendingRefs = new ArrayList<>();
    private IdlFile currentFile;
    private Syntax.IdlParseResult currentParseResult;

    private References(Model model, Shape shape) {
        this.model = model;
        this.shape = shape;
        this.traitValueWalker = new ShapeReferencesNodeWalker(model, this);
        this.nodeMemberWalker = new ShapeReferencesNodeWalker(Builtins.MODEL, this);
    }

    /**
     * References to a shape in a specific file, excluding the shape's definition.
     *
     * @param idlFile The file the references are in
     * @param useRefs The references in use statements
     * @param refs All other references in the file
     */
    record FileReferences(IdlFile idlFile, List<Syntax.Statement.Use> useRefs, List<Syntax.Node.Str> refs) {}

    /**
     * The definition of a shape.
     *
     * @param idlFile The file the shape is defined in
     * @param ref The shape's name token
     */
    record DefinitionReference(IdlFile idlFile, Syntax.Node.Str ref) {}

    /**
     * Finds all references to {@code shape} across all files in the given {@code project}.
     *
     * @param model The model the shape is in
     * @param shape The shape to find references to
     * @param project The project to find references in
     * @return All found references, including the shape's definition
     */
    static References findReferences(Model model, Shape shape, Project project) {
        var references = new References(model, shape);
        references.findReferences(project);
        return references;
    }

    /**
     * Finds all references to {@code shape} in the given {@code idlFile}.
     *
     * @param model The model the shape is in
     * @param shape The shape to find references to
     * @param idlFile The file to find references in
     * @return All found references, not including the shape's definition
     */
    static References findReferences(Model model, Shape shape, IdlFile idlFile) {
        var references = new References(model, shape);
        references.findReferences(idlFile);
        return references;
    }

    /**
     * @return A list of all found references
     */
    List<FileReferences> fileReferences() {
        return fileReferences;
    }

    /**
     * @return A list of all found definitions
     */
    List<DefinitionReference> definitionReferences() {
        return definitionReferences;
    }

    private void addPendingReferences() {
        // Don't create a new FileReferences when there weren't any refs in
        // the file.
        if (!pendingUseRefs.isEmpty() || !pendingRefs.isEmpty()) {
            fileReferences.add(new FileReferences(
                    currentFile,
                    pendingUseRefs,
                    pendingRefs
            ));

            // Create a fresh pending list so subsequent modifications don't mutate a
            // list in the FileReferences we just made.
            pendingUseRefs = new ArrayList<>();
            pendingRefs = new ArrayList<>();
        }
    }

    private void findReferences(Project project) {
        for (SmithyFile smithyFile : project.getAllSmithyFiles()) {
            if (!(smithyFile instanceof IdlFile idlFile)) {
                continue;
            }

            findReferences(idlFile);
        }

        // Include the shape's definition, which won't be collected otherwise.
        // Note: This doesn't add the definition of an inline shape, because it
        //  doesn't have an identifier to ref.
        addDefinitionReference(project);
    }

    private void findReferences(IdlFile idlFile) {
        currentFile = idlFile;
        currentParseResult = idlFile.getParse();

        for (Syntax.Statement statement : currentParseResult.statements()) {
            collect(statement);
        }

        addPendingReferences();
    }

    private void collect(Syntax.Statement statement) {
        switch (statement) {
            case Syntax.Statement.Use use -> {
                if (use.use().stringValue().equals(shape.getId().toString())) {
                    pendingUseRefs.add(use);
                }
            }

            case Syntax.Statement.Mixins mixins -> {
                for (var mixin : mixins.mixins()) {
                    addShapeReference(mixin);
                }
            }

            case Syntax.Statement.ForResource forResource -> addShapeReference(forResource.resource());

            case Syntax.Statement.MemberDef memberDef -> {
                if (memberDef.target() != null) {
                    addShapeReference(memberDef.target());
                }
            }

            case Syntax.Statement.TraitApplication traitApplication -> collectTrait(traitApplication);

            case Syntax.Statement.NodeMemberDef nodeMemberDef -> collectNodeMember(nodeMemberDef);

            default -> {
            }
        }
    }

    private void collectTrait(Syntax.Statement.TraitApplication traitApplication) {
        findShape(traitApplication.id().stringValue()).ifPresent(traitShape -> {
            if (traitShape.getId().equals(shape.getId())) {
                pendingRefs.add(traitApplication.id());
            }
            traitValueWalker.walk(traitApplication.value(), traitShape);
        });
    }

    private void collectNodeMember(Syntax.Statement.NodeMemberDef nodeMemberDef) {
        createView(nodeMemberDef)
                .map(StatementView::nearestShapeDefBefore)
                .map(shapeDef -> Builtins.getMemberTargetForShapeType(
                        shapeDef.shapeType().stringValue(),
                        nodeMemberDef.name().stringValue()))
                .ifPresent(memberTarget -> nodeMemberWalker.walk(nodeMemberDef.value(), memberTarget));
    }

    private boolean startsWithId(Shape s) {
        return s.getId().getNamespace().equals(shape.getId().getNamespace())
               && s.getId().getName().equals(shape.getId().getName());
    }

    private void addShapeReference(Syntax.Node.Str token) {
        if (findShape(token.stringValue())
                .filter(s -> s.getId().equals(shape.getId()))
                .isPresent()) {
            pendingRefs.add(token);
        }
    }

    private Optional<Shape> findShape(String nameOrId) {
        return ShapeSearch.findShape(currentParseResult, nameOrId, model);
    }

    private Optional<StatementView> createView(Syntax.Statement statement) {
        return StatementView.createAt(currentParseResult, statement);
    }

    private void addDefinitionReference(Project project) {
        var sourceLocation = shape.getSourceLocation();
        var projectFile = project.getProjectFile(LspAdapter.toUri(sourceLocation.getFilename()));
        if (!(projectFile instanceof IdlFile idl)) {
            return;
        }

        var parseResult = idl.getParse();
        int documentIndex = idl.document().indexOfPosition(LspAdapter.toPosition(sourceLocation));
        var statement = StatementView.createAt(parseResult, documentIndex)
                .map(StatementView::getStatement)
                .orElse(null);
        if (statement instanceof Syntax.Statement.ShapeDef shapeDef) {
            definitionReferences.add(new DefinitionReference(idl, shapeDef.shapeName()));
        }
    }

    private void addIdRef(Syntax.Node.Str id) {
        if (findShape(id.stringValue())
                .filter(this::startsWithId)
                .isPresent()) {
            pendingRefs.add(id);
        }
    }

    /**
     * Walks a {@link Syntax.Node}, whose structure is defined by a {@link Shape},
     * to find all references to shapes in that node.
     *
     * @param model The model with the shape defining the node's structure
     * @param references The references to consume found shape references
     *
     * @implNote This is very similar to {@link NodeSearch}, but walks all children
     *  instead of along a specific path, and only looks for shapes with {@code idRef}.
     *  It also doesn't use {@link DynamicMemberTarget}s, as right now there aren't
     *  any cases where a dynamic member target could provide shape references.
     */
    private record ShapeReferencesNodeWalker(Model model, References references) {
        private void walk(Syntax.Node node, Shape nodeShape) {
            if (nodeShape == null) {
                return;
            }

            switch (node) {
                case Syntax.Node.Obj obj -> walk(obj.kvps(), nodeShape);

                case Syntax.Node.Kvps kvps -> walkKvps(kvps, nodeShape);

                case Syntax.Node.Arr arr -> walkArr(arr, nodeShape);

                case Syntax.Node.Str str -> {
                    if (nodeShape.hasTrait(IdRefTrait.class)) {
                        references.addIdRef(str);
                    }
                }

                case null, default -> {
                }
            }
        }

        private void walkArr(Syntax.Node.Arr arr, Shape nodeShape) {
            if (!(nodeShape instanceof ListShape listShape)) {
                return;
            }

            if (listShape.getMember().hasTrait(IdRefTrait.ID)) {
                for (var elem : arr.elements()) {
                    walkIdRef(elem);
                }
            } else {
                var target = getTarget(listShape.getMember());
                if (target == null) {
                    return;
                }
                for (var elem : arr.elements()) {
                    walk(elem, target);
                }
            }
        }

        private void walkKvps(Syntax.Node.Kvps kvps, Shape nodeShape) {
            if (!ShapeSearch.isObjectShape(nodeShape)) {
                return;
            }

            if (nodeShape instanceof MapShape mapShape) {
                walkMap(kvps, mapShape);
            } else {
                walkAggregate(kvps, nodeShape);
            }
        }

        private void walkMap(Syntax.Node.Kvps kvps, MapShape mapShape) {
            var keyMember = mapShape.getKey();
            var valueMember = mapShape.getValue();

            boolean keyHasIdRef = keyMember.hasTrait(IdRefTrait.ID);
            boolean valueHasIdRef = valueMember.hasTrait(IdRefTrait.ID);

            if (keyHasIdRef && valueHasIdRef) {
                for (var kvp : kvps.kvps()) {
                    walkIdRef(kvp.key());
                    walkIdRef(kvp.value());
                }
            } else if (keyHasIdRef) {
                var valueTarget = model.getShape(mapShape.getValue().getTarget()).orElse(null);
                for (var kvp : kvps.kvps()) {
                    walkIdRef(kvp.key());
                    walk(kvp.value(), valueTarget);
                }
            } else if (valueHasIdRef) {
                var keyTarget = model.getShape(mapShape.getKey().getTarget()).orElse(null);
                for (var kvp : kvps.kvps()) {
                    walk(kvp.key(), keyTarget);
                    walkIdRef(kvp.value());
                }
            } else {
                var keyTarget = getTarget(keyMember);
                var valueTarget = getTarget(valueMember);
                for (var kvp : kvps.kvps()) {
                    walk(kvp.key(), keyTarget);
                    walk(kvp.value(), valueTarget);
                }
            }
        }

        private void walkAggregate(Syntax.Node.Kvps kvps, Shape nodeShape) {
            for (var kvp : kvps.kvps()) {
                var member = nodeShape.getMember(kvp.key().stringValue()).orElse(null);
                if (member == null) {
                    continue;
                }

                if (member.hasTrait(IdRefTrait.ID)) {
                    walkIdRef(kvp.value());
                } else {
                    var target = getTarget(member);
                    walk(kvp.value(), target);
                }
            }
        }

        private void walkIdRef(Syntax.Node node) {
            if (node instanceof Syntax.Node.Str str) {
                references.addIdRef(str);
            }
        }

        private Shape getTarget(MemberShape member) {
            return model.getShape(member.getTarget()).orElse(null);
        }
    }
}
