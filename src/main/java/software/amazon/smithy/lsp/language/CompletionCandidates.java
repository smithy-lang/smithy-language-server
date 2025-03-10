/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import software.amazon.smithy.lsp.util.StreamUtils;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.EnumShape;
import software.amazon.smithy.model.shapes.IntEnumShape;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.DefaultTrait;
import software.amazon.smithy.model.traits.IdRefTrait;

/**
 * Candidates for code completions.
 *
 * <p>There are different kinds of completion candidates, each of which may
 * need to be represented differently, filtered, and/or mapped to IDE-specific
 * data structures in their own way.</p>
 */
sealed interface CompletionCandidates {
    Constant NONE = new Constant("");
    Constant EMPTY_STRING = new Constant("\"\"");
    Constant EMPTY_OBJ = new Constant("{}");
    Constant EMPTY_ARR = new Constant("[]");
    Literals BOOL = new Literals(List.of("true", "false"));
    Literals KEYWORD = new Literals(List.of(
            "metadata", "namespace", "use",
            "blob", "boolean", "string", "byte", "short", "integer", "long", "float", "double",
            "bigInteger", "bigDecimal", "timestamp", "document", "enum", "intEnum",
            "list", "map", "structure", "union",
            "service", "resource", "operation",
            "apply"));
    Literals BUILTIN_CONTROLS = new Literals(Builtins.CONTROL.members().stream()
            .map(member -> "$" + member.getMemberName() + ": " + defaultCandidates(member).value())
            .toList());
    Literals BUILTIN_METADATA = new Literals(Builtins.METADATA.members().stream()
            .map(member -> member.getMemberName() + " = []")
            .toList());
    Labeled SMITHY_IDL_VERSION = new Labeled(Stream.of("1.0", "2.0")
            .collect(StreamUtils.toWrappedMap()));
    Labeled VALIDATOR_NAMES = new Labeled(Builtins.VALIDATOR_CONFIG_MAPPING.keySet().stream()
            .collect(StreamUtils.toWrappedMap()));

    /**
     * @apiNote This purposefully does not handle {@link software.amazon.smithy.lsp.language.Builtins.BuiltinShape}
     * as it is meant to be used for member target default values.
     *
     * @param shape The shape to get candidates for.
     * @return A constant value corresponding to the 'default' or 'empty' value
     *  of a shape.
     */
    static Constant defaultCandidates(Shape shape) {
        if (shape.hasTrait(DefaultTrait.class)) {
            DefaultTrait defaultTrait = shape.expectTrait(DefaultTrait.class);
            return new Constant(Node.printJson(defaultTrait.toNode()));
        }

        if (shape.isBlobShape() || (shape.isStringShape() && !shape.hasTrait(IdRefTrait.class))) {
            return EMPTY_STRING;
        } else if (ShapeSearch.isObjectShape(shape)) {
            return EMPTY_OBJ;
        } else if (shape.isListShape()) {
            return EMPTY_ARR;
        } else {
            return NONE;
        }
    }

    /**
     * @param result The search result to get candidates from.
     * @return The completion candidates for {@code result}.
     */
    static CompletionCandidates fromSearchResult(NodeSearch.Result result) {
        return switch (result) {
            case NodeSearch.Result.TerminalShape(Shape shape, MemberShape targetOf, var ignored) ->
                    terminalCandidates(shape, targetOf);

            case NodeSearch.Result.ObjectKey(var ignored, Shape shape, Model model) ->
                    membersCandidates(model, shape);

            case NodeSearch.Result.ObjectShape(var ignored, Shape shape, Model model) ->
                    membersCandidates(model, shape);

            case NodeSearch.Result.ArrayShape(var ignored, ListShape shape, Model model) ->
                    model.getShape(shape.getMember().getTarget())
                            .map(target -> terminalCandidates(target, shape.getMember()))
                            .orElse(NONE);

            default -> NONE;
        };
    }

    /**
     * @param idlPosition The position in the idl to get candidates for.
     * @return The candidates for shape completions.
     */
    static CompletionCandidates shapeCandidates(IdlPosition idlPosition) {
        return switch (idlPosition) {
            case IdlPosition.UseTarget ignored -> Shapes.USE_TARGET;
            case IdlPosition.TraitId ignored -> Shapes.TRAITS;
            case IdlPosition.Mixin ignored -> Shapes.MIXINS;
            case IdlPosition.ForResource ignored -> Shapes.RESOURCE_SHAPES;
            case IdlPosition.MemberTarget ignored -> Shapes.MEMBER_TARGETABLE;
            case IdlPosition.ApplyTarget ignored -> Shapes.ANY_SHAPE;
            case IdlPosition.NodeMemberTarget nodeMemberTarget -> fromSearchResult(
                    ShapeSearch.searchNodeMemberTarget(nodeMemberTarget));
            default -> CompletionCandidates.NONE;
        };
    }

