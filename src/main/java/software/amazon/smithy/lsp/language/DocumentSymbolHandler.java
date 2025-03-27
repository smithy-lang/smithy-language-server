/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Consumer;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.syntax.Syntax;

public record DocumentSymbolHandler(Document document, List<Syntax.Statement> statements) {
    // Statement types that may appear before the start of a shape's members, which
    // we need to skip.
    private static final EnumSet<Syntax.Statement.Type> BEFORE_MEMBER_TYPES = EnumSet.of(
            Syntax.Statement.Type.ForResource,
            Syntax.Statement.Type.Mixins,
            Syntax.Statement.Type.Block
    );

    /**
     * @return A list of DocumentSymbol
     */
    public List<Either<SymbolInformation, DocumentSymbol>> handle() {
        List<Either<SymbolInformation, DocumentSymbol>> result = new ArrayList<>();
        // Passing around the list would make the code super noisy, and we'd have
        // to do Either.forRight everywhere, so use a consumer.
        addSymbols((symbol) -> result.add(Either.forRight(symbol)));
        return result;
    }

    private void addSymbols(Consumer<DocumentSymbol> consumer) {
        var listIterator = statements.listIterator();
        while (listIterator.hasNext()) {
            var statement = listIterator.next();
            if (statement instanceof Syntax.Statement.Namespace namespace) {
                consumer.accept(namespaceSymbol(namespace));
            } else if (statement instanceof Syntax.Statement.ShapeDef shapeDef) {
                var symbol = rootSymbol(shapeDef);
                consumer.accept(symbol);
                addMemberSymbols(listIterator, symbol);
            }
        }
    }

    private void addMemberSymbols(ListIterator<Syntax.Statement> listIterator, DocumentSymbol parent) {
        // We only want to collect members within the block, so we can use Block's lastMemberIndex
        // to tell us when to stop.
        int lastMemberIndex = 0;
        while (listIterator.hasNext()) {
            var statement = listIterator.next();

            if (!BEFORE_MEMBER_TYPES.contains(statement.type())) {
                // No members
                listIterator.previous();
                return;
            }

            if (statement instanceof Syntax.Statement.Block block) {
                // Update the parent's range to cover all its members
                var blockEnd = document.positionAtIndex(block.end());
                parent.getRange().setEnd(blockEnd);
                lastMemberIndex = block.lastStatementIndex();
                break;
            }
        }

        List<DocumentSymbol> children = childrenSymbols(listIterator, lastMemberIndex);
        if (!children.isEmpty()) {
            parent.setChildren(children);
        }
    }

    private List<DocumentSymbol> childrenSymbols(ListIterator<Syntax.Statement> listIterator, int lastChildIndex) {
        List<DocumentSymbol> children = new ArrayList<>();

        while (listIterator.nextIndex() <= lastChildIndex) {
            var statement = listIterator.next();

            switch (statement) {
                case Syntax.Statement.MemberDef def -> children.add(memberDefSymbol(def));

                case Syntax.Statement.EnumMemberDef def -> children.add(enumMemberDefSymbol(def));

                case Syntax.Statement.ElidedMemberDef def -> children.add(elidedMemberDefSymbol(def));

                case Syntax.Statement.NodeMemberDef def -> children.add(nodeMemberDefSymbol(def));

                case Syntax.Statement.InlineMemberDef def -> children.add(inlineMemberSymbol(listIterator, def));

                default -> {
                }
            }
        }

        return children;
    }

    private DocumentSymbol namespaceSymbol(Syntax.Statement.Namespace namespace) {
        return new DocumentSymbol(
                namespace.namespace().stringValue(),
                SymbolKind.Namespace,
                document.rangeOf(namespace),
                document.rangeOfValue(namespace.namespace())
        );
    }

    private DocumentSymbol rootSymbol(Syntax.Statement.ShapeDef shapeDef) {
        return new DocumentSymbol(
                shapeDef.shapeName().stringValue(),
                getSymbolKind(shapeDef),
                document.rangeOf(shapeDef),
                document.rangeOfValue(shapeDef.shapeName())
        );
    }

    private static SymbolKind getSymbolKind(Syntax.Statement.ShapeDef shapeDef) {
        return switch (shapeDef.shapeType().stringValue()) {
            case "enum", "intEnum" -> SymbolKind.Enum;
            case "operation", "service", "resource" -> SymbolKind.Interface;
            default -> SymbolKind.Class;
        };
    }

    private DocumentSymbol memberDefSymbol(Syntax.Statement.MemberDef memberDef) {
        var detail = memberDef.target() == null
                ? null
                : memberDef.target().stringValue();

        return new DocumentSymbol(
                memberDef.name().stringValue(),
                SymbolKind.Field,
                document.rangeOf(memberDef),
                document.rangeOfValue(memberDef.name()),
                detail
        );
    }

    private DocumentSymbol enumMemberDefSymbol(Syntax.Statement.EnumMemberDef enumMemberDef) {
        return new DocumentSymbol(
                enumMemberDef.name().stringValue(),
                SymbolKind.EnumMember,
                document.rangeOf(enumMemberDef),
                document.rangeOfValue(enumMemberDef.name())
        );
    }

    private DocumentSymbol elidedMemberDefSymbol(Syntax.Statement.ElidedMemberDef elidedMemberDef) {
        var range = document.rangeOf(elidedMemberDef);
        return new DocumentSymbol(
                "$" + elidedMemberDef.name().stringValue(),
                SymbolKind.Field,
                range,
                range
        );
    }

    private DocumentSymbol nodeMemberDefSymbol(Syntax.Statement.NodeMemberDef nodeMemberDef) {
        String detail = switch (nodeMemberDef.value()) {
            case Syntax.Ident ident -> ident.stringValue();
            case null, default -> null;
        };

        return new DocumentSymbol(
                nodeMemberDef.name().stringValue(),
                SymbolKind.Property,
                document.rangeOf(nodeMemberDef),
                document.rangeOfValue(nodeMemberDef.name()),
                detail
        );
    }

    private DocumentSymbol inlineMemberSymbol(
            ListIterator<Syntax.Statement> listIterator,
            Syntax.Statement.InlineMemberDef inlineMemberDef
    ) {
        var inlineSymbol = new DocumentSymbol(
                inlineMemberDef.name().stringValue(),
                SymbolKind.Property,
                document.rangeOf(inlineMemberDef),
                document.rangeOfValue(inlineMemberDef.name())
        );

        addMemberSymbols(listIterator, inlineSymbol);
        return inlineSymbol;
    }
}
