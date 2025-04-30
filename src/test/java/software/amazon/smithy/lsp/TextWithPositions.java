/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import static software.amazon.smithy.lsp.document.DocumentTest.safeString;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.Position;
import software.amazon.smithy.lsp.document.Document;

/**
 * Wraps some text and positions within that text for easier testing of features
 * that operate on cursor positions within a text document.
 *
 * @param text The underlying text
 * @param positions The positions within {@code text}
 */
public record TextWithPositions(String text, Position... positions) {
    private static final String POSITION_MARKER = "%";

    /**
     * A convenience method for constructing {@link TextWithPositions} without
     * manually specifying the positions, which are error-prone and hard to
     * read.
     *
     * <p>The string provided to this method can contain position markers,
     * the {@code %} character, denoting where {@link #positions} should
     * be. Each marker will be removed from {@link #text}.</p>
     *
     * @param raw The raw string with position markers
     * @return {@link TextWithPositions} with positions where the markers were,
     *  and those markers removed.
     */
    public static TextWithPositions from(String raw) {
        Document document = Document.of(safeString(raw));
        List<Position> positions = new ArrayList<>();

        int lastLine = -1;
        int lineMarkerCount = 0;
        int i = 0;
        while (true) {
            int next = document.nextIndexOf(POSITION_MARKER, i);
            if (next < 0) {
                break;
            }
            Position position = document.positionAtIndex(next);

            // If there's two or more markers on the same line, any markers after the
            // first will be off by one when we do the replacement.
            if (position.getLine() != lastLine) {
                lastLine = position.getLine();
                lineMarkerCount = 1;
            } else {
                position.setCharacter(position.getCharacter() - lineMarkerCount);
                lineMarkerCount++;
            }
            positions.add(position);
            i = next + 1;
        }
        String text = document.copyText().replace(POSITION_MARKER, "");
        return new TextWithPositions(text, positions.toArray(new Position[0]));
    }}
