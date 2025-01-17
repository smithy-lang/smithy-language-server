/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.syntax;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An IDL parse result at a specific position within the underlying document.
 *
 * @param parseResult The IDL parse result
 * @param statementIndex The index of the statement {@code documentIndex} is within
 * @param documentIndex The index within the underlying document
 */
public record StatementView(Syntax.IdlParseResult parseResult, int statementIndex, int documentIndex) {

    /**
     * @param parseResult The parse result to create a view of
     * @return An optional view of the first statement in the given parse result,
     *  or empty if the parse result has no statements
     */
    public static Optional<StatementView> createAtStart(Syntax.IdlParseResult parseResult) {
        if (parseResult.statements().isEmpty()) {
            return Optional.empty();
        }

        return createAt(parseResult, parseResult.statements().getFirst().start());
    }

    /**
     * @param parseResult The parse result to create a view of
     * @param documentIndex The index within the underlying document
     * @return An optional view of the statement the given documentIndex is within
     *  in the given parse result, or empty if the index is not within a statement
     */
    public static Optional<StatementView> createAt(Syntax.IdlParseResult parseResult, int documentIndex) {
        if (documentIndex < 0) {
            return Optional.empty();
        }

        int statementIndex = statementIndex(parseResult.statements(), documentIndex);
        if (statementIndex < 0) {
            return Optional.empty();
        }

        return Optional.of(new StatementView(parseResult, statementIndex, documentIndex));
    }

    private static int statementIndex(List<Syntax.Statement> statements, int position) {
        int low = 0;
        int up = statements.size() - 1;

        while (low <= up) {
            int mid = (low + up) / 2;
            Syntax.Statement statement = statements.get(mid);
            if (statement.isIn(position)) {
                if (statement instanceof Syntax.Statement.Block) {
                    return statementIndexBetween(statements, mid, up, position);
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

        Syntax.Statement last = statements.get(up);
        if (last instanceof Syntax.Statement.MemberStatement memberStatement) {
            // Note: parent() can be null for TraitApplication.
            if (memberStatement.parent() != null && memberStatement.parent().isIn(position)) {
                return memberStatement.parent().statementIndex();
            }
        }

        return -1;
    }

    private static int statementIndexBetween(List<Syntax.Statement> statements, int lower, int upper, int position) {
        int ogLower = lower;
        lower += 1;
        while (lower <= upper) {
            int mid = (lower + upper) / 2;
            Syntax.Statement statement = statements.get(mid);
            if (statement.isIn(position)) {
                // Could have nested blocks, like in an inline structure definition
                if (statement instanceof Syntax.Statement.Block) {
                    return statementIndexBetween(statements, mid, upper, position);
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
     * @return The non-nullable statement that {@link #documentIndex()} is within
     */
    public Syntax.Statement getStatement() {
        return parseResult.statements().get(statementIndex);
    }

    /**
     * @param documentIndex The index within the underlying document
     * @return The optional statement the given index is within
     */
    public Optional<Syntax.Statement> getStatementAt(int documentIndex) {
        int statementIndex = statementIndex(parseResult.statements(), documentIndex);
        if (statementIndex < 0) {
            return Optional.empty();
        }
        return Optional.of(parseResult.statements().get(statementIndex));
    }

    /**
     * @return The nearest shape def before this view
     */
    public Syntax.Statement.ShapeDef nearestShapeDefBefore() {
        int searchStatementIndex = statementIndex - 1;
        while (searchStatementIndex >= 0) {
            Syntax.Statement statement = parseResult.statements().get(searchStatementIndex);
            if (statement instanceof Syntax.Statement.ShapeDef shapeDef) {
                return shapeDef;
            }
            searchStatementIndex--;
        }
        return null;
    }

    /**
     * @return The nearest for resource and mixins before this view
     */
    public Syntax.ForResourceAndMixins nearestForResourceAndMixinsBefore() {
        int searchStatementIndex = statementIndex;
        while (searchStatementIndex >= 0) {
            Syntax.Statement searchStatement = parseResult.statements().get(searchStatementIndex);
            if (searchStatement instanceof Syntax.Statement.Block) {
                Syntax.Statement.ForResource forResource = null;
                Syntax.Statement.Mixins mixins = null;

                int lastSearchIndex = searchStatementIndex - 2;
                searchStatementIndex--;
                while (searchStatementIndex >= 0 && searchStatementIndex >= lastSearchIndex) {
                    Syntax.Statement candidateStatement = parseResult.statements().get(searchStatementIndex);
                    if (candidateStatement instanceof Syntax.Statement.Mixins m) {
                        mixins = m;
                    } else if (candidateStatement instanceof Syntax.Statement.ForResource f) {
                        forResource = f;
                    }
                    searchStatementIndex--;
                }

                return new Syntax.ForResourceAndMixins(forResource, mixins);
            }
            searchStatementIndex--;
        }

        return new Syntax.ForResourceAndMixins(null, null);
    }

    /**
     * @return The names of all the other members around this view
     */
    public Set<String> otherMemberNames() {
        Set<String> found = new HashSet<>();
        int searchIndex = statementIndex;
        int lastMemberStatementIndex = statementIndex;
        while (searchIndex >= 0) {
            Syntax.Statement statement = parseResult.statements().get(searchIndex);
            if (statement instanceof Syntax.Statement.Block block) {
                lastMemberStatementIndex = block.lastStatementIndex();
                break;
            } else if (searchIndex != statementIndex) {
                addMemberName(found, statement);
            }
            searchIndex--;
        }
        searchIndex = statementIndex + 1;
        while (searchIndex <= lastMemberStatementIndex) {
            Syntax.Statement statement = parseResult.statements().get(searchIndex);
            addMemberName(found, statement);
            searchIndex++;
        }
        return found;
    }

    private static void addMemberName(Set<String> memberNames, Syntax.Statement statement) {
        switch (statement) {
            case Syntax.Statement.MemberDef def -> memberNames.add(def.name().stringValue());
            case Syntax.Statement.NodeMemberDef def -> memberNames.add(def.name().stringValue());
            case Syntax.Statement.InlineMemberDef def -> memberNames.add(def.name().stringValue());
            case Syntax.Statement.ElidedMemberDef def -> memberNames.add(def.name().stringValue());
            default -> {
            }
        }
    }

    /**
     * @return The nearest shape def after this view
     */
    public Syntax.Statement.ShapeDef nearestShapeDefAfter() {
        for (int i = statementIndex + 1; i < parseResult.statements().size(); i++) {
            Syntax.Statement statement = parseResult.statements().get(i);
            if (statement instanceof Syntax.Statement.ShapeDef shapeDef) {
                return shapeDef;
            } else if (!(statement instanceof Syntax.Statement.TraitApplication)) {
                return null;
            }
        }

        return null;
    }
}
