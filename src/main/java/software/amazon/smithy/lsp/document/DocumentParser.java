/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.document;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.loader.ParserUtils;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.StringNode;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.utils.SimpleParser;

/**
 * 'Parser' that uses the line-indexed property of the underlying {@link Document}
 * to jump around the document, parsing small pieces without needing to start at
 * the beginning.
 *
 * <p>This isn't really a parser as much as it is a way to get very specific
 * information about a document, such as whether a given position lies within
 * a trait application, a member target, etc. It won't tell you whether syntax
 * is valid.
 *
 * <p>Methods on this class often return {@code -1} or {@code null} for failure
 * cases to reduce allocations, since these methods may be called frequently.
 */
public final class DocumentParser extends SimpleParser {
    private final Document document;

    private DocumentParser(Document document) {
        super(document.borrowText());
        this.document = document;
    }

    static DocumentParser of(String text) {
        return DocumentParser.forDocument(Document.of(text));
    }

    /**
     * @param document Document to create a parser for
     * @return A parser for the given document
     */
    public static DocumentParser forDocument(Document document) {
        return new DocumentParser(document);
    }

    /**
     * @return The {@link DocumentNamespace} for the underlying document, or
     *  {@code null} if it couldn't be found
     */
    public DocumentNamespace documentNamespace() {
        int namespaceStartIdx = firstIndexOfWithOnlyLeadingWs("namespace");
        if (namespaceStartIdx < 0) {
            return null;
        }

        Position namespaceStatementStartPosition = document.positionAtIndex(namespaceStartIdx);
        if (namespaceStatementStartPosition == null) {
            // Shouldn't happen on account of the previous check
            return null;
        }
        jumpToPosition(namespaceStatementStartPosition);
        skip(); // n
        skip(); // a
        skip(); // m
        skip(); // e
        skip(); // s
        skip(); // p
        skip(); // a
        skip(); // c
        skip(); // e

        if (!isSp()) {
            return null;
        }

        sp();

        if (!isNamespaceChar()) {
            return null;
        }

        int start = position();
        while (isNamespaceChar()) {
            skip();
        }
        int end = position();
        CharSequence namespace = document.borrowSpan(start, end);

        consumeRemainingCharactersOnLine();
        Position namespaceStatementEnd = currentPosition();

        return new DocumentNamespace(new Range(namespaceStatementStartPosition, namespaceStatementEnd), namespace);
    }

    /**
     * @return The {@link DocumentImports} for the underlying document, or
     *  {@code null} if they couldn't be found
     */
    public DocumentImports documentImports() {
        // TODO: What if its 'uses', not just 'use'?
        //  Should we look for another?
        int firstUseStartIdx = firstIndexOfWithOnlyLeadingWs("use");
        if (firstUseStartIdx < 0) {
            // No use
            return null;
        }

        Position firstUsePosition = document.positionAtIndex(firstUseStartIdx);
        if (firstUsePosition == null) {
            // Shouldn't happen on account of the previous check
            return null;
        }
        rewind(firstUseStartIdx, firstUsePosition.getLine() + 1, firstUsePosition.getCharacter() + 1);

        Set<String> imports = new HashSet<>();
        Position lastUseEnd; // At this point we know there's at least one
        do {
            skip(); // u
            skip(); // s
            skip(); // e

            String id = getImport(); // handles skipping the ws
            if (id != null) {
                imports.add(id);
            }
            consumeRemainingCharactersOnLine();
            lastUseEnd = currentPosition();
            nextNonWsNonComment();
        } while (isUse());

        if (imports.isEmpty()) {
            return null;
        }

        return new DocumentImports(new Range(firstUsePosition, lastUseEnd), imports);
    }

    /**
     * @param shapes The shapes defined in the underlying document
     * @return A map of the starting positions of shapes defined or referenced
     *  in the underlying document to their corresponding {@link DocumentShape}
     */
    public Map<Position, DocumentShape> documentShapes(Set<Shape> shapes) {
        Map<Position, DocumentShape> documentShapes = new HashMap<>(shapes.size());
        for (Shape shape : shapes) {
            if (!jumpToSource(shape.getSourceLocation())) {
                continue;
            }

            DocumentShape documentShape;
            if (shape.isMemberShape()) {
                DocumentShape.Kind kind = DocumentShape.Kind.DefinedMember;
                if (is('$')) {
                    kind = DocumentShape.Kind.Elided;
                }
                documentShape = documentShape(kind);
            } else {
                skipAlpha(); // shape type
                sp();
                documentShape = documentShape(DocumentShape.Kind.DefinedShape);
            }

            documentShapes.put(documentShape.range().getStart(), documentShape);
            if (documentShape.hasMemberTarget()) {
                DocumentShape memberTarget = documentShape.targetReference();
                documentShapes.put(memberTarget.range().getStart(), memberTarget);
            }
        }
        return documentShapes;
    }

