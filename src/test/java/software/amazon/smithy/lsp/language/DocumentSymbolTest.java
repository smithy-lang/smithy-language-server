/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static software.amazon.smithy.lsp.LspMatchers.hasText;

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
        var document = Document.of("""
                $version: "2"
                namespace com.foo

                @trait
                string myTrait

                structure Foo {
                    @required
                    bar: Bar
                
                    baz: Baz
                }
                
                operation MyOp {
                    input := {
                        foo: String
                    }
                
                    output: MyOpOutput
                }
                
                resource MyResource {
                    identifiers: {
                        myId: String
                        myOtherId: String
                    }
                    properties: {
                        myProperty: Foo
                        myOtherProperty: String
                    }
                    get: MyOp
                    operations: [
                        MyOp
                    ]
                }
                """);
        var symbols = getDocumentSymbols(document);

        assertThat(symbols, hasSize(5));

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
        assertThat(fooSymbol.getRange(), hasText(document, allOf(
                containsString("structure Foo"),
                containsString("bar: Bar"),
                containsString("baz: Baz")
        )));
        assertThat(fooSymbol.getSelectionRange(), hasText(document, equalTo("Foo")));
        assertThat(fooSymbol.getDetail(), nullValue());
        assertThat(fooSymbol.getChildren(), hasSize(2));

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
        assertThat(myOpSymbol.getRange(), hasText(document, allOf(
                containsString("operation MyOp"),
                containsString("input :="),
                containsString("output: MyOpOutput")
        )));
        assertThat(myOpSymbol.getSelectionRange(), hasText(document, equalTo("MyOp")));
        assertThat(myOpSymbol.getChildren(), hasSize(2));

        var myOpInputSymbol = myOpSymbol.getChildren().get(0);
        assertThat(myOpInputSymbol.getName(), equalTo("input"));
        assertThat(myOpInputSymbol.getKind(), equalTo(SymbolKind.Property));
        assertThat(myOpInputSymbol.getRange(), hasText(document, allOf(
                containsString("input :="),
                containsString("foo: String")
        )));
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

        var myOpOutputSymbol = myOpSymbol.getChildren().get(1);
        assertThat(myOpOutputSymbol.getName(), equalTo("output"));
        assertThat(myOpOutputSymbol.getKind(), equalTo(SymbolKind.Property));
        assertThat(myOpOutputSymbol.getRange(), hasText(document, equalTo("output: MyOpOutput")));
        assertThat(myOpOutputSymbol.getSelectionRange(), hasText(document, equalTo("output")));
        assertThat(myOpOutputSymbol.getDetail(), equalTo("MyOpOutput"));
        assertThat(myOpOutputSymbol.getChildren(), nullValue());

        var myResourceSymbol = symbols.get(4);
        assertThat(myResourceSymbol.getName(), equalTo("MyResource"));
        assertThat(myResourceSymbol.getKind(), equalTo(SymbolKind.Class));
        assertThat(myResourceSymbol.getRange(), hasText(document, allOf(
                containsString("resource MyResource"),
                containsString("myId: String"),
                containsString("get: MyOp")
        )));
        assertThat(myResourceSymbol.getSelectionRange(), hasText(document, equalTo("MyResource")));
        assertThat(myResourceSymbol.getDetail(), nullValue());
        assertThat(myResourceSymbol.getChildren(), hasSize(4));

        var myResourceIdentifiersSymbol = myResourceSymbol.getChildren().get(0);
        assertThat(myResourceIdentifiersSymbol.getName(), equalTo("identifiers"));
        assertThat(myResourceIdentifiersSymbol.getKind(), equalTo(SymbolKind.Property));
        assertThat(myResourceIdentifiersSymbol.getRange(), hasText(document, allOf(
                containsString("identifiers:"),
                containsString("myId: String"),
                containsString("myOtherId: String")
        )));
        assertThat(myResourceIdentifiersSymbol.getSelectionRange(), hasText(document, equalTo("identifiers")));
        assertThat(myResourceIdentifiersSymbol.getDetail(), nullValue());
        assertThat(myResourceIdentifiersSymbol.getChildren(), nullValue());
        var myResourcePropertiesSymbol = myResourceSymbol.getChildren().get(1);
        assertThat(myResourcePropertiesSymbol.getName(), equalTo("properties"));
        assertThat(myResourcePropertiesSymbol.getKind(), equalTo(SymbolKind.Property));
        assertThat(myResourcePropertiesSymbol.getRange(), hasText(document, allOf(
                containsString("properties:"),
                containsString("myProperty: Foo"),
                containsString("myOtherProperty: String")
        )));
        assertThat(myResourcePropertiesSymbol.getSelectionRange(), hasText(document, equalTo("properties")));
        assertThat(myResourcePropertiesSymbol.getDetail(), nullValue());
        assertThat(myResourcePropertiesSymbol.getChildren(), nullValue());
        var myResourceGetSymbol = myResourceSymbol.getChildren().get(2);
        assertThat(myResourceGetSymbol.getName(), equalTo("get"));
        assertThat(myResourceGetSymbol.getKind(), equalTo(SymbolKind.Property));
        assertThat(myResourceGetSymbol.getRange(), hasText(document, equalTo("get: MyOp")));
        assertThat(myResourceGetSymbol.getSelectionRange(), hasText(document, equalTo("get")));
        assertThat(myResourceGetSymbol.getDetail(), equalTo("MyOp"));
        assertThat(myResourceGetSymbol.getChildren(), nullValue());
        var myResourceOperationsSymbol = myResourceSymbol.getChildren().get(3);
        assertThat(myResourceOperationsSymbol.getName(), equalTo("operations"));
        assertThat(myResourceOperationsSymbol.getKind(), equalTo(SymbolKind.Property));
        assertThat(myResourceOperationsSymbol.getRange(), hasText(document, allOf(
                containsString("operations: ["),
                containsString("MyOp")
        )));
        assertThat(myResourceOperationsSymbol.getSelectionRange(), hasText(document, equalTo("operations")));
        assertThat(myResourceOperationsSymbol.getDetail(), nullValue());
        assertThat(myResourceOperationsSymbol.getChildren(), nullValue());
    }

    @Test
    public void handlesForResourceAndMixins() {
        var document = Document.of("""
                operation MyOp for MyResource with [MyMixin] {
                    input: MyOpInput
                    output := for MyResource with [MyMixin] {
                        foo: String
                        $bar
                    }
                }
                """);
        var symbols = getDocumentSymbols(document);
        assertThat(symbols.size(), equalTo(1));

        var myOpSymbol = symbols.get(0);
        assertThat(myOpSymbol.getName(), equalTo("MyOp"));
        assertThat(myOpSymbol.getKind(), equalTo(SymbolKind.Class));
        assertThat(myOpSymbol.getRange(), hasText(document, allOf(
                containsString("operation MyOp for MyResource with [MyMixin]"),
                containsString("input: MyOpInput"),
                containsString("output :="),
                containsString("$bar")
        )));
        assertThat(myOpSymbol.getSelectionRange(), hasText(document, equalTo("MyOp")));
        assertThat(myOpSymbol.getDetail(), nullValue());
        assertThat(myOpSymbol.getChildren(), hasSize(2));

        var myOpInputSymbol = myOpSymbol.getChildren().get(0);
        assertThat(myOpInputSymbol.getName(), equalTo("input"));
        assertThat(myOpInputSymbol.getKind(), equalTo(SymbolKind.Property));
        assertThat(myOpInputSymbol.getRange(), hasText(document, equalTo("input: MyOpInput")));
        assertThat(myOpInputSymbol.getSelectionRange(), hasText(document, equalTo("input")));
        assertThat(myOpInputSymbol.getDetail(), equalTo("MyOpInput"));
        assertThat(myOpInputSymbol.getChildren(), nullValue());

        var myOpOutputSymbol = myOpSymbol.getChildren().get(1);
        assertThat(myOpOutputSymbol.getName(), equalTo("output"));
        assertThat(myOpOutputSymbol.getKind(), equalTo(SymbolKind.Property));
        assertThat(myOpOutputSymbol.getRange(), hasText(document, allOf(
                containsString("output := for MyResource with [MyMixin]"),
                containsString("foo: String"),
                containsString("$bar")
        )));
        assertThat(myOpOutputSymbol.getSelectionRange(), hasText(document, equalTo("output")));
        assertThat(myOpOutputSymbol.getDetail(), nullValue());
        assertThat(myOpOutputSymbol.getChildren(), hasSize(2));

        var fooMemberSymbol = myOpOutputSymbol.getChildren().get(0);
        assertThat(fooMemberSymbol.getName(), equalTo("foo"));
        assertThat(fooMemberSymbol.getKind(), equalTo(SymbolKind.Field));
        assertThat(fooMemberSymbol.getRange(), hasText(document, equalTo("foo: String")));
        assertThat(fooMemberSymbol.getSelectionRange(), hasText(document, equalTo("foo")));
        assertThat(fooMemberSymbol.getDetail(), equalTo("String"));
        assertThat(fooMemberSymbol.getChildren(), nullValue());

        var barMemberSymbol = myOpOutputSymbol.getChildren().get(1);
        assertThat(barMemberSymbol.getName(), equalTo("$bar"));
        assertThat(barMemberSymbol.getKind(), equalTo(SymbolKind.Field));
        assertThat(barMemberSymbol.getRange(), hasText(document, equalTo("$bar")));
        assertThat(barMemberSymbol.getSelectionRange(), hasText(document, equalTo("$bar")));
        assertThat(barMemberSymbol.getDetail(), nullValue());
        assertThat(barMemberSymbol.getChildren(), nullValue());
    }

    @Test
    public void enums() {
        var document = Document.of("""
                enum MyEnum {
                    FOO
                    BAR = "bar"
                }
                
                intEnum MyIntEnum {
                    FOO
                    BAR = 1
                }
                """);
        var symbols = getDocumentSymbols(document);

        assertThat(symbols.size(), equalTo(2));
        var myEnumSymbol = symbols.get(0);
        assertThat(myEnumSymbol.getName(), equalTo("MyEnum"));
        assertThat(myEnumSymbol.getKind(), equalTo(SymbolKind.Enum));
        assertThat(myEnumSymbol.getRange(), hasText(document, allOf(
                containsString("enum MyEnum"),
                containsString("BAR = \"bar\"")
        )));
        assertThat(myEnumSymbol.getSelectionRange(), hasText(document, equalTo("MyEnum")));
        assertThat(myEnumSymbol.getDetail(), nullValue());
        assertThat(myEnumSymbol.getChildren(), hasSize(2));

        var myEnumFooSymbol = myEnumSymbol.getChildren().get(0);
        assertThat(myEnumFooSymbol.getName(), equalTo("FOO"));
        assertThat(myEnumFooSymbol.getKind(), equalTo(SymbolKind.EnumMember));
        assertThat(myEnumFooSymbol.getRange(), hasText(document, equalTo("FOO")));
        assertThat(myEnumFooSymbol.getSelectionRange(), hasText(document, equalTo("FOO")));
        assertThat(myEnumFooSymbol.getDetail(), nullValue());
        assertThat(myEnumFooSymbol.getChildren(), nullValue());

        var myEnumBarSymbol = myEnumSymbol.getChildren().get(1);
        assertThat(myEnumBarSymbol.getName(), equalTo("BAR"));
        assertThat(myEnumBarSymbol.getKind(), equalTo(SymbolKind.EnumMember));
        assertThat(myEnumBarSymbol.getRange(), hasText(document, equalTo("BAR = \"bar\"")));
        assertThat(myEnumBarSymbol.getSelectionRange(), hasText(document, equalTo("BAR")));
        assertThat(myEnumBarSymbol.getDetail(), nullValue());
        assertThat(myEnumBarSymbol.getChildren(), nullValue());

        var myIntEnumSymbol = symbols.get(1);
        assertThat(myIntEnumSymbol.getName(), equalTo("MyIntEnum"));
        assertThat(myIntEnumSymbol.getKind(), equalTo(SymbolKind.Enum));
        assertThat(myIntEnumSymbol.getRange(), hasText(document, allOf(
                containsString("intEnum MyIntEnum"),
                containsString("BAR = 1")
        )));
        assertThat(myIntEnumSymbol.getSelectionRange(), hasText(document, equalTo("MyIntEnum")));
        assertThat(myIntEnumSymbol.getDetail(), nullValue());
        assertThat(myIntEnumSymbol.getChildren(), hasSize(2));

        var myIntEnumFooSymbol = myIntEnumSymbol.getChildren().get(0);
        assertThat(myIntEnumFooSymbol.getName(), equalTo("FOO"));
        assertThat(myIntEnumFooSymbol.getKind(), equalTo(SymbolKind.EnumMember));
        assertThat(myIntEnumFooSymbol.getRange(), hasText(document, equalTo("FOO")));
        assertThat(myIntEnumFooSymbol.getSelectionRange(), hasText(document, equalTo("FOO")));
        assertThat(myIntEnumFooSymbol.getDetail(), nullValue());
        assertThat(myIntEnumFooSymbol.getChildren(), nullValue());

        var myIntEnumBarSymbol = myIntEnumSymbol.getChildren().get(1);
        assertThat(myIntEnumBarSymbol.getName(), equalTo("BAR"));
        assertThat(myIntEnumBarSymbol.getKind(), equalTo(SymbolKind.EnumMember));
        assertThat(myIntEnumBarSymbol.getRange(), hasText(document, equalTo("BAR = 1")));
        assertThat(myIntEnumBarSymbol.getSelectionRange(), hasText(document, equalTo("BAR")));
        assertThat(myIntEnumBarSymbol.getDetail(), nullValue());
        assertThat(myIntEnumBarSymbol.getChildren(), nullValue());
    }

    private static List<DocumentSymbol> getDocumentSymbols(Document document) {
        Syntax.IdlParseResult parseResult = Syntax.parseIdl(document);

        List<DocumentSymbol> symbols = new ArrayList<>();
        var handler = new DocumentSymbolHandler(document, parseResult.statements());
        for (var sym : handler.handle()) {
            symbols.add(sym.getRight());
        }
        return symbols;
    }
}
