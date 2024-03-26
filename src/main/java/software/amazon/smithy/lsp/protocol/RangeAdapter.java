/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.protocol;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Builder and utility methods for working with LSP's {@link Range}.
 */
public final class RangeAdapter {
    private int startLine;
    private int startCharacter;
    private int endLine;
    private int endCharacter;

    /**
     * @return Range of (0, 0) - (0, 0)
     */
    public static Range origin() {
        return new RangeAdapter()
                .startLine(0)
                .startCharacter(0)
                .endLine(0)
                .endCharacter(0)
                .build();
    }

    /**
     * @param point Position to create a point range of
     * @return Range of (point) - (point)
     */
    public static Range point(Position point) {
        return new Range(point, point);
    }

    /**
     * @param line Line of the point
     * @param character Character offset on the line
     * @return Range of (line, character) - (line, character)
     */
    public static Range point(int line, int character) {
        return point(new Position(line, character));
    }

    /**
     * @param line Line the span is on
     * @param startCharacter Start character of the span
     * @param endCharacter End character of the span
     * @return Range of (line, startCharacter) - (line, endCharacter)
     */
    public static Range lineSpan(int line, int startCharacter, int endCharacter) {
        return of(line, startCharacter, line, endCharacter);
    }

    /**
     * @param offset Offset from (0, 0)
     * @return Range of (0, 0) - (offset)
     */
    public static Range offset(Position offset) {
        return new RangeAdapter()
                .startLine(0)
                .startCharacter(0)
                .endLine(offset.getLine())
                .endCharacter(offset.getCharacter())
                .build();
    }

    /**
     * @param offset Offset from (offset.line, 0)
     * @return Range of (offset.line, 0) - (offset)
     */
    public static Range lineOffset(Position offset) {
        return new RangeAdapter()
                .startLine(offset.getLine())
                .startCharacter(0)
                .endLine(offset.getLine())
                .endCharacter(offset.getCharacter())
                .build();
    }

    /**
     * @param startLine Range start line
     * @param startCharacter Range start character
     * @param endLine Range end line
     * @param endCharacter Range end character
     * @return Range of (startLine, startCharacter) - (endLine, endCharacter)
     */
    public static Range of(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new RangeAdapter()
                .startLine(startLine)
                .startCharacter(startCharacter)
                .endLine(endLine)
                .endCharacter(endCharacter)
                .build();
    }

    /**
     * @return This range adapter, with the start/end characters incremented by one
     */
    public RangeAdapter shiftRight() {
        return this.shiftRight(1);
    }

    /**
     * @param offset Offset to shift
     * @return This range adapter, with the start/end characters incremented by {@code offset}
     */
    public RangeAdapter shiftRight(int offset) {
        this.startCharacter += offset;
        this.endCharacter += offset;

        return this;
    }

    /**
     * @return This range adapter, with start/end lines incremented by one, and the start/end
     *  characters span shifted to begin at 0
     */
    public RangeAdapter shiftNewLine() {
        this.startLine = this.startLine + 1;
        this.endLine = this.endLine + 1;

        int charDiff = this.endCharacter - this.startCharacter;
        this.startCharacter = 0;
        this.endCharacter = charDiff;

        return this;
    }

    /**
     * @param startLine The start line for the range
     * @return The updated range adapter
     */
    public RangeAdapter startLine(int startLine) {
        this.startLine = startLine;
        return this;
    }

    /**
     * @param startCharacter The start character for the range
     * @return The updated range adapter
     */
    public RangeAdapter startCharacter(int startCharacter) {
        this.startCharacter = startCharacter;
        return this;
    }

    /**
     * @param endLine The end line for the range
     * @return The updated range adapter
     */
    public RangeAdapter endLine(int endLine) {
        this.endLine = endLine;
        return this;
    }

    /**
     * @param endCharacter The end character for the range
     * @return The updated range adapter
     */
    public RangeAdapter endCharacter(int endCharacter) {
        this.endCharacter = endCharacter;
        return this;
    }

    /**
     * @return The built Range
     */
    public Range build() {
        return new Range(
                new Position(startLine, startCharacter),
                new Position(endLine, endCharacter));
    }
}