    /**
     * @param model The model that {@code shape} is a part of.
     * @param shape The shape to get member candidates for.
     * @return If a struct or union shape, returns {@link Members} candidates.
     *  Otherwise, {@link #NONE}.
     */
    static CompletionCandidates membersCandidates(Model model, Shape shape) {
        if (shape.isStructureShape() || shape.isUnionShape()) {
            return new Members(shape.getAllMembers().entrySet().stream()
                    .collect(StreamUtils.mappingValue(member -> model.getShape(member.getTarget())
                            .map(CompletionCandidates::defaultCandidates)
                            .orElse(NONE))));
        } else if (shape instanceof MapShape mapShape) {
            return model.getShape(mapShape.getKey().getTarget())
                    .flatMap(Shape::asEnumShape)
                    .map(CompletionCandidates::enumCandidates)
                    .orElse(NONE);
        }
        return NONE;
    }

    private static CompletionCandidates terminalCandidates(Shape shape, MemberShape targetOf) {
        Builtins.BuiltinShape builtinShape = Builtins.BUILTIN_SHAPES.get(shape.getId());
        if (builtinShape != null) {
            return forBuiltin(builtinShape);
        }

        if (isIdRef(shape, targetOf)) {
            return Shapes.ANY_SHAPE;
        }

        return switch (shape) {
            case EnumShape enumShape -> enumCandidates(enumShape);

            case IntEnumShape intEnumShape -> new Labeled(intEnumShape.getEnumValues()
                    .entrySet()
                    .stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().toString())));

            case Shape s when s.isBooleanShape() -> BOOL;

            default -> defaultCandidates(shape);
        };
    }

    private static CompletionCandidates enumCandidates(EnumShape enumShape) {
        return new Labeled(enumShape.getEnumValues()
                .entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> "\"" + entry.getValue() + "\"")));
    }

    private static boolean isIdRef(Shape shape, MemberShape targetOf) {
        return shape.hasTrait(IdRefTrait.class)
                || (targetOf != null && targetOf.hasTrait(IdRefTrait.class));
    }

    private static CompletionCandidates forBuiltin(Builtins.BuiltinShape builtinShape) {
        return switch (builtinShape) {
            case SmithyIdlVersion -> SMITHY_IDL_VERSION;
            case AnyNamespace -> Custom.NAMESPACE_FILTER;
            case ValidatorName -> Custom.VALIDATOR_NAME;
            case AnyShape -> Shapes.ANY_SHAPE;
            case AnyTrait -> Shapes.TRAITS;
            case AnyMixin -> Shapes.MIXINS;
            case AnyString -> Shapes.STRING_SHAPES;
            case AnyError -> Shapes.ERROR_SHAPES;
            case AnyOperation -> Shapes.OPERATION_SHAPES;
            case AnyResource -> Shapes.RESOURCE_SHAPES;
            case AnyMemberTarget -> Shapes.MEMBER_TARGETABLE;
        };
    }

    /**
     * A single, constant-value completion, like an empty string, for example.
     *
     * @param value The completion value.
     */
    record Constant(String value) implements CompletionCandidates {}

    /**
     * Multiple values to be completed as literals, like keywords.
     *
     * @param literals The completion values.
     */
    record Literals(List<String> literals) implements CompletionCandidates {}

    /**
     * Multiple label -> value pairs, where the label is displayed to the user,
     * and may be used for matching, and the value is the literal text to complete.
     *
     * <p>For example, completing enum value in a trait may display and match on the
     * name, like FOO, but complete the actual value, like "foo".
     *
     * @param labeled The labeled completion values.
     */
    record Labeled(Map<String, String> labeled) implements CompletionCandidates {}

    /**
     * Multiple name -> constant pairs, where the name corresponds to a member
     * name, and the constant is a default/empty value for that member.
     *
     * <p>For example, shape members can be completed as {@code name: constant}.
     *
     * @param members The members completion values.
     */
    record Members(Map<String, Constant> members) implements CompletionCandidates {}

    /**
     * Multiple member names to complete as elided members.
     *
     * @apiNote These are distinct from {@link Literals} because they may have
     * custom filtering/mapping, and may appear _with_ {@link Literals} in an
     * {@link And}.
     *
     * @param memberNames The member names completion values.
     */
    record ElidedMembers(Collection<String> memberNames) implements CompletionCandidates {}

    /**
     * A combination of two sets of completion candidates, of possibly different
     * types.
     *
     * @param one The first set of completion candidates.
     * @param two The second set of completion candidates.
     */
    record And(CompletionCandidates one, CompletionCandidates two) implements CompletionCandidates {}

    /**
     * Shape completion candidates, each corresponding to a different set of
     * shapes that will be selected from the model.
     */
    enum Shapes implements CompletionCandidates {
        ANY_SHAPE,
        USE_TARGET,
        TRAITS,
        MIXINS,
        STRING_SHAPES,
        ERROR_SHAPES,
        RESOURCE_SHAPES,
        OPERATION_SHAPES,
        MEMBER_TARGETABLE
    }

    /**
     * Candidates that require a custom computation to generate, lazily.
     */
    enum Custom implements CompletionCandidates {
        NAMESPACE_FILTER,
        VALIDATOR_NAME,
        PROJECT_NAMESPACES,
    }
}
