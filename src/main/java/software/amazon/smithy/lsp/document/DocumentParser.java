/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.document;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.lsp.syntax.Syntax;
import software.amazon.smithy.lsp.syntax.SyntaxSearch;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.utils.SimpleParser;

/**
 * Essentially a wrapper around a list of {@link Syntax.Statement}, to map
 * them into the current "Document*" objects used by the rest of the server,
 * until we replace those too.
 */
public final class DocumentParser extends SimpleParser {
    private final Document document;
    private final List<Syntax.Statement> statements;

    private DocumentParser(Document document, List<Syntax.Statement> statements) {
        super(document.borrowText());
        this.document = document;
        this.statements = statements;
    }

    static DocumentParser of(String text) {
        return DocumentParser.forDocument(Document.of(text));
    }

    /**
     * @param document Document to create a parser for
     * @return A parser for the given document
     */
    public static DocumentParser forDocument(Document document) {
        Syntax.IdlParse parse = Syntax.parseIdl(document);
        return new DocumentParser(document, parse.statements());
    }

    /**
     * @param document Document to create a parser for
     * @param statements The statements the parser should use
     * @return The parser for the given document and statements
     */
    public static DocumentParser forStatements(Document document, List<Syntax.Statement> statements) {
        return new DocumentParser(document, statements);
    }

    /**
     * @return The {@link DocumentNamespace} for the underlying document, or
     *  {@code null} if it couldn't be found
     */
    public DocumentNamespace documentNamespace() {
        for (Syntax.Statement statement : statements) {
            if (statement instanceof Syntax.Statement.Namespace namespace) {
                Range range = namespace.rangeIn(document);
                String namespaceValue = namespace.namespace().copyValueFrom(document);
                return new DocumentNamespace(range, namespaceValue);
            }
        }
        return null;
    }

    /**
     * @return The {@link DocumentImports} for the underlying document, or
     *  {@code null} if they couldn't be found
     */
    public DocumentImports documentImports() {
        Set<String> imports;
        for (int i = 0; i < statements.size(); i++) {
            Syntax.Statement statement = statements.get(i);
            if (statement instanceof Syntax.Statement.Use firstUse) {
                imports = new HashSet<>();
                imports.add(firstUse.use().copyValueFrom(document));
                Range useRange = firstUse.rangeIn(document);
                Position start = useRange.getStart();
                Position end = useRange.getEnd();
                i++;
                while (i < statements.size()) {
                    statement = statements.get(i);
                    if (statement instanceof Syntax.Statement.Use use) {
                        imports.add(use.use().copyValueFrom(document));
                        end = use.rangeIn(document).getEnd();
                        i++;
                    } else {
                        break;
                    }
                }
                return new DocumentImports(new Range(start, end), imports);
            }
        }
        return null;
    }

    /**
     * @return A map of start position to {@link DocumentShape} for each shape
     * and/or shape reference in the document.
     */
    public Map<Position, DocumentShape> documentShapes() {
        Map<Position, DocumentShape> documentShapes = new HashMap<>();
        for (Syntax.Statement statement : statements) {
            switch (statement) {
                case Syntax.Statement.ShapeDef shapeDef -> {
                    String shapeName = shapeDef.shapeName().copyValueFrom(document);
                    Range range = shapeDef.shapeName().rangeIn(document);
                    var shape = new DocumentShape(range, shapeName, DocumentShape.Kind.DefinedShape, null);
                    documentShapes.put(range.getStart(), shape);
                }
                case Syntax.Statement.MemberDef memberDef -> {
                    String shapeName = memberDef.name().copyValueFrom(document);
                    Range range = memberDef.name().rangeIn(document);
                    DocumentShape target = null;
                    if (memberDef.target() != null && !memberDef.target().isEmpty()) {
                        String targetName = memberDef.target().copyValueFrom(document);
                        Range targetRange = memberDef.target().rangeIn(document);
                        target = new DocumentShape(targetRange, targetName, DocumentShape.Kind.Targeted, null);
                        documentShapes.put(targetRange.getStart(), target);
                    }
                    var shape = new DocumentShape(range, shapeName, DocumentShape.Kind.DefinedMember, target);
                    documentShapes.put(range.getStart(), shape);
                }
                case Syntax.Statement.ElidedMemberDef elidedMemberDef -> {
                    String shapeName = elidedMemberDef.name().copyValueFrom(document);
                    Range range = elidedMemberDef.rangeIn(document);
                    var shape = new DocumentShape(range, shapeName, DocumentShape.Kind.Elided, null);
                    documentShapes.put(range.getStart(), shape);
                }
                case Syntax.Statement.EnumMemberDef enumMemberDef -> {
                    String shapeName = enumMemberDef.name().copyValueFrom(document);
                    Range range = enumMemberDef.rangeIn(document);
                    var shape = new DocumentShape(range, shapeName, DocumentShape.Kind.DefinedMember, null);
                    documentShapes.put(range.getStart(), shape);
                }
                default -> {
                }
            }
        }
        return documentShapes;
    }

