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
    private static final EnumSet<Syntax.Statement.Type> MEMBER_TYPES = EnumSet.of(
            Syntax.Statement.Type.MemberDef,
            Syntax.Statement.Type.EnumMemberDef,
            Syntax.Statement.Type.ElidedMemberDef,
            Syntax.Statement.Type.InlineMemberDef,
            Syntax.Statement.Type.NodeMemberDef
    );
    private static final EnumSet<Syntax.Statement.Type> INLINE_TYPES = EnumSet.of(
            Syntax.Statement.Type.ForResource,
            Syntax.Statement.Type.Mixins,
            Syntax.Statement.Type.Block,
            Syntax.Statement.Type.TraitApplication,
            Syntax.Statement.Type.MemberDef,
            Syntax.Statement.Type.ElidedMemberDef
    );

    /**
     * @return A list of DocumentSymbol
     */
    public List<Either<SymbolInformation, DocumentSymbol>> handle() {
        List<Either<SymbolInformation, DocumentSymbol>> result = new ArrayList<>();
        addSymbols((symbol) -> result.add(Either.forRight(symbol)));
        return result;
    }

    private void addSymbols(Consumer<DocumentSymbol> consumer) {
        DocumentSymbol parent = null;

        var listIterator = statements.listIterator();
        while (listIterator.hasNext()) {
            var statement = listIterator.next();
            if (statement instanceof Syntax.Statement.Namespace namespace) {
                consumer.accept(namespaceSymbol(namespace));
            } else if (statement instanceof Syntax.Statement.ShapeDef shapeDef) {
                parent = rootSymbol(shapeDef);
                consumer.accept(parent);
            } else if (parent != null && MEMBER_TYPES.contains(statement.type())) {
                if (statement instanceof Syntax.Statement.InlineMemberDef inlineMemberDef) {
                    inlineMemberSymbol(listIterator, parent, inlineMemberDef);
                } else {
                    addMemberSymbol(parent, statement);
                }
            }
        }
    }

    private DocumentSymbol namespaceSymbol(Syntax.Statement.Namespace namespace) {
        return new DocumentSymbol(
                namespace.namespace().stringValue(),
                SymbolKind.Namespace,
                document.rangeBetween(namespace.start(), namespace.end()),
                document.rangeBetween(namespace.namespace().start(), namespace.namespace().end())
        );
    }

    private DocumentSymbol rootSymbol(Syntax.Statement.ShapeDef shapeDef) {
        return new DocumentSymbol(
                shapeDef.shapeName().stringValue(),
                getSymbolKind(shapeDef),
                document.rangeBetween(shapeDef.start(), shapeDef.end()),
                document.rangeBetween(shapeDef.shapeName().start(), shapeDef.shapeName().end())
        );
    }

    private static SymbolKind getSymbolKind(Syntax.Statement.ShapeDef shapeDef) {
        return switch (shapeDef.shapeType().stringValue()) {
            case "enum", "intEnum" -> SymbolKind.Enum;
            default -> SymbolKind.Class;
        };
    }

    private void addMemberSymbol(DocumentSymbol parent, Syntax.Statement statement) {
        if (parent.getChildren() == null) {
            parent.setChildren(new ArrayList<>());
        }

        DocumentSymbol memberSymbol = switch (statement) {
            case Syntax.Statement.MemberDef memberDef -> memberDefSymbol(memberDef);

            case Syntax.Statement.EnumMemberDef enumMemberDef -> enumMemberDefSymbol(enumMemberDef);

            case Syntax.Statement.ElidedMemberDef elidedMemberDef -> elidedMemberDefSymbol(elidedMemberDef);

            case Syntax.Statement.NodeMemberDef nodeMemberDef -> nodeMemberDefSymbol(nodeMemberDef);

            default -> null;
        };

        if (memberSymbol != null) {
            parent.getChildren().add(memberSymbol);
        }
    }

    private DocumentSymbol memberDefSymbol(Syntax.Statement.MemberDef memberDef) {
        return new DocumentSymbol(
                memberDef.name().stringValue(),
                SymbolKind.Field,
                document.rangeBetween(memberDef.start(), memberDef.end()),
                document.rangeBetween(memberDef.name().start(), memberDef.name().end()),
                memberDef.target() == null ? null : memberDef.target().stringValue()
        );
    }

    private DocumentSymbol enumMemberDefSymbol(Syntax.Statement.EnumMemberDef enumMemberDef) {
        return new DocumentSymbol(
                enumMemberDef.name().stringValue(),
                SymbolKind.EnumMember,
                document.rangeBetween(enumMemberDef.start(), enumMemberDef.end()),
                document.rangeBetween(enumMemberDef.name().start(), enumMemberDef.name().end())
        );
    }

    private DocumentSymbol elidedMemberDefSymbol(Syntax.Statement.ElidedMemberDef elidedMemberDef) {
        var range = document.rangeBetween(elidedMemberDef.start(), elidedMemberDef.end());
        return new DocumentSymbol(
                elidedMemberDef.name().stringValue(),
                SymbolKind.Field,
                range,
                range
        );
    }

    private DocumentSymbol nodeMemberDefSymbol(Syntax.Statement.NodeMemberDef nodeMemberDef) {
        var range = document.rangeBetween(nodeMemberDef.start(), nodeMemberDef.end());
        return new DocumentSymbol(
                nodeMemberDef.name().stringValue(),
                SymbolKind.Property,
                range,
                range
        );
    }

    private void inlineMemberSymbol(
            ListIterator<Syntax.Statement> listIterator,
            DocumentSymbol parent,
            Syntax.Statement.InlineMemberDef inlineMemberDef
    ) {
        if (parent.getChildren() == null) {
            parent.setChildren(new ArrayList<>());
        }

        var inlineSymbol = new DocumentSymbol(
                inlineMemberDef.name().stringValue(),
                SymbolKind.Property,
                document.rangeBetween(inlineMemberDef.start(), inlineMemberDef.end()),
                document.rangeBetween(inlineMemberDef.name().start(), inlineMemberDef.name().end())
        );
        parent.getChildren().add(inlineSymbol);

        List<DocumentSymbol> children = new ArrayList<>();

        while (listIterator.hasNext()) {
            var statement = listIterator.next();

            if (!INLINE_TYPES.contains(statement.type())) {
                listIterator.previous();
                break;
            }

            if (statement instanceof Syntax.Statement.MemberDef memberDef) {
                children.add(memberDefSymbol(memberDef));
            } else if (statement instanceof Syntax.Statement.ElidedMemberDef elidedMemberDef) {
                children.add(elidedMemberDefSymbol(elidedMemberDef));
            }
        }

        if (!children.isEmpty()) {
            inlineSymbol.setChildren(children);
        }
    }
}
