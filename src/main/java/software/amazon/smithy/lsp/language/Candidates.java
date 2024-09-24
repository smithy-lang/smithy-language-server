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
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.DefaultTrait;
import software.amazon.smithy.model.traits.IdRefTrait;

/**
 * Candidates for code-completions.
 *
 * <p>There are different kinds of completion candidates, each of which may
 * need to be represented differently, filtered, and/or mapped to IDE-specific
 * data structures in their own way.</p>
 */
sealed interface Candidates {
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
    // TODO: Maybe BUILTIN_CONTROLS and BUILTIN_METADATA should be regular
    //  Labeled/Members, with custom mappers.
    Literals BUILTIN_CONTROLS = new Candidates.Literals(
            Builtins.CONTROL.members().stream()
                    .map(member -> "$" + member.getMemberName() + ": " + Candidates.defaultCandidates(member).value())
                    .toList());
    Literals BUILTIN_METADATA = new Candidates.Literals(
            Builtins.METADATA.members().stream()
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
    static Candidates.Constant defaultCandidates(Shape shape) {
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
    static Candidates fromSearchResult(NodeSearch.Result result) {
        return switch (result) {
            case NodeSearch.Result.TerminalShape(Shape shape, var ignored) ->
                    terminalCandidates(shape);

            case NodeSearch.Result.ObjectKey(var ignored, Shape shape, Model model) ->
                    membersCandidates(model, shape);

            case NodeSearch.Result.ObjectShape(var ignored, Shape shape, Model model) ->
                    membersCandidates(model, shape);

            case NodeSearch.Result.ArrayShape(var ignored, ListShape shape, Model model) ->
                    model.getShape(shape.getMember().getTarget())
                            .map(Candidates::terminalCandidates)
                            .orElse(NONE);

            default -> NONE;
        };
    }

    /**
     * @param model The model that {@code shape} is a part of.
     * @param shape The shape to get member candidates for.
     * @return If a struct or union shape, returns {@link Members} candidates.
     *  Otherwise, {@link #NONE}.
     */
    static Candidates membersCandidates(Model model, Shape shape) {
        if (shape.isStructureShape() || shape.isUnionShape()) {
            return new Members(shape.getAllMembers().entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> model.getShape(entry.getValue().getTarget())
                                        .map(Candidates::defaultCandidates)
                                        .orElse(NONE))));
        } else if (shape instanceof MapShape mapShape) {
            EnumShape enumKey = model.getShape(mapShape.getKey().getTarget())
                    .flatMap(Shape::asEnumShape)
                    .orElse(null);
            if (enumKey != null) {
                return terminalCandidates(enumKey);
            }
        }
        return NONE;
    }

    private static Candidates terminalCandidates(Shape shape) {
        Builtins.BuiltinShape builtinShape = Builtins.BUILTIN_SHAPES.get(shape.getId());
        if (builtinShape != null) {
            return forBuiltin(builtinShape);
        }

        return switch (shape) {
            case EnumShape enumShape -> new Labeled(enumShape.getEnumValues()
                    .entrySet()
                    .stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> "\"" + entry.getValue() + "\"")));

            case IntEnumShape intEnumShape -> new Labeled(intEnumShape.getEnumValues()
                    .entrySet()
                    .stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().toString())));

            case Shape s when s.hasTrait(IdRefTrait.class) -> Shapes.ANY_SHAPE;

            case Shape s when s.isBooleanShape() -> BOOL;

            default -> defaultCandidates(shape);
        };
    }

    private static Candidates forBuiltin(Builtins.BuiltinShape builtinShape) {
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
    record Constant(String value) implements Candidates {}

    /**
     * Multiple values to be completed as literals, like keywords.
     *
     * @param literals The completion values.
     */
    record Literals(List<String> literals) implements Candidates {}

    /**
     * Multiple label -> value pairs, where the label is displayed to the user,
     * and may be used for matching, and the value is the literal text to complete.
     *
     * @param labeled The labeled completion values.
     */
    record Labeled(Map<String, String> labeled) implements Candidates {}

    /**
     * Multiple name -> constant pairs, where the name corresponds to a member
     * name, and the constant is a default/empty value for that member.
     *
     * @param members The members completion values.
     */
    record Members(Map<String, Candidates.Constant> members) implements Candidates {}

    /**
     * Multiple member names to complete as elided members.
     * @apiNote These are distinct from {@link Literals} because they may have
     * custom filtering/mapping, and may appear _with_ {@link Literals} in an
     * {@link And}.
     *
     * @param memberNames The member names completion values.
     */
    record ElidedMembers(Collection<String> memberNames) implements Candidates {}

    /**
     * A combination of two sets of completion candidates, of possibly different
     * types.
     *
     * @param one The first set of completion candidates.
     * @param two The second set of completion candidates.
     */
    record And(Candidates one, Candidates two) implements Candidates {}

    /**
     * Shape completion candidates, each corresponding to a different set of
     * shapes that will be selected from the model.
     */
    enum Shapes implements Candidates {
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
    enum Custom implements Candidates {
        NAMESPACE_FILTER,
        VALIDATOR_NAME,
        PROJECT_NAMESPACES,
    }
}
