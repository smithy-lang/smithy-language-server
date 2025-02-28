/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import static software.amazon.smithy.lsp.protocol.LspAdapter.identRange;

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

        List<DocumentSymbol> children = new ArrayList<>();

        while (listIterator.nextIndex() <= lastMemberIndex) {
            var statement = listIterator.next();

            switch (statement) {
                case Syntax.Statement.MemberDef memberDef -> {
                    children.add(memberDefSymbol(memberDef));
                }

                case Syntax.Statement.EnumMemberDef enumMemberDef -> {
                    children.add(enumMemberDefSymbol(enumMemberDef));
                }

                case Syntax.Statement.ElidedMemberDef elidedMemberDef -> {
                    children.add(elidedMemberDefSymbol(elidedMemberDef));
                }

                case Syntax.Statement.NodeMemberDef nodeMemberDef -> {
                    children.add(nodeMemberDefSymbol(nodeMemberDef));
                }

                case Syntax.Statement.InlineMemberDef inlineMemberDef -> {
                    children.add(inlineMemberSymbol(listIterator, inlineMemberDef));
                }

                default -> {
                }
            }
        }

        if (!children.isEmpty()) {
            parent.setChildren(children);
        }
    }

    private DocumentSymbol namespaceSymbol(Syntax.Statement.Namespace namespace) {
        var range = document.rangeBetween(namespace.start(), namespace.end());
        var selectionRange = identRange(namespace.namespace(), document);
        return new DocumentSymbol(
                namespace.namespace().stringValue(),
                SymbolKind.Namespace,
                range,
                selectionRange
        );
    }

    private DocumentSymbol rootSymbol(Syntax.Statement.ShapeDef shapeDef) {
        var symbolKind = getSymbolKind(shapeDef);
        var range = document.rangeBetween(shapeDef.start(), shapeDef.end());
        var selectionRange = identRange(shapeDef.shapeName(), document);
        return new DocumentSymbol(
                shapeDef.shapeName().stringValue(),
                symbolKind,
                range,
                selectionRange
        );
    }

    private static SymbolKind getSymbolKind(Syntax.Statement.ShapeDef shapeDef) {
        return switch (shapeDef.shapeType().stringValue()) {
            case "enum", "intEnum" -> SymbolKind.Enum;
            default -> SymbolKind.Class;
        };
    }

    private DocumentSymbol memberDefSymbol(Syntax.Statement.MemberDef memberDef) {
        var range = document.rangeBetween(memberDef.start(), memberDef.end());
        var selectionRange = identRange(memberDef.name(), document);
        var detail = memberDef.target() == null
                ? null
                : memberDef.target().stringValue();

        return new DocumentSymbol(
                memberDef.name().stringValue(),
                SymbolKind.Field,
                range,
                selectionRange,
                detail
        );
    }

    private DocumentSymbol enumMemberDefSymbol(Syntax.Statement.EnumMemberDef enumMemberDef) {
        var range = document.rangeBetween(enumMemberDef.start(), enumMemberDef.end());
        var selectionRange = identRange(enumMemberDef.name(), document);
        return new DocumentSymbol(
                enumMemberDef.name().stringValue(),
                SymbolKind.EnumMember,
                range,
                selectionRange
        );
    }

    private DocumentSymbol elidedMemberDefSymbol(Syntax.Statement.ElidedMemberDef elidedMemberDef) {
        var range = document.rangeBetween(elidedMemberDef.start(), elidedMemberDef.end());
        return new DocumentSymbol(
                "$" + elidedMemberDef.name().stringValue(),
                SymbolKind.Field,
                range,
                range
        );
    }

    private DocumentSymbol nodeMemberDefSymbol(Syntax.Statement.NodeMemberDef nodeMemberDef) {
        var range = document.rangeBetween(nodeMemberDef.start(), nodeMemberDef.end());
        var selectionRange = identRange(nodeMemberDef.name(), document);
        String detail = null;
        if (nodeMemberDef.value() instanceof Syntax.Ident ident) {
            detail = ident.stringValue();
        }

        return new DocumentSymbol(
                nodeMemberDef.name().stringValue(),
                SymbolKind.Property,
                range,
                selectionRange,
                detail
        );
    }

    private DocumentSymbol inlineMemberSymbol(
            ListIterator<Syntax.Statement> listIterator,
            Syntax.Statement.InlineMemberDef inlineMemberDef
    ) {
        var range = document.rangeBetween(inlineMemberDef.start(), inlineMemberDef.end());
        var selectionRange = identRange(inlineMemberDef.name(), document);
        var inlineSymbol = new DocumentSymbol(
                inlineMemberDef.name().stringValue(),
                SymbolKind.Property,
                range,
                selectionRange
        );

        addMemberSymbols(listIterator, inlineSymbol);
        return inlineSymbol;
    }
}
