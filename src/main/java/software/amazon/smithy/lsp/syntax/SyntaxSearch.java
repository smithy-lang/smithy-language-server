/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.syntax;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import software.amazon.smithy.lsp.document.Document;

/**
 * Low-level API to query specific information about {@link Syntax.Statement}s
 * and {@link Syntax.Node}s.
 */
public final class SyntaxSearch {
    private SyntaxSearch() {
    }

    /**
     * @param statements The statements to search
     * @param position The character offset in the document
     * @return The index of the statement in the list of statements that the
     * given position is within, or -1 if it was not found.
     */
    public static int statementIndex(List<Syntax.Statement> statements, int position) {
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
     * @param statements The statements to search
     * @param memberStatementIndex The index of the statement to search from
     * @return The closest shape def statement appearing before the given index
     * or {@code null} if none was found.
     */
    public static Syntax.Statement.ShapeDef closestShapeDefBeforeMember(
            List<Syntax.Statement> statements,
            int memberStatementIndex
    ) {
        int searchStatementIdx = memberStatementIndex - 1;
        while (searchStatementIdx >= 0) {
            Syntax.Statement searchStatement = statements.get(searchStatementIdx);
            if (searchStatement instanceof Syntax.Statement.ShapeDef shapeDef) {
                return shapeDef;
            }
            searchStatementIdx--;
        }
        return null;
    }

    /**
     * @param forResource The nullable for-resource statement
     * @param mixins The nullable mixins statement
     */
    public record ForResourceAndMixins(Syntax.Statement.ForResource forResource, Syntax.Statement.Mixins mixins) {}

    /**
     * @param statements The statements to search
     * @param memberStatementIndex The index of the statement to search from
     * @return The closest adjacent {@link Syntax.Statement.ForResource} and
     * {@link Syntax.Statement.Mixins} to the statement at the given index.
     */
    public static ForResourceAndMixins closestForResourceAndMixinsBeforeMember(
            List<Syntax.Statement> statements,
            int memberStatementIndex
    ) {
        int searchStatementIndex = memberStatementIndex;
        while (searchStatementIndex >= 0) {
            Syntax.Statement searchStatement = statements.get(searchStatementIndex);
            if (searchStatement instanceof Syntax.Statement.Block) {
                Syntax.Statement.ForResource forResource = null;
                Syntax.Statement.Mixins mixins = null;

                int lastSearchIndex = searchStatementIndex - 2;
                searchStatementIndex--;
                while (searchStatementIndex >= 0 && searchStatementIndex >= lastSearchIndex) {
                    Syntax.Statement candidateStatement = statements.get(searchStatementIndex);
                    if (candidateStatement instanceof Syntax.Statement.Mixins m) {
                        mixins = m;
                    } else if (candidateStatement instanceof Syntax.Statement.ForResource f) {
                        forResource = f;
                    }
                    searchStatementIndex--;
                }

                return new ForResourceAndMixins(forResource, mixins);
            }
            searchStatementIndex--;
        }

        return new ForResourceAndMixins(null, null);
    }

    /**
     * @param document The document to search within
     * @param statements The statements to search
     * @param memberStatementIndex The index of the member statement to search around
     * @return The names of other members around (but not including) the member at
     *  {@code memberStatementIndex}.
     */
    public static Set<String> otherMemberNames(
            Document document,
            List<Syntax.Statement> statements,
            int memberStatementIndex
    ) {
        Set<String> found = new HashSet<>();
        int searchIndex = memberStatementIndex;
        int lastMemberStatementIndex = memberStatementIndex;
        while (searchIndex >= 0) {
            Syntax.Statement statement = statements.get(searchIndex);
            if (statement instanceof Syntax.Statement.Block block) {
                lastMemberStatementIndex = block.lastStatementIndex();
                break;
            } else if (searchIndex != memberStatementIndex) {
                addMemberName(document, found, statement);
            }
            searchIndex--;
        }
        searchIndex = memberStatementIndex + 1;
        while (searchIndex <= lastMemberStatementIndex) {
            Syntax.Statement statement = statements.get(searchIndex);
            addMemberName(document, found, statement);
            searchIndex++;
        }
        return found;
    }

    private static void addMemberName(Document document, Set<String> memberNames, Syntax.Statement statement) {
        switch (statement) {
            case Syntax.Statement.MemberDef def -> memberNames.add(def.name().copyValueFrom(document));
            case Syntax.Statement.NodeMemberDef def -> memberNames.add(def.name().copyValueFrom(document));
            case Syntax.Statement.InlineMemberDef def -> memberNames.add(def.name().copyValueFrom(document));
            case Syntax.Statement.ElidedMemberDef def -> memberNames.add(def.name().copyValueFrom(document));
            default -> {
            }
        }
    }

    /**
     * @param statements The statements to search
     * @param traitStatementIndex The index of the trait statement to search from
     * @return The closest shape def statement after {@code traitStatementIndex},
     *  or null if none was found.
     */
    public static Syntax.Statement.ShapeDef closestShapeDefAfterTrait(
            List<Syntax.Statement> statements,
            int traitStatementIndex
    ) {
        for (int i = traitStatementIndex + 1; i < statements.size(); i++) {
            Syntax.Statement statement = statements.get(i);
            if (statement instanceof Syntax.Statement.ShapeDef shapeDef) {
                return shapeDef;
            } else if (!(statement instanceof Syntax.Statement.TraitApplication)) {
                return null;
            }
        }

        return null;
    }
}
