/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.List;
import java.util.function.Consumer;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.lsp.syntax.Syntax;

public record DocumentSymbolHandler(Document document, List<Syntax.Statement> statements) {
    /**
     * @return A list of DocumentSymbol
     */
    public List<Either<SymbolInformation, DocumentSymbol>> handle() {
        return statements.stream()
                .mapMulti(this::addSymbols)
                .toList();
    }

    private void addSymbols(Syntax.Statement statement, Consumer<Either<SymbolInformation, DocumentSymbol>> consumer) {
        switch (statement) {
            case Syntax.Statement.TraitApplication app -> addSymbol(consumer, app.id(), SymbolKind.Class);

            case Syntax.Statement.ShapeDef def -> addSymbol(consumer, def.shapeName(), SymbolKind.Class);

            case Syntax.Statement.EnumMemberDef def -> addSymbol(consumer, def.name(), SymbolKind.Enum);

            case Syntax.Statement.ElidedMemberDef def -> addSymbol(consumer, def.name(), SymbolKind.Property);

            case Syntax.Statement.MemberDef def -> {
                addSymbol(consumer, def.name(), SymbolKind.Property);
                if (def.target() != null) {
                    addSymbol(consumer, def.target(), SymbolKind.Class);
                }
            }
            default -> {
            }
        }
    }

    private void addSymbol(
            Consumer<Either<SymbolInformation, DocumentSymbol>> consumer,
            Syntax.Ident ident,
            SymbolKind symbolKind
    ) {
        Range range = LspAdapter.identRange(ident, document);
        if (range == null) {
            return;
        }

        DocumentSymbol symbol = new DocumentSymbol(ident.stringValue(), symbolKind, range, range);
        consumer.accept(Either.forRight(symbol));
    }
}
