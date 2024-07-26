/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.document;

import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import software.amazon.smithy.lsp.protocol.LspAdapter;

/**
 * In-memory representation of a text document, indexed by line, which can
 * be patched in-place.
 *
 * <p>Methods on this class will often return {@code -1} or {@code null} for
 * failure cases to reduce allocations, since these methods may be called
 * frequently.
 */
public final class Document {
    private final StringBuilder buffer;
    private int[] lineIndices;

    private Document(StringBuilder buffer, int[] lineIndices) {
        this.buffer = buffer;
        this.lineIndices = lineIndices;
    }

    /**
     * @param string String to create a document for
     * @return The created document
     */
    public static Document of(String string) {
        StringBuilder buffer = new StringBuilder(string);
        int[] lineIndicies = computeLineIndicies(buffer);
        return new Document(buffer, lineIndicies);
    }

    /**
     * @return A copy of this document
     */
    public Document copy() {
        return new Document(new StringBuilder(copyText()), lineIndices.clone());
    }

    /**
     * @param range The range to apply the edit to. Providing {@code null} will
     *              replace the text in the document
     * @param text The text of the edit to apply
     */
    public void applyEdit(Range range, String text) {
        if (range == null) {
            buffer.replace(0, buffer.length(), text);
        } else {
            Position start = range.getStart();
            Position end = range.getEnd();
            if (start.getLine() >= lineIndices.length) {
                buffer.append(text);
            } else {
                int startIndex = lineIndices[start.getLine()] + start.getCharacter();
                if (end.getLine() >= lineIndices.length) {
                    buffer.replace(startIndex, buffer.length(), text);
                } else {
                    int endIndex = lineIndices[end.getLine()] + end.getCharacter();
                    buffer.replace(startIndex, endIndex, text);
                }
            }
        }
        this.lineIndices = computeLineIndicies(buffer);
    }

    /**
     * @return The range of the document, from (0, 0) to {@link #end()}
     */
    public Range fullRange() {
        return LspAdapter.offset(end());
    }

    /**
     * @param line The line to find the index of
     * @return The index of the start of the given {@code line}, or {@code -1}
     *  if the line doesn't exist
     */
    public int indexOfLine(int line) {
        if (line >= lineIndices.length || line < 0) {
            return -1;
        }
        return lineIndices[line];
    }

    /**
     * @param idx Index to find the line of
     * @return The line that {@code idx} is within or {@code -1} if the line
     *  doesn't exist
     */
    public int lineOfIndex(int idx) {
        // TODO: Use binary search or similar
        if (idx >= length() || idx < 0) {
            return -1;
        }

        for (int line = 0; line <= lastLine() - 1; line++) {
            int currentLineIdx = indexOfLine(line);
            int nextLineIdx = indexOfLine(line + 1);
            if (idx >= currentLineIdx && idx < nextLineIdx) {
                return line;
            }
        }

        return lastLine();
    }

    /**
     * @param position The position to find the index of
     * @return The index of the position in this document, or {@code -1} if the
     *  position is out of bounds
     */
    public int indexOfPosition(Position position) {
        return indexOfPosition(position.getLine(), position.getCharacter());
    }

    /**
     * @param line The line of the index to find
     * @param character The character offset in the line
     * @return The index of the position in this document, or {@code -1} if the
     *  position is out of bounds
     */
    public int indexOfPosition(int line, int character) {
        int startLineIdx = indexOfLine(line);
        if (startLineIdx < 0) {
            // line is oob
            return -1;
        }


        int idx = startLineIdx + character;
        if (line == lastLine()) {
            if (idx >= buffer.length()) {
                // index is oob
                return -1;
            }
        } else {
            if (idx >= indexOfLine(line + 1)) {
                // index is onto next line
                return -1;
            }
        }

        return idx;
    }

    /**
     * @param index The index to find the position of
     * @return The position of the index in this document, or {@code null} if
     *  the index is out of bounds
     */
    public Position positionAtIndex(int index) {
        int line = lineOfIndex(index);
        if (line < 0) {
            return null;
        }
        int lineStart = indexOfLine(line);
        int character = index - lineStart;
        return new Position(line, character);
    }

    /**
     * @param line The line to find the end of
     * @return The index of the end of the given line, or {@code -1} if the
     *  line is out of bounds
     */
    public int lineEnd(int line) {
        if (line > lastLine() || line < 0) {
            return -1;
        }

        if (line == lastLine()) {
            return length() - 1;
        } else {
            return indexOfLine(line + 1) - 1;
        }
    }

    /**
     * @return The line number of the last line in this document
     */
    public int lastLine() {
        return lineIndices.length - 1;
    }

    /**
     * @return The end position of this document
     */
    public Position end() {
        return new Position(
                lineIndices.length - 1,
                buffer.length() - lineIndices[lineIndices.length - 1]);
    }

