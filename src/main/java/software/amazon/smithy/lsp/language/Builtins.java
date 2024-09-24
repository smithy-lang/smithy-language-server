/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;

/**
 * Provides access to a Smithy model used to model various builtin constructs
 * of the Smithy language, such as metadata validators.
 *
 * <p>As a modeling language, Smithy is, unsurprisingly, good at modeling stuff.
 * Instead of building a whole separate abstraction to provide completions and
 * hover information for stuff like metadata validators, the language server uses
 * a Smithy model for the structure and documentation. This means we can re-use the
 * same mechanisms of model/node-traversal we do for regular models.</p>
 *
 * <p>See the Smithy model for docs on the specific shapes.</p>
 */
final class Builtins {
    static final String NAMESPACE = "smithy.lang.server";

    static final Model MODEL = Model.assembler()
            .disableValidation()
            .addImport(Builtins.class.getResource("builtins.smithy"))
            .addImport(Builtins.class.getResource("control.smithy"))
            .addImport(Builtins.class.getResource("metadata.smithy"))
            .addImport(Builtins.class.getResource("members.smithy"))
            .assemble()
            .unwrap();

    static final Map<ShapeId, BuiltinShape> BUILTIN_SHAPES = Arrays.stream(BuiltinShape.values())
            .collect(Collectors.toMap(
                    builtinShape -> id(builtinShape.name()),
                    builtinShape -> builtinShape));

    static final Shape CONTROL = MODEL.expectShape(id("BuiltinControl"));

    static final Shape METADATA = MODEL.expectShape(id("BuiltinMetadata"));

    static final Shape VALIDATORS = MODEL.expectShape(id("BuiltinValidators"));

    static final Shape SHAPE_MEMBER_TARGETS = MODEL.expectShape(id("ShapeMemberTargets"));

    static final Map<String, ShapeId> VALIDATOR_CONFIG_MAPPING = VALIDATORS.members().stream()
            .collect(Collectors.toMap(
                    MemberShape::getMemberName,
                    memberShape -> memberShape.getTarget()));

    private Builtins() {
    }

    /**
     * Shapes in the builtin model that require some custom processing by consumers.
     *
     * <p>Some values are special - they don't correspond to a specific shape type,
     * can't be represented by a Smithy model, or have some known constraints that
     * aren't as efficient to model. These values get their own dedicated shape in
     * the builtin model, corresponding to the names of this enum.</p>
     */
    enum BuiltinShape {
        SmithyIdlVersion,
        AnyNamespace,
        ValidatorName,
        AnyShape,
        AnyTrait,
        AnyMixin,
        AnyString,
        AnyError,
        AnyOperation,
        AnyResource,
        AnyMemberTarget
    }

    static Shape getMetadataValue(String metadataKey) {
        return METADATA.getMember(metadataKey)
                .map(memberShape -> MODEL.expectShape(memberShape.getTarget()))
                .orElse(null);
    }

    static StructureShape getMembersForShapeType(String shapeType) {
        return SHAPE_MEMBER_TARGETS.getMember(shapeType)
                .map(memberShape -> MODEL.expectShape(memberShape.getTarget(), StructureShape.class))
                .orElse(null);
    }

    static Shape getMemberTargetForShapeType(String shapeType, String memberName) {
        StructureShape memberTargets = getMembersForShapeType(shapeType);
        if (memberTargets == null) {
            return null;
        }

        return memberTargets.getMember(memberName)
                .map(memberShape -> MODEL.expectShape(memberShape.getTarget()))
                .orElse(null);
    }

    private static ShapeId id(String name) {
        return ShapeId.fromParts(NAMESPACE, name);
    }
}
