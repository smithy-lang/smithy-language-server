/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.Range;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.document.DocumentImports;
import software.amazon.smithy.lsp.syntax.Syntax;


public record FoldingRangeHandler(Document document, DocumentImports documentImports,
                                  List<Syntax.Statement> statements) {
    /**
     * Main public handle function in the handler class.
     *
     * @return A list of FoldingRange
     */
    public List<FoldingRange> handle() {
        return generateFoldingRanges();
    }

    private boolean isFoldable(int startLine, int endLine) {
        // If the statement or node takes up at least two lines, it is foldable
        return endLine > startLine;
    }

    private void addFoldingRange(List<FoldingRange> foldingRanges, int startIndex, int endIndex) {
        int startLine = document.lineOfIndex(startIndex);
        int endLine = document.lineOfIndex(endIndex);
        if (isFoldable(startLine, endLine)) {
            foldingRanges.add(new FoldingRange(startLine, endLine));
        }
    }

    private void addFoldingRangeForImports(List<FoldingRange> foldingRanges, DocumentImports documentImports) {
        Range range = documentImports.importsRange();
        if (range != null && isFoldable(range.getStart().getLine(), range.getEnd().getLine())) {
            foldingRanges.add(new FoldingRange(range.getStart().getLine(), range.getEnd().getLine()));
        }
    }

    private List<FoldingRange> generateFoldingRanges() {
        List<FoldingRange> foldingRanges = new ArrayList<>();

        addFoldingRangeForImports(foldingRanges, documentImports);

        ListIterator<Syntax.Statement> iterator = statements.listIterator();

        while (iterator.hasNext()) {
            var statement = iterator.next();
            switch (statement) {
                case Syntax.Statement.TraitApplication trait ->
                        processFoldingRangeForTraitApplication(foldingRanges, trait, iterator);

                case Syntax.Statement.Metadata metadata ->
                        processFoldingRangeForNode(foldingRanges, metadata.value());

                case Syntax.Statement.Block blk ->
                        processFoldingRangeForBlock(foldingRanges, blk);

                case Syntax.Statement.NodeMemberDef nodeMember ->
                        processFoldingRangeForNode(foldingRanges, nodeMember.value());

                case Syntax.Statement.InlineMemberDef inlineMember ->
                        addFoldingRange(foldingRanges, inlineMember.start(), inlineMember.end());
                // Skip the statements don't need to be folded.
                default -> {}
            }
        }
        return foldingRanges;
    }

    private void processFoldingRangeForBlock(List<FoldingRange> foldingRanges, Syntax.Statement.Block blk) {
        // If the block is empty, the last statement index will not be set.
        if (blk.lastStatementIndex() == blk.statementIndex()) {
            return;
        }
        addFoldingRange(foldingRanges, blk.start(), blk.end());
    }

    private void processFoldingRangeForTraitApplication(List<FoldingRange> foldingRanges,
                                                        Syntax.Statement.TraitApplication trait,
                                                        ListIterator<Syntax.Statement> iterator) {
        int traitBlockStart = trait.start();
        int traitBlockEnd = -1;
        // Create folding range for the start trait statement.
        processFoldingRangeForNode(foldingRanges, trait.value());
        // Find next non-trait statement and create folding range for the statement traversed.
        while (iterator.hasNext()) {
            var nextStatement = iterator.next();

            if (nextStatement instanceof Syntax.Statement.TraitApplication nextTrait) {
                traitBlockEnd = nextTrait.value() == null ? nextTrait.end() : nextTrait.value().end();
                processFoldingRangeForNode(foldingRanges, nextTrait.value());
            } else {
                iterator.previous();
                break;
            }
        }
        //Single nested trait is handled by processFoldingRangeForNode.
        if (traitBlockEnd != -1) {
            addFoldingRange(foldingRanges, traitBlockStart, traitBlockEnd);
        }
    }

    private void processFoldingRangeForNode(List<FoldingRange> foldingRanges, Syntax.Node node) {
        if (node == null) {
            return;
        }

        int startLine = document.lineOfIndex(node.start());
        int endLine = document.lineOfIndex(node.end());

        if (!isFoldable(startLine, endLine)) {
            return;
        }

        switch (node) {
            case Syntax.Node.Kvps kvps -> {
                if (!kvps.kvps().isEmpty()) {
                    addFoldingRange(foldingRanges, kvps.start(), kvps.end());
                    kvps.kvps().forEach(kvp -> processFoldingRangeForNode(foldingRanges, kvp.value()));
                }
            }
            // Obj only contains kvps, will use kvps as its folding range
            case Syntax.Node.Obj obj -> processFoldingRangeForNode(foldingRanges, obj.kvps());

            case Syntax.Node.Arr arr -> {
                if (!arr.elements().isEmpty()) {
                    addFoldingRange(foldingRanges, arr.start(), arr.end());
                    arr.elements().forEach(element -> processFoldingRangeForNode(foldingRanges, element));
                }
            }
            // Skip the Nodes don't need to be folded
            default -> {}
        }
    }
}
