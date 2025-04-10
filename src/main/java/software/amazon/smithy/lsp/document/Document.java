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
import software.amazon.smithy.lsp.syntax.Syntax;

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
        int low = 0;
        int up = lastLine();

        while (low <= up) {
            int mid = (low + up) / 2;
            int midLineIdx = lineIndices[mid];
            int midLineEndIdx = lineEndUnchecked(mid);
            if (idx >= midLineIdx && idx <= midLineEndIdx) {
                return mid;
            } else if (idx < midLineIdx) {
                up = mid - 1;
            } else {
                low = mid + 1;
            }
        }

        return -1;
    }

    private int lineEndUnchecked(int line) {
        if (line == lastLine()) {
            return length() - 1;
        } else {
            return lineIndices[line + 1] - 1;
        }
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
     * @param start The start character offset
     * @param end The end character offset
     * @return The range between the two given offsets
     */
    public Range rangeBetween(int start, int end) {
        if (end < start || start < 0) {
            return null;
        }

        // The start is inclusive, so it should be within the bounds of the document
        Position startPos = positionAtIndex(start);
        if (startPos == null) {
            return null;
        }

        Position endPos;
        if (end == length()) {
            endPos = end();
        } else {
            endPos = positionAtIndex(end);
        }

        return new Range(startPos, endPos);
    }

    /**
     * @param item The item to get the range of
     * @return The range of the item
     */
    public Range rangeOf(Syntax.Item item) {
        return rangeBetween(item.start(), item.end());
    }

    /**
     * @param token The token to get the range of
     * @return The range of the token, excluding enclosing ""
     */
    public Range rangeOfValue(Syntax.Node.Str token) {
        int lineStart = indexOfLine(token.lineNumber());
        if (lineStart < 0) {
            return null;
        }

        int startChar = token.start() - lineStart;
        int endChar = token.end() - lineStart;

        if (token.type() == Syntax.Node.Type.Str) {
            startChar += 1;
            endChar -= 1;
        }

        return new Range(new Position(token.lineNumber(), startChar), new Position(token.lineNumber(), endChar));
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
        return new Position(lastLine(), lastColExclusive());
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
     * @return A reference to the text in this document
     */
    public CharSequence borrowText() {
        return buffer;
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
        int start = indexOfPosition(range.getStart());

        int end;
        Position endPosition = range.getEnd();
        if (endPosition.getLine() == lastLine() && endPosition.getCharacter() == lastColExclusive()) {
            end = length();
        } else {
            end = indexOfPosition(range.getEnd());
        }
        return copySpan(start, end);
    }

    private int lastColExclusive() {
        return length() - lineIndices[lastLine()];
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

        boolean isMember = false;
        int startIdx = idx;
        while (startIdx >= 0) {
            char c = buffer.charAt(startIdx);
            if (!isIdChar(c)) {
                break;
            }

            if (c == '$') {
                isMember = true;
            }

            startIdx -= 1;
        }

        int endIdx = idx;
        while (endIdx < buffer.length()) {
            char c = buffer.charAt(endIdx);
            if (!isIdChar(c)) {
                break;
            }

            if (!isMember && c == '$') {
                break;
            }

            endIdx += 1;
        }

        DocumentId.Type type;
        if (isMember) {
            type = DocumentId.Type.MEMBER;
        } else {
            type = DocumentId.Type.ROOT;
        }

        // Add one since we went past the start when breaking from the loop.
        // Not necessary for endIdx, because we want it to be one past the last
        // character.
        int startCharIdx = startIdx + 1;
        CharBuffer wrapped = CharBuffer.wrap(buffer, startCharIdx, endIdx);
        Range range = rangeBetween(startCharIdx, endIdx);
        return new DocumentId(type, wrapped, range);
    }

    private static boolean isIdChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '.';
    }

    /**
     * @param start The index of the start of the span to copy
     * @param end The index of the end of the span to copy
     * @return A copy of the text within the indicies {@code start} and
     *  {@code end}, or {@code null} if the span is out of bounds or start > end
     */
    public String copySpan(int start, int end) {
        if (start < 0 || end < 0) {
            return null;
        }

        // end is exclusive
        if (end > buffer.length() || start > end) {
            return null;
        }

        return CharBuffer.wrap(buffer, start, end).toString();
    }

    /**
     * @return The length of the document
     */
    public int length() {
        return buffer.length();
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
