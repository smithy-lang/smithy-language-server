/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import software.amazon.smithy.lsp.syntax.NodeCursor;
import software.amazon.smithy.lsp.syntax.Syntax;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;

/**
 * Searches models along the path of {@link NodeCursor}s, with support for
 * dynamically computing member targets via {@link DynamicMemberTarget}.
 */
final class NodeSearch {
    private NodeSearch() {
    }

    /**
     * @param cursor The cursor to search along.
     * @param model The model to search within.
     * @param startingShape The shape to start the search at.
     * @return The search result.
     */
    static Result search(NodeCursor cursor, Model model, Shape startingShape) {
        return new DefaultSearch(model).search(cursor, startingShape);
    }

    /**
     * @param cursor The cursor to search along.
     * @param model The model to search within.
     * @param startingShape The shape to start the search at.
     * @param dynamicMemberTargets A map of member shape id to dynamic member
     *                             targets to use for the search.
     * @return The search result.
     */
    static Result search(
            NodeCursor cursor,
            Model model,
            Shape startingShape,
            Map<ShapeId, DynamicMemberTarget> dynamicMemberTargets
    ) {
        if (dynamicMemberTargets == null || dynamicMemberTargets.isEmpty()) {
            return search(cursor, model, startingShape);
        }

        return new SearchWithDynamicMemberTargets(model, dynamicMemberTargets).search(cursor, startingShape);
    }

    /**
     * The different types of results of a search. The result will be {@link None}
     * if at any point the cursor doesn't line up with the model (i.e. if the
     * cursor was an array edge, but in the model we were at a structure shape).
     *
     * @apiNote Each result type, besides {@link None}, also includes the model,
     * because it may be necessary to interpret the results (i.e. if you need
     * member targets). This is done so that other APIs can wrap {@link NodeSearch}
     * and callers don't have to know about which model was used in the search
     * under the hood, or to allow switching the model if necessary during a search.
     */
    sealed interface Result {
        None NONE = new None();

        /**
         * @return The string values of other keys in {@link ObjectKey} and {@link ObjectShape},
         *  or an empty set.
         */
        default Set<String> getOtherPresentKeys() {
            Syntax.Node.Kvps terminalContainer;
            NodeCursor.Key terminalKey;
            switch (this) {
                case NodeSearch.Result.ObjectShape obj -> {
                    terminalContainer = obj.node();
                    terminalKey = null;
                }
                case NodeSearch.Result.ObjectKey key -> {
                    terminalContainer = key.key().parent();
                    terminalKey = key.key();
                }
                default -> {
                    return Set.of();
                }
            }

            Set<String> otherPresentKeys = new HashSet<>();
            for (var kvp : terminalContainer.kvps()) {
                otherPresentKeys.add(kvp.key().stringValue());
            }

            if (terminalKey != null) {
                otherPresentKeys.remove(terminalKey.name());
            }

            return otherPresentKeys;
        }

        /**
         * No result - the path is invalid in the model.
         */
        record None() implements Result {}

        /**
         * The path ended on a shape.
         *
         * @param shape The shape at the end of the path.
         * @param model The model {@code shape} is within.
         */
        record TerminalShape(Shape shape, Model model) implements Result {}

        /**
         * The path ended on a key or member name of an object-like shape.
         *
         * @param key The key node the path ended at.
         * @param containerShape The shape containing the key.
         * @param model The model {@code containerShape} is within.
         */
        record ObjectKey(NodeCursor.Key key, Shape containerShape, Model model) implements Result {}

        /**
         * The path ended on an object-like shape.
         *
         * @param node The node the path ended at.
         * @param shape The shape at the end of the path.
         * @param model The model {@code shape} is within.
         */
        record ObjectShape(Syntax.Node.Kvps node, Shape shape, Model model) implements Result {}

        /**
         * The path ended on an array-like shape.
         *
         * @param node The node the path ended at.
         * @param shape The shape at the end of the path.
         * @param model The model {@code shape} is within.
         */
        record ArrayShape(Syntax.Node.Arr node, ListShape shape, Model model) implements Result {}
    }

    private static sealed class DefaultSearch {
        protected final Model model;

        private DefaultSearch(Model model) {
            this.model = model;
        }

        Result search(NodeCursor cursor, Shape shape) {
            if (!cursor.hasNext() || shape == null) {
                return Result.NONE;
            }

            NodeCursor.Edge edge = cursor.next();
            return switch (edge) {
                case NodeCursor.Obj obj
                        when ShapeSearch.isObjectShape(shape) -> searchObj(cursor, obj, shape);

                case NodeCursor.Arr arr
                        when shape instanceof ListShape list -> searchArr(cursor, arr, list);

                case NodeCursor.Terminal ignored -> new Result.TerminalShape(shape, model);

                default -> Result.NONE;
            };
        }

        private Result searchObj(NodeCursor cursor, NodeCursor.Obj obj, Shape shape) {
            if (!cursor.hasNext()) {
                return new Result.ObjectShape(obj.node(), shape, model);
            }

            return switch (cursor.next()) {
                case NodeCursor.Terminal ignored -> new Result.ObjectShape(obj.node(), shape, model);

                case NodeCursor.Key key -> new Result.ObjectKey(key, shape, model);

                case NodeCursor.ValueForKey ignored
                        when shape instanceof MapShape map -> searchTarget(cursor, map.getValue());

                case NodeCursor.ValueForKey value -> shape.getMember(value.keyName())
                        .map(member -> searchTarget(cursor, member))
                        .orElse(Result.NONE);

                default -> Result.NONE;
            };
        }

        private Result searchArr(NodeCursor cursor, NodeCursor.Arr arr, ListShape shape) {
            if (!cursor.hasNext()) {
                return new Result.ArrayShape(arr.node(), shape, model);
            }

            return switch (cursor.next()) {
                case NodeCursor.Terminal ignored -> new Result.ArrayShape(arr.node(), shape, model);

                case NodeCursor.Elem ignored -> searchTarget(cursor, shape.getMember());

                default -> Result.NONE;
            };
        }

        protected Result searchTarget(NodeCursor cursor, MemberShape memberShape) {
            return search(cursor, model.getShape(memberShape.getTarget()).orElse(null));
        }
    }

    private static final class SearchWithDynamicMemberTargets extends DefaultSearch {
        private final Map<ShapeId, DynamicMemberTarget> dynamicMemberTargets;

        private SearchWithDynamicMemberTargets(
                Model model,
                Map<ShapeId, DynamicMemberTarget> dynamicMemberTargets
        ) {
            super(model);
            this.dynamicMemberTargets = dynamicMemberTargets;
        }

        @Override
        protected Result searchTarget(NodeCursor cursor, MemberShape memberShape) {
            DynamicMemberTarget dynamicMemberTarget = dynamicMemberTargets.get(memberShape.getId());
            if (dynamicMemberTarget != null) {
                cursor.setCheckpoint();
                Shape target = dynamicMemberTarget.getTarget(cursor, model);
                cursor.returnToCheckpoint();
                if (target != null) {
                    return search(cursor, target);
                }
            }

            return super.searchTarget(cursor, memberShape);
        }
    }
}