    private DocumentShape documentShape(DocumentShape.Kind kind) {
        Position start = currentPosition();
        int startIdx = position();
        if (kind == DocumentShape.Kind.Elided) {
            skip(); // '$'
            startIdx = position(); // so the name doesn't contain '$' - we need to match it later
        }
        skipIdentifier(); // shape name
        Position end = currentPosition();
        int endIdx = position();
        Range range = new Range(start, end);
        CharSequence shapeName = document.borrowSpan(startIdx, endIdx);

        // This is a bit ugly, but it avoids intermediate allocations (like a builder would require)
        DocumentShape targetReference = null;
        if (kind == DocumentShape.Kind.DefinedMember) {
            sp();
            if (is(':')) {
                skip();
                sp();
                targetReference = documentShape(DocumentShape.Kind.Targeted);
            }
        } else if (kind == DocumentShape.Kind.DefinedShape && (shapeName == null || shapeName.isEmpty())) {
            kind = DocumentShape.Kind.Inline;
        }

        return new DocumentShape(range, shapeName, kind, targetReference);
    }

    /**
     * @return The {@link DocumentVersion} for the underlying document, or
     *  {@code null} if it couldn't be found
     */
    public DocumentVersion documentVersion() {
        firstIndexOfNonWsNonComment();
        if (!is('$')) {
            return null;
        }
        while (is('$') && !isVersion()) {
            // Skip this line
            if (!jumpToLine(line())) {
                return null;
            }
            // Skip any ws and docs
            nextNonWsNonComment();
        }

        // Found a non-control statement before version.
        if (!is('$')) {
            return null;
        }

        Position start = currentPosition();
        skip(); // $
        skipAlpha(); // version
        sp();
        if (!is(':')) {
            return null;
        }
        skip(); // ':'
        sp();
        int nodeStartCharacter = column() - 1;
        CharSequence span = document.borrowSpan(position(), document.lineEnd(line() - 1) + 1);
        if (span == null) {
            return null;
        }

        // TODO: Ew
        Node node;
        try {
            node = StringNode.parseJsonWithComments(span.toString());
        } catch (Exception e) {
            return null;
        }

        if (node.isStringNode()) {
            String version = node.expectStringNode().getValue();
            int end = nodeStartCharacter + version.length() + 2; // ?
            Range range = LspAdapter.of(start.getLine(), start.getCharacter(), start.getLine(), end);
            return new DocumentVersion(range, version);
        }
        return null;
    }

    /**
     * @param sourceLocation The source location of the start of the trait
     *                       application. The filename must be the same as
     *                       the underlying document's (this is not checked),
     *                       and the position must be on the {@code @}
     * @return The range of the trait id from the {@code @} up to the trait's
     *  body or end, or null if the {@code sourceLocation} isn't on an {@code @}
     *  or there's no id next to the {@code @}
     */
    public Range traitIdRange(SourceLocation sourceLocation) {
        if (!jumpToSource(sourceLocation)) {
            return null;
        }

        if (!is('@')) {
            return null;
        }

        skip();

        while (isShapeIdChar()) {
            skip();
        }

        return new Range(LspAdapter.toPosition(sourceLocation), currentPosition());
    }

    /**
     * Jumps the parser location to the start of the given {@code line}.
     *
     * @param line The line in the underlying document to jump to
     * @return Whether the parser successfully jumped
     */
    public boolean jumpToLine(int line) {
        int idx = this.document.indexOfLine(line);
        if (idx >= 0) {
            this.rewind(idx, line + 1, 1);
            return true;
        }
        return false;
    }

