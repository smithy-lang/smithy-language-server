/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.Map;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.lsp.syntax.NodeCursor;
import software.amazon.smithy.lsp.syntax.Syntax;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;

/**
 * An abstraction to allow computing the target of a member dynamically, instead
 * of just using what's in the model, when traversing a model using a
 * {@link NodeCursor}.
 *
 * <p>For example, the examples trait has two members, input and output, whose
 * values are represented by the target operation's input and output shapes,
 * respectively. In the model however, these members just target Document shapes,
 * because we don't have a way to directly model the relationship. It would be
 * really useful for customers to get e.g. completions despite that, which is the
 * purpose of this interface.</p>
 *
 * @implNote One of the ideas behind this is that you should not have to pay for
 * computing the member target unless necessary.
 */
sealed interface DynamicMemberTarget {
    /**
     * @param cursor The cursor being used to traverse the model.
     * @param model The model being traversed.
     * @return The target of the member shape at the cursor's current position.
     */
    Shape getTarget(NodeCursor cursor, Model model);

    static Map<ShapeId, DynamicMemberTarget> forTrait(Shape traitShape, IdlPosition.TraitValue traitValue) {
        SmithyFile smithyFile = traitValue.smithyFile();
        return switch (traitShape.getId().toString()) {
            case "smithy.test#smokeTests" -> Map.of(
                    ShapeId.from("smithy.test#SmokeTestCase$params"),
                        new OperationInput(traitValue),
                    ShapeId.from("smithy.test#SmokeTestCase$vendorParams"),
                        new ShapeIdDependent("vendorParamsShape", smithyFile));

            case "smithy.api#examples" -> Map.of(
                    ShapeId.from("smithy.api#Example$input"),
                        new OperationInput(traitValue),
                    ShapeId.from("smithy.api#Example$output"),
                        new OperationOutput(traitValue));

            case "smithy.test#httpRequestTests" -> Map.of(
                    ShapeId.from("smithy.test#HttpRequestTestCase$params"),
                        new OperationInput(traitValue),
                    ShapeId.from("smithy.test#HttpRequestTestCase$vendorParams"),
                        new ShapeIdDependent("vendorParamsShape", smithyFile));

            case "smithy.test#httpResponseTests" -> Map.of(
                    ShapeId.from("smithy.test#HttpResponseTestCase$params"),
                        new OperationOutput(traitValue),
                    ShapeId.from("smithy.test#HttpResponseTestCase$vendorParams"),
                        new ShapeIdDependent("vendorParamsShape", smithyFile));

            default -> null;
        };
    }

    static Map<ShapeId, DynamicMemberTarget> forMetadata(String metadataKey, SmithyFile smithyFile) {
        return switch (metadataKey) {
            case "validators" -> Map.of(
                    ShapeId.from("smithy.lang.server#Validator$configuration"), new MappedDependent(
                            "name",
                            smithyFile.document(),
                            Builtins.VALIDATOR_CONFIG_MAPPING));
            default -> null;
        };
    }

    /**
     * Computes the input shape of the operation targeted by {@code traitValue},
     * to use as the member target.
     *
     * @param traitValue The position, in the applied trait value.
     */
    record OperationInput(IdlPosition.TraitValue traitValue) implements DynamicMemberTarget {
        @Override
        public Shape getTarget(NodeCursor cursor, Model model) {
            return ShapeSearch.findTraitTarget(traitValue, model)
                    .flatMap(Shape::asOperationShape)
                    .flatMap(operationShape -> model.getShape(operationShape.getInputShape()))
                    .orElse(null);
        }
    }

    /**
     * Computes the output shape of the operation targeted by {@code traitValue},
     * to use as the member target.
     *
     * @param traitValue The position, in the applied trait value.
     */
    record OperationOutput(IdlPosition.TraitValue traitValue) implements DynamicMemberTarget {
        @Override
        public Shape getTarget(NodeCursor cursor, Model model) {
            return ShapeSearch.findTraitTarget(traitValue, model)
                    .flatMap(Shape::asOperationShape)
                    .flatMap(operationShape -> model.getShape(operationShape.getOutputShape()))
                    .orElse(null);
        }
    }

    /**
     * Computes the value of another member in the node, {@code memberName},
     * using that as the id of the target shape.
     *
     * @param memberName The name of the other member to compute the value of.
     * @param smithyFile The file the node is within.
     */
    record ShapeIdDependent(String memberName, SmithyFile smithyFile) implements DynamicMemberTarget {
        @Override
        public Shape getTarget(NodeCursor cursor, Model model) {
            Syntax.Node.Kvp matchingKvp = findMatchingKvp(memberName, cursor, smithyFile.document());
            if (matchingKvp.value() instanceof Syntax.Node.Str str) {
                String id = str.copyValueFrom(smithyFile.document());
                return ShapeSearch.findShape(smithyFile, model, id).orElse(null);
            }
            return null;
        }
    }

    /**
     * Computes the value of another member in the node, {@code memberName},
     * and looks up the id of the target shape from {@code mapping} using that
     * value.
     *
     * @param memberName The name of the member to compute the value of.
     * @param document The document the node is within.
     * @param mapping A mapping of {@code memberName} values to corresponding
     *                member target ids.
     */
    record MappedDependent(String memberName, Document document, Map<String, ShapeId> mapping)
            implements DynamicMemberTarget {
        @Override
        public Shape getTarget(NodeCursor cursor, Model model) {
            Syntax.Node.Kvp matchingKvp = findMatchingKvp(memberName, cursor, document);
            if (matchingKvp.value() instanceof Syntax.Node.Str str) {
                String value = str.copyValueFrom(document);
                ShapeId targetId = mapping.get(value);
                if (targetId != null) {
                    return model.getShape(targetId).orElse(null);
                }
            }
            return null;
        }
    }

    // Note: This is suboptimal in isolation, but it should be called rarely in
    // comparison to parsing or NodeCursor construction, which are optimized for
    // speed and memory usage (instead of key lookup), and the number of keys
    // is assumed to be low in most cases.
    private static Syntax.Node.Kvp findMatchingKvp(String keyName, NodeCursor cursor, Document document) {
        // This will be called after skipping a ValueForKey, so that will be previous
        if (!cursor.hasPrevious()) {
            // TODO: Log
            return null;
        }
        NodeCursor.Edge edge = cursor.previous();
        if (edge instanceof NodeCursor.ValueForKey(var ignored, Syntax.Node.Kvps parent)) {
            for (Syntax.Node.Kvp kvp : parent.kvps()) {
                String key = kvp.key().copyValueFrom(document);
                if (!keyName.equals(key)) {
                    continue;
                }

                return kvp;
            }
        }
        return null;
    }
}
