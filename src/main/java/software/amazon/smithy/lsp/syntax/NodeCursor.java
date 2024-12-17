/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.syntax;

import java.util.ArrayList;
import java.util.List;

/**
 * A moveable index into a path from the root of a {@link Syntax.Node} to a
 * position somewhere within that node. The path supports iteration both
 * forward and backward, as well as storing a 'checkpoint' along the path
 * that can be returned to at a later point.
 */
public final class NodeCursor {
    private final List<Edge> edges;
    private int pos = 0;
    private int checkpoint = 0;

    NodeCursor(List<Edge> edges) {
        this.edges = edges;
    }

    /**
     * @param value The node value to create the cursor for
     * @param documentIndex The index within the document to create the cursor for
     * @return A node cursor from the start of {@code value} to {@code documentIndex}
     *  within {@code document}.
     */
    public static NodeCursor create(Syntax.Node value, int documentIndex) {
        List<NodeCursor.Edge> edges = new ArrayList<>();
        NodeCursor cursor = new NodeCursor(edges);

        if (value == null || documentIndex < 0) {
            return cursor;
        }

        Syntax.Node next = value;
        while (true) {
            iteration: switch (next) {
                case Syntax.Node.Kvps kvps -> {
                    edges.add(new NodeCursor.Obj(kvps));
                    Syntax.Node.Kvp lastKvp = null;
                    for (Syntax.Node.Kvp kvp : kvps.kvps()) {
                        if (kvp.key.isIn(documentIndex)) {
                            String key = kvp.key.stringValue();
                            edges.add(new NodeCursor.Key(key, kvps));
                            edges.add(new NodeCursor.Terminal(kvp));
                            return cursor;
                        } else if (kvp.inValue(documentIndex)) {
                            if (kvp.value == null) {
                                lastKvp = kvp;
                                break;
                            }
                            String key = kvp.key.stringValue();
                            edges.add(new NodeCursor.ValueForKey(key, kvps));
                            next = kvp.value;
                            break iteration;
                        } else {
                            lastKvp = kvp;
                        }
                    }
                    if (lastKvp != null && lastKvp.value == null) {
                        edges.add(new NodeCursor.ValueForKey(lastKvp.key.stringValue(), kvps));
                        edges.add(new NodeCursor.Terminal(lastKvp));
                        return cursor;
                    }
                    return cursor;
                }
                case Syntax.Node.Obj obj -> {
                    next = obj.kvps;
                }
                case Syntax.Node.Arr arr -> {
                    edges.add(new NodeCursor.Arr(arr));
                    for (int i = 0; i < arr.elements.size(); i++) {
                        Syntax.Node elem = arr.elements.get(i);
                        if (elem.isIn(documentIndex)) {
                            edges.add(new NodeCursor.Elem(i, arr));
                            next = elem;
                            break iteration;
                        }
                    }
                    return cursor;
                }
                case null -> {
                    edges.add(new NodeCursor.Terminal(null));
                    return cursor;
                }
                default -> {
                    edges.add(new NodeCursor.Terminal(next));
                    return cursor;
                }
            }
        }
    }

    public List<Edge> edges() {
        return edges;
    }

    /**
     * @return Whether the cursor is not at the end of the path. A return value
     *  of {@code true} means {@link #next()} may be called safely.
     */
    public boolean hasNext() {
        return pos < edges.size();
    }

    /**
     * @return The next edge along the path. Also moves the cursor forward.
     */
    public Edge next() {
        Edge edge = edges.get(pos);
        pos++;
        return edge;
    }

    /**
     * @return Whether the cursor is not at the start of the path. A return value
     *  of {@code true} means {@link #previous()} may be called safely.
     */
    public boolean hasPrevious() {
        return edges.size() - pos >= 0;
    }

    /**
     * @return The previous edge along the path. Also moves the cursor backward.
     */
    public Edge previous() {
        pos--;
        return edges.get(pos);
    }

    /**
     * @return Whether the path consists of a single, terminal, node.
     */
    public boolean isTerminal() {
        return edges.size() == 1 && edges.getFirst() instanceof Terminal;
    }

    /**
     * Store the current cursor position to be returned to later. Subsequent
     * calls overwrite the checkpoint.
     */
    public void setCheckpoint() {
        this.checkpoint = pos;
    }

    /**
     * Return to a previously set checkpoint. Subsequent calls continue to
     * the same checkpoint, unless overwritten.
     */
    public void returnToCheckpoint() {
        this.pos = checkpoint;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        for (Edge edge : edges) {
            switch (edge) {
                case Obj ignored -> builder.append("Obj,");
                case Arr ignored -> builder.append("Arr,");
                case Terminal ignored -> builder.append("Terminal,");
                case Elem elem -> builder.append("Elem(").append(elem.index).append("),");
                case Key key -> builder.append("Key(").append(key.name).append("),");
                case ValueForKey valueForKey -> builder.append("ValueForKey(").append(valueForKey.keyName).append("),");
            }
        }
        return builder.toString();
    }

    /**
     * An edge along a path within a {@link Syntax.Node}. Edges are fine-grained
     * structurally, so there is a distinction between e.g. a path into an object,
     * an object key, and a value for an object key, but there is no distinction
     * between e.g. a path into a string value vs a numeric value. Each edge stores
     * a reference to the underlying node, or a reference to the parent node.
     */
    public sealed interface Edge {}

    /**
     * Within an object, i.e. within the braces: '{}'.
     * @param node The value of the underlying node at this edge.
     */
    public record Obj(Syntax.Node.Kvps node) implements Edge {}

    /**
     * Within an array/list, i.e. within the brackets: '[]'.
     * @param node The value of the underlying node at this edge.
     */
    public record Arr(Syntax.Node.Arr node) implements Edge {}

    /**
     * The end of a path. Will always be present at the end of any non-empty path.
     * @param node The value of the underlying node at this edge.
     */
    public record Terminal(Syntax.Node node) implements Edge {}

    /**
     * Within a key of an object, i.e. '{"here": null}'
     * @param name The name of the key.
     * @param parent The object node the key is within.
     */
    public record Key(String name, Syntax.Node.Kvps parent) implements Edge {}

    /**
     * Within a value corresponding to a key of an object, i.e. '{"key": "here"}'
     * @param keyName The name of the key.
     * @param parent The object node the value is within.
     */
    public record ValueForKey(String keyName, Syntax.Node.Kvps parent) implements Edge {}

    /**
     * Within an element of an array/list, i.e. '["here"]'.
     * @param index The index of the element.
     * @param parent The array node the element is within.
     */
    public record Elem(int index, Syntax.Node.Arr parent) implements Edge {}
}
