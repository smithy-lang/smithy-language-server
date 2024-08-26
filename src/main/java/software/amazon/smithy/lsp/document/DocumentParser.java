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
import software.amazon.smithy.lsp.document.syntax.Syntax;
import software.amazon.smithy.lsp.document.syntax.SyntaxQuery;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.model.SourceLocation;

/**
 * Essentially a wrapper around a list of {@link Syntax.Statement}, to map
 * them into the current "Document*" objects used by the rest of the server,
 * until we replace those too.
 */
public final class DocumentParser {
    private final Document document;
    private final List<Syntax.Statement> statements;

    private DocumentParser(Document document, List<Syntax.Statement> statements) {
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
                    if (memberDef.target() != null) {
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
        int statementIndex = SyntaxQuery.findStatementIndex(statements, position);
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
        int documentIndex = document.indexOfPosition(position);
        if (documentIndex < 0) {
            return DocumentPositionContext.OTHER;
        }
        int statementIndex = SyntaxQuery.findStatementIndex(statements, documentIndex);
        if (statementIndex < 0) {
            return DocumentPositionContext.OTHER;
        }
        return switch (statements.get(statementIndex)) {
            case Syntax.Statement.TraitApplication ignored -> DocumentPositionContext.TRAIT;
            case Syntax.Statement.ShapeDef ignored         -> DocumentPositionContext.SHAPE_DEF;
            case Syntax.Statement.Mixins ignored           -> DocumentPositionContext.MIXIN;
            case Syntax.Statement.Use use
                    when use.use().isIn(documentIndex)     -> DocumentPositionContext.USE_TARGET;
            case Syntax.Statement.MemberDef memberDef
                    when memberDef.inTarget(documentIndex) -> DocumentPositionContext.MEMBER_TARGET;
            default -> DocumentPositionContext.OTHER;
        };
    }
}
