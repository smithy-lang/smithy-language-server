/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.document.syntax;

import java.util.ArrayList;
import java.util.List;
import software.amazon.smithy.lsp.document.Document;

/**
 * Provides methods to query specific information about {@link Syntax.Statement}s
 * and {@link Syntax.Node}s.
 */
public final class SyntaxQuery {
    private SyntaxQuery() {
    }

    /**
     * @param statements The statements to search
     * @param position The character offset in the document
     * @return The index of the statement in the list of statements that the
     *         given position is within, or -1 if it was not found.
     */
    public static int findStatementIndex(List<Syntax.Statement> statements, int position) {
        int low = 0;
        int up = statements.size() - 1;

        while (low <= up) {
            int mid = (low + up) / 2;
            Syntax.Statement statement = statements.get(mid);
            if (statement.isIn(position)) {
                if (statement instanceof Syntax.Statement.Block) {
                    return findBetween(statements, mid, up, position);
                } else {
                    return mid;
                }
            } else if (statement.start() > position) {
                up = mid - 1;
            } else if (statement.end() < position) {
                low = mid + 1;
            } else {
                return -1;
            }
        }

        return -1;
    }

    private static int findBetween(List<Syntax.Statement> statements, int lower, int upper, int position) {
        int ogLower = lower;
        lower += 1;
        while (lower <= upper) {
            int mid = (lower + upper) / 2;
            Syntax.Statement statement = statements.get(mid);
            if (statement.isIn(position)) {
                // Could have nested blocks, like in an inline structure definition
                if (statement instanceof Syntax.Statement.Block) {
                    return findBetween(statements, mid, upper, position);
                }
                return mid;
            } else if (statement.start() > position) {
                upper = mid - 1;
            } else if (statement.end() < position) {
                lower = mid + 1;
            } else {
                return ogLower;
            }
        }

        return ogLower;
    }

    /**
     * @param document The document to find the path in
     * @param value The node value to find the path in
     * @param documentIndex The character offset in the given document to find the path to
     * @return The path within the given node value to the given offset
     */
    public static NodePath findNodePath(Document document, Syntax.Node value, int documentIndex) {
        List<NodePath.Edge> edges = new ArrayList<>();
        NodePath path = NodePath.of(edges);

        if (value == null || documentIndex < 0) {
            return path;
        }

        Syntax.Node next = value;
        while (true) {
            iteration: switch (next) {
                case Syntax.Node.Kvps kvps -> {
                    edges.add(NodePath.OBJ);
                    Syntax.Node.Kvp lastKvp = null;
                    for (Syntax.Node.Kvp kvp : kvps.kvps) {
                        String key = kvp.key.copyValueFrom(document);
                        if (kvp.key.isIn(documentIndex)) {
                            edges.add(new NodePath.Key(key));
                            edges.add(NodePath.TERMINAL);
                            return path;
                        } else if (kvp.value != null && kvp.value.isIn(documentIndex)) {
                            edges.add(new NodePath.ValueForKey(key));
                            next = kvp.value;
                            break iteration;
                        } else {
                            lastKvp = kvp;
                        }
                    }
                    if (lastKvp != null && lastKvp.value == null) {
                        edges.add(new NodePath.ValueForKey(lastKvp.key.copyValueFrom(document)));
                        edges.add(NodePath.TERMINAL);
                        return path;
                    }
                    edges.add(NodePath.TERMINAL);
                    return path;
                }
                case Syntax.Node.Obj obj -> {
                    next = obj.kvps;
                }
                case Syntax.Node.Arr arr -> {
                    edges.add(NodePath.ARR);
                    for (int i = 0; i < arr.elements.size(); i++) {
                        Syntax.Node elem = arr.elements.get(i);
                        if (elem.isIn(documentIndex)) {
                            edges.add(new NodePath.Elem(i));
                            next = elem;
                            break iteration;
                        }
                    }

                    edges.add(NodePath.TERMINAL);
                    return path;
                }
                default -> {
                    edges.add(NodePath.TERMINAL);
                    return path;
                }
            }
        }
    }
}
