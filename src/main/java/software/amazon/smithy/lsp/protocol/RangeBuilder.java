/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.protocol;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Builder for constructing LSP's {@link Range}.
 */
public final class RangeBuilder {
    private int startLine;
    private int startCharacter;
    private int endLine;
    private int endCharacter;

    /**
     * @return This range adapter, with the start/end characters incremented by one
     */
    public RangeBuilder shiftRight() {
        return this.shiftRight(1);
    }

    /**
     * @param offset Offset to shift
     * @return This range adapter, with the start/end characters incremented by {@code offset}
     */
    public RangeBuilder shiftRight(int offset) {
        this.startCharacter += offset;
        this.endCharacter += offset;

        return this;
    }

    /**
     * @return This range adapter, with start/end lines incremented by one, and the start/end
     *  characters span shifted to begin at 0
     */
    public RangeBuilder shiftNewLine() {
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
    public RangeBuilder startLine(int startLine) {
        this.startLine = startLine;
        return this;
    }

    /**
     * @param startCharacter The start character for the range
     * @return The updated range adapter
     */
    public RangeBuilder startCharacter(int startCharacter) {
        this.startCharacter = startCharacter;
        return this;
    }

    /**
     * @param endLine The end line for the range
     * @return The updated range adapter
     */
    public RangeBuilder endLine(int endLine) {
        this.endLine = endLine;
        return this;
    }

    /**
     * @param endCharacter The end character for the range
     * @return The updated range adapter
     */
    public RangeBuilder endCharacter(int endCharacter) {
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