    /**
     * @return The {@link DocumentVersion} for the underlying document, or
     *  {@code null} if it couldn't be found
     */
    public DocumentVersion documentVersion() {
        for (Syntax.Statement statement : statements) {
            if (statement instanceof Syntax.Statement.Control control
                && control.value() instanceof Syntax.Node.Str str) {
                String key = control.key().copyValueFrom(document);
                if (key.equals("version")) {
                    String version = str.copyValueFrom(document);
                    Range range = control.rangeIn(document);
                    return new DocumentVersion(range, version);
                }
            } else if (statement instanceof Syntax.Statement.Namespace) {
                break;
            }
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
        int position = document.indexOfPosition(LspAdapter.toPosition(sourceLocation));
        int statementIndex = SyntaxSearch.statementIndex(statements, position);
        if (statementIndex < 0) {
            return null;
        }

        if (statements.get(statementIndex) instanceof Syntax.Statement.TraitApplication traitApplication) {
            Range range = traitApplication.id().rangeIn(document);
            range.getStart().setCharacter(range.getStart().getCharacter() - 1); // include @
            return range;
        }
        return null;
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
     * @return The underlying document
     */
    public Document getDocument() {
        return this.document;
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

    /**
     * Finds a contiguous range of non-whitespace characters starting from the given SourceLocation.
     * If the sourceLocation happens to be a whitespace character, it returns a Range representing that column.
     *
     * Here is how it works:
     * 1. We first jump to sourceLocation. If we can't, we return null.
     * 2. We then check if the sourceLocation is a whitespace character. If it is, we return that column.
     * 3. We then find the start of the contiguous range by traversing backwards until a whitespace character is found.
     * 4. We then find the end of the contiguous range by traversing forwards until a whitespace character is found.
     *
     * @param sourceLocation The starting location to search from.
     * @return A Range object representing the contiguous non-whitespace characters,
     *         or null if not found.
     */
    public Range findContiguousRange(SourceLocation sourceLocation) {
        if (!jumpToSource(sourceLocation)) {
            return null;
        }

        Position startPosition = LspAdapter.toPosition(sourceLocation);
        int startLine = startPosition.getLine();
        int startColumn = startPosition.getCharacter();

        if (isWs()) {
            return new Range(
                        new Position(startLine, startColumn),
                        // As per LSP docs the end postion is exclusive,
                        // so adding `+1` makes it highlight the startColumn.
                        new Position(startLine, startColumn + 1)
                    );
        }

        // The column offset is NOT the position, but an offset from the sourceLocation column.
        // This is required as the `isWs` uses offset, and not position to determine whether the token at the offset
        // is whitespace or not.
        int startColumnOffset = 0;
        // Find the start of the contiguous range by traversing backwards until a whitespace.
        while (startColumn + startColumnOffset > 0 && !isWs(startColumnOffset - 1)) {
            startColumnOffset--;
        }

        int endColumn = startColumn;
        // Find the end of the contiguous range
        while (!isEof() && !isWs()) {
            endColumn++;
            skip();
        }

        // We add one to the column as it helps us shift it to correct character.
        return new Range(
                new Position(startLine, startColumn + startColumnOffset),
                new Position(startLine, endColumn));
    }
}
