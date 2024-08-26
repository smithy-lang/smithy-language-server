/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.document.syntax;

import java.util.Arrays;
import java.util.List;

/**
 * Represents a path within a {@link Syntax.Node}.
 */
public final class NodePath {
    /**
     * Single instance of Obj edges. Use this instead of creating new Objs.
     */
    public static final Edge OBJ = new Obj();

    /**
     * Single instance of Arr edges. Use this instead of creating new Arrs.
     */
    public static final Edge ARR = new Arr();

    /**
     * Single instance of Terminal edges. Use this instead of creating new Terminals.
     */
    public static final Edge TERMINAL = new Terminal();

    private final List<Edge> edges;

    private NodePath(List<Edge> edges) {
        this.edges = edges;
    }

    /**
     * @param edges The edges of the path to create
     * @return The created path
     */
    public static NodePath of(List<Edge> edges) {
        return new NodePath(edges);
    }

    static NodePath of(Edge... edges) {
        return new NodePath(Arrays.asList(edges));
    }

    /**
     * @return The edges of this path
     */
    public List<Edge> edges() {
        return edges;
    }

    /**
     * @param path The path to check
     * @return Whether this path matches the given path
     */
    public boolean matches(NodePath path) {
        if (path == null || path.edges.size() != this.edges.size()) {
            return false;
        }

        for (int i = 0; i < this.edges.size(); i++) {
            Edge thisEdge = this.edges.get(i);
            Edge otherEdge = path.edges.get(i);

            if (thisEdge.edgeType() != otherEdge.edgeType()) {
                return false;
            }

            if (thisEdge instanceof Key(var thisName)
                && otherEdge instanceof Key(var otherName)
            ) {
                if (!thisName.equals(otherName)) {
                    return false;
                }
            } else if (thisEdge instanceof ValueForKey(var thisKeyName)
                       && otherEdge instanceof ValueForKey(var otherKeyName)
            ) {
                if (!thisKeyName.equals(otherKeyName)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * @return Whether this path is a single {@link Terminal} edge
     */
    public boolean isTerminal() {
        return edges.size() == 1 && edges.get(0).edgeType() == Type.Terminal;
    }

    /**
     * An edge in a {@link NodePath}.
     */
    public sealed interface Edge {
        /**
         * @return The type of the edge
         */
        default Type edgeType() {
            return switch (this) {
                case Obj ignored -> Type.Obj;
                case Arr ignored -> Type.Arr;
                case Elem ignored -> Type.Elem;
                case Terminal ignored -> Type.Terminal;
                case Key ignored -> Type.Key;
                case ValueForKey ignored -> Type.ValueForKey;
            };
        }
    }

    /**
     * The type of {@link Edge}.
     */
    public enum Type {
        Obj,
        Key,
        ValueForKey,
        Arr,
        Elem,
        Terminal
    }

    /**
     * The edge for a path into an object.
     */
    public record Obj() implements Edge {}

    /**
     * The edge for a path into an array.
     */
    public record Arr() implements Edge {}

    /**
     * Indicates the end of a path.
     */
    public record Terminal() implements Edge {}

    /**
     * The edge for a path into an array element.
     *
     * @param index The index of the element
     */
    public record Elem(int index) implements Edge {}

    /**
     * The edge for a path into a key of an object.
     *
     * @param name The name of the key
     */
    public record Key(String name) implements Edge {}

    /**
     * The edge for a path into the value corresponding to a key of an object.
     *
     * @param keyName The name of the key
     */
    public record ValueForKey(String keyName) implements Edge {}
}
