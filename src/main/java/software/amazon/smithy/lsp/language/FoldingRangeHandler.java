/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.Range;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.document.DocumentImports;
import software.amazon.smithy.lsp.syntax.Syntax;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;


public record FoldingRangeHandler(Document document, DocumentImports documentImports,
                                  List<Syntax.Statement> statements) {
    /**
     * Main public handle function in the handler class
     *
     * @return A list of FoldingRange
     */
    public List<FoldingRange> handle() {
        return generateFoldingRanges();

    }

    /**
     * Check if the given range can be folded
     *
     * @param range The Range object held the start line and end line info for the folding range
     * @return If the given Range can be folded (end line - start line >= 1)
     */
    private boolean isFoldable(Range range) {
        return range != null && range.getEnd().getLine() - range.getStart().getLine() >= 1;
    }

    /**
     * Create a fold range object with given range and add it to the foldingRanges list
     *
     * @param range         The Range object held the start line and end line info for the folding range
     * @param foldingRanges The list holds folding ranges for current document
     */
    private void addFoldingRange(Range range, List<FoldingRange> foldingRanges) {
        if (!isFoldable(range)) return;
        foldingRanges.add(new FoldingRange(range.getStart().getLine(), range.getEnd().getLine()));
    }

    /**
     * Create range with start and end character index and then create a fold range object with given range and add it
     * to the foldingRanges list.
     *
     * @param startCharacter The index of the start character of the folding range
     * @param endCharacter   The index of the start character of the folding range
     * @param foldingRanges  The list holds folding ranges for current document
     */
    private void addFoldingRange(int startCharacter, int endCharacter, List<FoldingRange> foldingRanges) {
        Range range = document.rangeBetween(startCharacter, endCharacter);
        addFoldingRange(range, foldingRanges);
    }

    /**
     * Use the import range in DocumentImport class to create the folding range directly
     *
     * @param documentImports The document import object that held the import range
     * @param foldingRanges   The list holds folding ranges for current document
     */
    private void addFoldingRangeForImports(DocumentImports documentImports, List<FoldingRange> foldingRanges) {
        Range range = documentImports.importsRange();
        addFoldingRange(range, foldingRanges);
    }

    /**
     * Main function to iterate statements and process the folding range based on the statement type
     *
     * @return The list holds folding ranges for current document
     */
    private List<FoldingRange> generateFoldingRanges() {
        List<FoldingRange> foldingRanges = new ArrayList<>();

        addFoldingRangeForImports(documentImports, foldingRanges);

        ListIterator<Syntax.Statement> iterator = statements.listIterator();

        while (iterator.hasNext()) {
            var statement = iterator.next();
            switch (statement) {
                case Syntax.Statement.TraitApplication trait ->
                        processFoldingRangeForTraitApplication(trait, iterator, foldingRanges);

                case Syntax.Statement.Metadata metadata -> processFoldingRangeForNode(metadata.value(), foldingRanges);

                case Syntax.Statement.Block blk -> processFoldingRangeForBlock(blk, foldingRanges);

                case Syntax.Statement.NodeMemberDef nodeMember ->
                        processFoldingRangeForNode(nodeMember.value(), foldingRanges);

                // For most of the current statement types just create the folding range based on
                // its own range
                default -> addFoldingRange(statement.start(), statement.end(), foldingRanges);
            }
        }
        return foldingRanges;
    }

    /**
     * Function to process the block statement's folding range.
     *
     * @param blk           The block statement to be processed
     * @param foldingRanges The list holds folding ranges for current document
     */
    private void processFoldingRangeForBlock(Syntax.Statement.Block blk, List<FoldingRange> foldingRanges) {
        // If the block is empty, the last statement index will not be set.
        if (blk.lastStatementIndex() == blk.statementIndex()) {
            return;
        }
        addFoldingRange(blk.start(), blk.end(), foldingRanges);
    }

    /**
     * Since traits can appear multiple times, we want to fold the trait block. Need to use iterator to find the last
     * trait statement for the trait block.
     *
     * @param trait         The first trait statement for this potential trait block
     * @param iterator      The list iterator for the statements passed into this handler class
     * @param foldingRanges The list holds folding ranges for current document
     */
    private void processFoldingRangeForTraitApplication(Syntax.Statement.TraitApplication trait, ListIterator<Syntax.Statement> iterator, List<FoldingRange> foldingRanges) {
        int traitBlockStart = trait.start();
        int traitBlockEnd = -1;
        // Create folding range for the start trait statement
        processFoldingRangeForNode(trait.value(), foldingRanges);
        // Find next non-trait statement and create folding range for the statement traversed
        while (iterator.hasNext()) {
            var nextStatement = iterator.next();

            if (nextStatement instanceof Syntax.Statement.TraitApplication nextTrait) {
                traitBlockEnd = nextTrait.value() == null ? nextTrait.end() : nextTrait.value().end();
                processFoldingRangeForNode(nextTrait.value(), foldingRanges);
            } else {
                iterator.previous();
                break;
            }
        }
        //Single nested trait is handled by createFoldingRangeForNode
        if (traitBlockEnd != -1) {
            addFoldingRange(traitBlockStart, traitBlockEnd, foldingRanges);
        }
    }

    /**
     * Recursively process the node's folding range for nested declarations.
     *
     * @param node          The node object to be processed
     * @param foldingRanges The list holds folding ranges for current document
     */
    private void processFoldingRangeForNode(Syntax.Node node, List<FoldingRange> foldingRanges) {
        if (node == null) {
            return;
        }

        Range range = document.rangeBetween(node.start(), node.end());

        if (!isFoldable(range)) {
            return;
        }

        switch (node) {
            case Syntax.Node.Kvps kvps -> {
                if (kvps.kvps().isEmpty()) {
                    return;
                }

                addFoldingRange(kvps.start(), kvps.end(), foldingRanges);

                kvps.kvps().forEach(kvp -> processFoldingRangeForNode(kvp.value(), foldingRanges));
            }
            // Obj only contains kvps, will use kvps as its folding range
            case Syntax.Node.Obj obj -> processFoldingRangeForNode(obj.kvps(), foldingRanges);

            case Syntax.Node.Arr arr -> {
                if (arr.elements().isEmpty()) {
                    return;
                }

                addFoldingRange(arr.start(), arr.end(), foldingRanges);

                arr.elements().forEach(element -> processFoldingRangeForNode(element, foldingRanges));
            }

            default -> addFoldingRange(node.start(), node.end(), foldingRanges);
        }
    }

}