    /**
     * @param s The string to find the next index of
     * @param after The index to start the search at
     * @return The index of the next occurrence of {@code s} after {@code after}
     *  or {@code -1} if one doesn't exist
     */
    public int nextIndexOf(String s, int after) {
        return buffer.indexOf(s, after);
    }

    /**
     * @param s The string to find the last index of
     * @param before The index to end the search at
     * @return The index of the last occurrence of {@code s} before {@code before}
     *  or {@code -1} if one doesn't exist
     */
    public int lastIndexOf(String s, int before) {
        return buffer.lastIndexOf(s, before);
    }

    /**
     * @param c The character to find the last index of
     * @param before The index to stop the search at
     * @param line The line to search within
     * @return The index of the last occurrence of {@code c} before {@code before}
     *  on the line {@code line} or {@code -1} if one doesn't exist
     */
    int lastIndexOfOnLine(char c, int before, int line) {
        int lineIdx = indexOfLine(line);
        for (int i = before; i >= lineIdx; i--) {
            if (buffer.charAt(i) == c) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @return A reference to the text in this document
     */
    public CharSequence borrowText() {
        return buffer;
    }

    /**
     * @param range The range to borrow the text of
     * @return A reference to the text in this document within the given {@code range}
     *  or {@code null} if the range is out of bounds
     */
    public CharBuffer borrowRange(Range range) {
        int startLine = range.getStart().getLine();
        int startChar = range.getStart().getCharacter();
        int endLine = range.getEnd().getLine();
        int endChar = range.getEnd().getCharacter();

        // TODO: Maybe make this return the whole thing, thing up to an index, or thing after an
        //  index if one of the indicies is out of bounds.
        int startLineIdx = indexOfLine(startLine);
        int endLineIdx = indexOfLine(endLine);
        if (startLineIdx < 0 || endLineIdx < 0) {
            return null;
        }

        int startIdx = startLineIdx + startChar;
        int endIdx = endLineIdx + endChar;
        if (startIdx > buffer.length() || endIdx > buffer.length()) {
            return null;
        }

        return CharBuffer.wrap(buffer, startIdx, endIdx);
    }

    /**
     * @param position The position within the token to borrow
     * @return A reference to the token that the given {@code position} is
     *  within, or {@code null} if the position is not within a token
     */
    public CharBuffer borrowToken(Position position) {
        int idx = indexOfPosition(position);
        if (idx < 0) {
            return null;
        }

        char atIdx = buffer.charAt(idx);
        // Not a token
        if (!Character.isLetterOrDigit(atIdx) && atIdx != '_') {
            return null;
        }

        int startIdx = idx;
        while (startIdx >= 0) {
            char c = buffer.charAt(startIdx);
            if (Character.isLetterOrDigit(c) || c == '_') {
                startIdx--;
            } else {
                break;
            }
        }

        int endIdx = idx;
        while (endIdx < buffer.length()) {
            char c = buffer.charAt(endIdx);
            if (Character.isLetterOrDigit(c) || c == '_') {
                endIdx++;
            } else {
                break;
            }
        }

        return CharBuffer.wrap(buffer, startIdx + 1, endIdx);
    }

    /**
     * @param position The position within the id to borrow
     * @return A reference to the id that the given {@code position} is
     *  within, or {@code null} if the position is not within an id
     */
    public CharBuffer borrowId(Position position) {
        DocumentId id = copyDocumentId(position);
        if (id == null) {
            return null;
        }
        return id.borrowIdValue();
    }

    /**
     * @param line The line to borrow
     * @return A reference to the text in the given line, or {@code null} if
     *  the line doesn't exist
     */
    public CharBuffer borrowLine(int line) {
        if (line >= lineIndices.length || line < 0) {
            return null;
        }

        int lineStart = indexOfLine(line);
        if (line + 1 >= lineIndices.length) {
            return CharBuffer.wrap(buffer, lineStart, buffer.length());
        }

        return CharBuffer.wrap(buffer, lineStart, indexOfLine(line + 1));
    }

    /**
     * @param start The index of the start of the span to borrow
     * @param end The end of the index of the span to borrow (exclusive)
     * @return A reference to the text within the indicies {@code start} and
     *  {@code end}, or {@code null} if the span is out of bounds or start > end
     */
    public CharBuffer borrowSpan(int start, int end) {
        if (start < 0 || end < 0) {
            return null;
        }

        // end is exclusive
        if (end > buffer.length() || start > end) {
            return null;
        }

        return CharBuffer.wrap(buffer, start, end);
    }

    /**
     * @return A copy of the text of this document
     */
    public String copyText() {
        return buffer.toString();
    }

    /**
     * @param range The range to copy the text of
     * @return A copy of the text in this document within the given {@code range}
     *  or {@code null} if the range is out of bounds
     */
    public String copyRange(Range range) {
        CharBuffer borrowed = borrowRange(range);
        if (borrowed == null) {
            return null;
        }

        return borrowed.toString();
    }

    /**
     * @param position The position within the token to copy
     * @return A copy of the token that the given {@code position} is within,
     *  or {@code null} if the position is not within a token
     */
    public String copyToken(Position position) {
        CharSequence token = borrowToken(position);
        if (token == null) {
            return null;
        }
        return token.toString();
    }

    /**
     * @param position The position within the id to copy
     * @return A copy of the id that the given {@code position} is
     *  within, or {@code null} if the position is not within an id
     */
    public String copyId(Position position) {
        CharBuffer id = borrowId(position);
        if (id == null) {
            return null;
        }
        return id.toString();
    }

    /**
     * @param position The position within the id to get
     * @return A new id that the given {@code position} is
     *  within, or {@code null} if the position is not within an id
     */
    public DocumentId copyDocumentId(Position position) {
        int idx = indexOfPosition(position);
        if (idx < 0) {
            return null;
        }

        char atIdx = buffer.charAt(idx);
        if (!isIdChar(atIdx)) {
            return null;
        }

        boolean hasHash = false;
        boolean hasDollar = false;
        boolean hasDot = false;
        int startIdx = idx;
        while (startIdx >= 0) {
            char c = buffer.charAt(startIdx);
            if (isIdChar(c)) {
                switch (c) {
                    case '#':
                        hasHash = true;
                        break;
                    case '$':
                        hasDollar = true;
                        break;
                    case '.':
                        hasDot = true;
                        break;
                    default:
                        break;
                }
                startIdx -= 1;
            } else {
                break;
            }
        }

        int endIdx = idx;
        while (endIdx < buffer.length()) {
            char c = buffer.charAt(endIdx);
            if (isIdChar(c)) {
                switch (c) {
                    case '#':
                        hasHash = true;
                        break;
                    case '$':
                        hasDollar = true;
                        break;
                    case '.':
                        hasDot = true;
                        break;
                    default:
                        break;
                }

                endIdx += 1;
            } else {
                break;
            }
        }


        // TODO: This can be improved to do some extra validation, like if
        //  there's more than 1 hash or $, its invalid. Additionally, we
        //  should only give a type of *WITH_MEMBER if the position is on
        //  the member itself. We will probably need to add some logic or
        //  keep track of the member itself in order to properly match the
        //  RELATIVE_WITH_MEMBER type in handlers.
        DocumentId.Type type;
        if (hasHash && hasDollar) {
            type = DocumentId.Type.ABSOLUTE_WITH_MEMBER;
        } else if (hasHash) {
            type = DocumentId.Type.ABSOLUTE_ID;
        } else if (hasDollar) {
            type = DocumentId.Type.RELATIVE_WITH_MEMBER;
        } else if (hasDot) {
            type = DocumentId.Type.NAMESPACE;
        } else {
            type = DocumentId.Type.ID;
        }

        int actualStartIdx = startIdx + 1; // because we go past the actual start in the loop
        CharBuffer wrapped = CharBuffer.wrap(buffer, actualStartIdx, endIdx); // endIdx here is non-inclusive
        Position start = positionAtIndex(actualStartIdx);
        Position end = positionAtIndex(endIdx - 1); // because we go pas the actual end in the loop
        Range range = new Range(start, end);
        return new DocumentId(type, wrapped, range);
    }

    private static boolean isIdChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '.';
    }

    /**
     * @param line The line to copy
     * @return A copy of the text in the given line, or {@code null} if the line
     *  doesn't exist
     */
    public String copyLine(int line) {
        CharBuffer borrowed = borrowLine(line);
        if (borrowed == null) {
            return null;
        }
        return borrowed.toString();
    }

    /**
     * @param start The index of the start of the span to copy
     * @param end The index of the end of the span to copy
     * @return A copy of the text within the indicies {@code start} and
     *  {@code end}, or {@code null} if the span is out of bounds or start > end
     */
    public String copySpan(int start, int end) {
        CharBuffer borrowed = borrowSpan(start, end);
        if (borrowed == null) {
            return null;
        }
        return borrowed.toString();
    }

    /**
     * @return The length of the document
     */
    public int length() {
        return buffer.length();
    }

    /**
     * @param index The index to get the character at
     * @return The character at the given index, or {@code \u0000} if one
     *  doesn't exist
     */
    char charAt(int index) {
        if (index < 0 || index >= length()) {
            return '\u0000';
        }
        return buffer.charAt(index);
    }

    // Adapted from String::split
    private static int[] computeLineIndicies(StringBuilder buffer) {
        int matchCount = 0;
        int off = 0;
        int next;
        // Have to box sadly, unless there's some IntArray I'm not aware of. Maybe IntBuffer
        List<Integer> indicies = new ArrayList<>();
        indicies.add(0);
        // This works with \r\n line breaks by basically forgetting about the \r, since we don't actually
        // care about the content of the line
        while ((next = buffer.indexOf("\n", off)) != -1) {
            indicies.add(next + 1);
            off = next + 1;
            ++matchCount;
        }
        return indicies.stream().mapToInt(Integer::intValue).toArray();
    }
}