    /**
     * Jumps the parser location to the given {@code source}.
     *
     * @param source The location to jump to. The filename must be the same as
     *               the underlying document's filename (this is not checked)
     * @return Whether the parser successfully jumped
     */
    public boolean jumpToSource(SourceLocation source) {
        int idx = this.document.indexOfPosition(source.getLine() - 1, source.getColumn() - 1);
        if (idx < 0) {
            return false;
        }
        this.rewind(idx, source.getLine(), source.getColumn());
        return true;
    }

    /**
     * @return The current position of the parser
     */
    public Position currentPosition() {
        return new Position(line() - 1, column() - 1);
    }

    /**
     * @return The underlying document
     */
    public Document getDocument() {
        return this.document;
    }

    /**
     * @param position The position in the document to check
     * @return The context at that position
     */
    public DocumentPositionContext determineContext(Position position) {
        // TODO: Support additional contexts
        //  Also can compute these in one pass probably.
        if (isTrait(position)) {
            return DocumentPositionContext.TRAIT;
        } else if (isMemberTarget(position)) {
            return DocumentPositionContext.MEMBER_TARGET;
        } else if (isShapeDef(position)) {
            return DocumentPositionContext.SHAPE_DEF;
        } else if (isMixin(position)) {
            return DocumentPositionContext.MIXIN;
        } else if (isUseTarget(position)) {
            return DocumentPositionContext.USE_TARGET;
        } else {
            return DocumentPositionContext.OTHER;
        }
    }

    private boolean isTrait(Position position) {
        if (!jumpToPosition(position)) {
            return false;
        }
        CharSequence line = document.borrowLine(position.getLine());
        if (line == null) {
            return false;
        }

        for (int i = position.getCharacter() - 1; i >= 0; i--) {
            char c = line.charAt(i);
            if (c == '@') {
                return true;
            }
            if (!isShapeIdChar()) {
                return false;
            }
        }
        return false;
    }

    private boolean isMixin(Position position) {
        int idx = document.indexOfPosition(position);
        if (idx < 0) {
            return false;
        }

        int lastWithIndex = document.lastIndexOf("with", idx);
        if (lastWithIndex < 0) {
            return false;
        }

        jumpToPosition(document.positionAtIndex(lastWithIndex));
        if (!isWs(-1)) {
            return false;
        }
        skip();
        skip();
        skip();
        skip();

        if (position() >= idx) {
            return false;
        }

        ws();

        if (position() >= idx) {
            return false;
        }

        if (!is('[')) {
            return false;
        }

        skip();

        while (position() < idx) {
            if (!isWs() && !isShapeIdChar() && !is(',')) {
                return false;
            }
            ws();
            skipShapeId();
            ws();
            if (is(',')) {
                skip();
                ws();
            }
        }

        return true;
    }

    private boolean isShapeDef(Position position) {
        int idx = document.indexOfPosition(position);
        if (idx < 0) {
            return false;
        }

        if (!jumpToLine(position.getLine())) {
            return false;
        }

        if (position() >= idx) {
            return false;
        }

        if (!isShapeType()) {
            return false;
        }

        skipAlpha();

        if (position() >= idx) {
            return false;
        }

        if (!isSp()) {
            return false;
        }

        sp();
        skipIdentifier();

        return position() >= idx;
    }

    private boolean isMemberTarget(Position position) {
        int idx = document.indexOfPosition(position);
        if (idx < 0) {
            return false;
        }

        int lastColonIndex = document.lastIndexOfOnLine(':', idx, position.getLine());
        if (lastColonIndex < 0) {
            return false;
        }

        if (!jumpToPosition(document.positionAtIndex(lastColonIndex))) {
            return false;
        }

        skip(); // ':'
        sp();

        if (position() >= idx) {
            return true;
        }

        skipShapeId();

        return position() >= idx;
    }

    private boolean isUseTarget(Position position) {
        int idx = document.indexOfPosition(position);
        if (idx < 0) {
            return false;
        }
        int lineStartIdx = document.indexOfLine(document.lineOfIndex(idx));

        int useIdx = nextIndexOfWithOnlyLeadingWs("use", lineStartIdx, idx);
        if (useIdx < 0) {
            return false;
        }

        jumpToPosition(document.positionAtIndex(useIdx));

        skip(); // u
        skip(); // s
        skip(); // e

        if (!isSp()) {
            return false;
        }

        sp();

        skipShapeId();

        return position() >= idx;
    }

