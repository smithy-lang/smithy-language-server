/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static software.amazon.smithy.lsp.LspMatchers.hasText;
import static software.amazon.smithy.lsp.document.DocumentTest.safeString;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.SymbolKind;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.syntax.Syntax;

public class DocumentSymbolTest {
    @Test
    public void documentSymbols() {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                @trait
                string myTrait

                structure Foo {
                    @required
                    bar: Bar
                }
                
                operation MyOp {
                    input := {
                        foo: String
                    }
                }
                """);
        var symbols = getDocumentSymbols(model);

        Document document = Document.of(model);

        assertThat(symbols, hasSize(4));

        var nsSymbol = symbols.get(0);
        assertThat(nsSymbol.getName(), equalTo("com.foo"));
        assertThat(nsSymbol.getKind(), equalTo(SymbolKind.Namespace));
        assertThat(nsSymbol.getRange(), hasText(document, equalTo("namespace com.foo")));
        assertThat(nsSymbol.getSelectionRange(), hasText(document, equalTo("com.foo")));
        assertThat(nsSymbol.getDetail(), nullValue());
        assertThat(nsSymbol.getChildren(), nullValue());

        var myTraitSymbol = symbols.get(1);
        assertThat(myTraitSymbol.getName(), equalTo("myTrait"));
        assertThat(myTraitSymbol.getKind(), equalTo(SymbolKind.Class));
        assertThat(myTraitSymbol.getRange(), hasText(document, equalTo("string myTrait")));
        assertThat(myTraitSymbol.getSelectionRange(), hasText(document, equalTo("myTrait")));
        assertThat(myTraitSymbol.getDetail(), nullValue());
        assertThat(myTraitSymbol.getChildren(), nullValue());

        var fooSymbol = symbols.get(2);
        assertThat(fooSymbol.getName(), equalTo("Foo"));
        assertThat(fooSymbol.getKind(), equalTo(SymbolKind.Class));
        assertThat(fooSymbol.getRange(), hasText(document, equalTo("structure Foo")));
        assertThat(fooSymbol.getSelectionRange(), hasText(document, equalTo("Foo")));
        assertThat(fooSymbol.getDetail(), nullValue());
        assertThat(fooSymbol.getChildren(), hasSize(1));
        var barMemberSymbol = fooSymbol.getChildren().get(0);
        assertThat(barMemberSymbol.getName(), equalTo("bar"));
        assertThat(barMemberSymbol.getKind(), equalTo(SymbolKind.Field));
        assertThat(barMemberSymbol.getRange(), hasText(document, equalTo("bar: Bar")));
        assertThat(barMemberSymbol.getSelectionRange(), hasText(document, equalTo("bar")));
        assertThat(barMemberSymbol.getDetail(), equalTo("Bar"));
        assertThat(barMemberSymbol.getChildren(), nullValue());

        var myOpSymbol = symbols.get(3);
        assertThat(myOpSymbol.getName(), equalTo("MyOp"));
        assertThat(myOpSymbol.getKind(), equalTo(SymbolKind.Class));
        assertThat(myOpSymbol.getRange(), hasText(document, equalTo("operation MyOp")));
        assertThat(myOpSymbol.getSelectionRange(), hasText(document, equalTo("MyOp")));
        assertThat(myOpSymbol.getChildren(), hasSize(1));
        var myOpInputSymbol = myOpSymbol.getChildren().get(0);
        assertThat(myOpInputSymbol.getName(), equalTo("input"));
        assertThat(myOpInputSymbol.getKind(), equalTo(SymbolKind.Property));
        assertThat(myOpInputSymbol.getRange(), hasText(document, equalTo("input :=")));
        assertThat(myOpInputSymbol.getSelectionRange(), hasText(document, equalTo("input")));
        assertThat(myOpInputSymbol.getDetail(), nullValue());
        assertThat(myOpInputSymbol.getChildren(), hasSize(1));
        var myOpInputFooMemberSymbol = myOpInputSymbol.getChildren().get(0);
        assertThat(myOpInputFooMemberSymbol.getName(), equalTo("foo"));
        assertThat(myOpInputFooMemberSymbol.getKind(), equalTo(SymbolKind.Field));
        assertThat(myOpInputFooMemberSymbol.getRange(), hasText(document, equalTo("foo: String")));
        assertThat(myOpInputFooMemberSymbol.getSelectionRange(), hasText(document, equalTo("foo")));
        assertThat(myOpInputFooMemberSymbol.getDetail(), equalTo("String"));
        assertThat(myOpInputFooMemberSymbol.getChildren(), nullValue());
    }

    private static List<DocumentSymbol> getDocumentSymbols(String text) {
        Document document = Document.of(text);
        Syntax.IdlParseResult parseResult = Syntax.parseIdl(document);

        List<DocumentSymbol> symbols = new ArrayList<>();
        var handler = new DocumentSymbolHandler(document, parseResult.statements());
        for (var sym : handler.handle()) {
            symbols.add(sym.getRight());
        }
        return symbols;
    }
}