    private boolean jumpToPosition(Position position) {
        int idx = this.document.indexOfPosition(position);
        if (idx < 0) {
            return false;
        }
        this.rewind(idx, position.getLine() + 1, position.getCharacter() + 1);
        return true;
    }

    private void skipAlpha() {
        while (isAlpha()) {
            skip();
        }
    }

    private void skipIdentifier() {
        if (isAlpha() || isUnder()) {
            skip();
        }
        while (isAlpha() || isDigit() || isUnder()) {
            skip();
        }
    }

    private boolean isIdentifierStart() {
        return isAlpha() || isUnder();
    }

    private boolean isIdentifierChar() {
        return isAlpha() || isUnder() || isDigit();
    }

    private boolean isAlpha() {
        return Character.isAlphabetic(peek());
    }

    private boolean isUnder() {
        return peek() == '_';
    }

    private boolean isDigit() {
        return Character.isDigit(peek());
    }

    private boolean isUse() {
        return is('u', 0) && is('s', 1) && is('e', 2);
    }

    private boolean isVersion() {
        return is('$', 0) && is('v', 1) && is('e', 2) && is('r', 3) && is('s', 4) && is('i', 5) && is('o', 6)
               && is('n', 7) && (is(':', 8) || is(' ', 8) || is('\t', 8));

    }

    private String getImport() {
        if (!is(' ', 0) && !is('\t', 0)) {
            // should be a space after use
            return null;
        }

        sp(); // skip space after use

        try {
            return ParserUtils.parseRootShapeId(this);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean is(char c, int offset) {
        return peek(offset) == c;
    }

    private boolean is(char c) {
        return peek() == c;
    }

    private boolean isWs() {
        return isNl() || isSp();
    }

    private boolean isNl() {
        return is('\n') || is('\r');
    }

    private boolean isSp() {
        return is(' ') || is('\t');
    }

    private boolean isWs(int offset) {
        char peeked = peek(offset);
        return switch (peeked) {
            case '\n', '\r', ' ', '\t' -> true;
            default -> false;
        };
    }

    private boolean isEof() {
        return is(EOF);
    }

    private boolean isShapeIdChar() {
        return isIdentifierChar() || is('#') || is('.') || is('$');
    }

    private void skipShapeId() {
        while (isShapeIdChar()) {
            skip();
        }
    }

    private boolean isShapeIdChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '.';
    }

    private boolean isNamespaceChar() {
        return isIdentifierChar() || is('.');
    }

    private boolean isShapeType() {
        CharSequence token = document.borrowToken(currentPosition());
        if (token == null) {
            return false;
        }

        return switch (token.toString()) {
            case "structure", "operation", "string", "integer", "list", "map", "boolean", "enum", "union", "blob",
                    "byte", "short", "long", "float", "double", "timestamp", "intEnum", "document", "service",
                    "resource", "bigDecimal", "bigInteger" -> true;
            default -> false;
        };
    }

    private int firstIndexOfWithOnlyLeadingWs(String s) {
        return nextIndexOfWithOnlyLeadingWs(s, 0, document.length());
    }

    private int nextIndexOfWithOnlyLeadingWs(String s, int start, int end) {
        int searchFrom = start;
        int previousSearchFrom;
        do {
            int idx = document.nextIndexOf(s, searchFrom);
            if (idx < 0) {
                return -1;
            }
            int lineStart = document.lastIndexOf(System.lineSeparator(), idx) + 1;
            if (idx == lineStart) {
                return idx;
            }
            CharSequence before = document.borrowSpan(lineStart, idx);
            if (before == null) {
                return -1;
            }
            if (before.chars().allMatch(Character::isWhitespace)) {
                return idx;
            }
            previousSearchFrom = searchFrom;
            searchFrom = idx + 1;
        } while (previousSearchFrom != searchFrom && searchFrom < end);
        return -1;
    }

    private int firstIndexOfNonWsNonComment() {
        reset();
        do {
            ws();
            if (is('/')) {
                consumeRemainingCharactersOnLine();
            }
        } while (isWs());
        return position();
    }

    private void nextNonWsNonComment() {
        do {
            ws();
            if (is('/')) {
                consumeRemainingCharactersOnLine();
            }
        } while (isWs());
    }

    private void reset() {
        rewind(0, 1, 1);
    }
}
