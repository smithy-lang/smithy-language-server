/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.syntax;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static software.amazon.smithy.lsp.document.DocumentTest.safeString;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.smithy.lsp.TextWithPositions;
import software.amazon.smithy.lsp.document.Document;

public class IdlParserTest {
    @Test
    public void parses() {
        String text = """
                string Foo
                @tags(["foo"])
                structure Bar {
                    baz: String
                }
                """;

        assertTypesEqual(text,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.TraitApplication,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.MemberDef);
    }

    @Test
    public void parsesStatements() {
        String text = """
                $version: "2"
                metadata foo = [{ bar: 2 }]
                namespace com.foo
                
                use com.bar#baz
                
                @baz
                structure Foo {
                    @baz
                    bar: String
                }
                
                enum Bar {
                    BAZ = "BAZ"
                }
                """;

        assertTypesEqual(text,
                Syntax.Statement.Type.Control,
                Syntax.Statement.Type.Metadata,
                Syntax.Statement.Type.Namespace,
                Syntax.Statement.Type.Use,
                Syntax.Statement.Type.TraitApplication,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.TraitApplication,
                Syntax.Statement.Type.MemberDef,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.EnumMemberDef);
    }

    @Test
    public void parsesMixinsAndForResource() {
        String text = """
                structure Foo with [Mix] {}
                structure Bar for Resource {}
                structure Baz for Resource with [Mix] {}
                structure Bux with [One, Two, Three] {}
                """;

        assertTypesEqual(text,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.Mixins,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.ForResource,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.ForResource,
                Syntax.Statement.Type.Mixins,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.Mixins);
    }

    @Test
    public void parsesOp() {
        String text = """
                operation One {}
                operation Two {
                    input: Input
                }
                operation Three {
                    input: Input
                    output: Output
                }
                operation Four {
                    input: Input
                    errors: [Err]
                }
                """;

        assertTypesEqual(text,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.MemberDef,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.MemberDef,
                Syntax.Statement.Type.MemberDef,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.MemberDef,
                Syntax.Statement.Type.NodeMemberDef);
    }

    @Test
    public void parsesOpInline() {
        String text = """
                operation One {
                    input := {
                        foo: String
                    }
                    output := {
                        @foo
                        foo: String
                    }
                }
                operation Two {
                    input := for Foo {
                        foo: String
                    }
                    output := with [Bar] {
                        bar: String
                    }
                }
                operation Three {
                    input := for Foo with [Bar, Baz] {}
                }
                operation Four {
                    input := @foo {}
                }
                """;

        assertTypesEqual(text,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.InlineMemberDef,
                Syntax.Statement.Type.MemberDef,
                Syntax.Statement.Type.InlineMemberDef,
                Syntax.Statement.Type.TraitApplication,
                Syntax.Statement.Type.MemberDef,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.InlineMemberDef,
                Syntax.Statement.Type.ForResource,
                Syntax.Statement.Type.MemberDef,
                Syntax.Statement.Type.InlineMemberDef,
                Syntax.Statement.Type.Mixins,
                Syntax.Statement.Type.MemberDef,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.InlineMemberDef,
                Syntax.Statement.Type.ForResource,
                Syntax.Statement.Type.Mixins,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.InlineMemberDef,
                Syntax.Statement.Type.TraitApplication);
    }

    @Test
    public void parsesOpInlineWithTraits() {
        String text = safeString("""
                operation Op {
                    input := @foo {
                        foo: Foo
                    }
                    output := {}
                }""");
        assertTypesEqual(text,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.InlineMemberDef,
                Syntax.Statement.Type.TraitApplication,
                Syntax.Statement.Type.MemberDef,
                Syntax.Statement.Type.InlineMemberDef);
    }

    @Test
    public void parsesServiceAndResource() {
        String text = """
                service Foo {
                    version: "2024-08-15
                    operations: [
                        Op1
                        Op2
                    ]
                    errors: [
                        Err1
                        Err2
                    ]
                }
                resource Bar {
                    identifiers: { id: String }
                    properties: { prop: String }
                }
                """;

        assertTypesEqual(text,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.NodeMemberDef,
                Syntax.Statement.Type.NodeMemberDef,
                Syntax.Statement.Type.NodeMemberDef,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.NodeMemberDef,
                Syntax.Statement.Type.NodeMemberDef);
    }

    @Test
    public void ignoresComments() {
        String text = """
                // one
                $version: "2" // two
                
                namespace com.foo // three
                // four
                use com.bar#baz // five
                
                // six
                @baz // seven
                structure Foo // eight
                { // nine
                // ten
                    bar: String // eleven
                } // twelve
                
                enum Bar // thirteen
                { // fourteen
                    // fifteen
                    BAR // sixteen
                } // seventeen
                service Baz // eighteen
                { // nineteen
                // twenty
                    version: "" // twenty one
                } // twenty two
                """;

        assertTypesEqual(text,
                Syntax.Statement.Type.Control,
                Syntax.Statement.Type.Namespace,
                Syntax.Statement.Type.Use,
                Syntax.Statement.Type.TraitApplication,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.MemberDef,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.EnumMemberDef,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.NodeMemberDef);
    }

    @Test
    public void defaultAssignments() {
        String text = """
                structure Foo {
                    one: One = ""
                    two: Two = 2
                    three: Three = false
                    four: Four = []
                    five: Five = {}
                }
                """;

        assertTypesEqual(text,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.MemberDef,
                Syntax.Statement.Type.MemberDef,
                Syntax.Statement.Type.MemberDef,
                Syntax.Statement.Type.MemberDef,
                Syntax.Statement.Type.MemberDef);
    }

    @Test
    public void stringKeysInTraits() {
        String text = """
                @foo(
                    "bar": "baz"
                )
                """;
        Syntax.IdlParseResult parse = Syntax.parseIdl(Document.of(text));
        assertThat(parse.statements(), hasSize(1));
        assertThat(parse.statements().get(0), instanceOf(Syntax.Statement.TraitApplication.class));

        Syntax.Statement.TraitApplication traitApplication = (Syntax.Statement.TraitApplication) parse.statements().get(0);
        var nodeTypes = NodeParserTest.getNodeTypes(traitApplication.value());

        assertThat(nodeTypes, contains(
                Syntax.Node.Type.Kvps,
                Syntax.Node.Type.Kvp,
                Syntax.Node.Type.Str,
                Syntax.Node.Type.Str));
    }

    @Test
    public void goodControlWithEmptyString() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                %$operationInputSuffix: ""%
                %$operationOutputSuffix: "   "%
               """);
        Document document = Document.of(text.text());
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var statements = parse.statements();
        var positions = text.positions();
        assertThat(statements, hasSize(3));

        assertTypesEqual(text.text(),
                Syntax.Statement.Type.Control,
                Syntax.Statement.Type.Control,
                Syntax.Statement.Type.Control
                );
        assertThat(document.copySpan(statements.get(1).start, statements.get(1).end), equalTo(
                "$operationInputSuffix: \"\"".trim()));
        assertThat(document.copySpan(statements.get(2).start, statements.get(2).end), equalTo(
                "$operationOutputSuffix: \"   \"".trim()));
    }

    @Test
    public void badControlWithoutColon() {
        String text = """
                $version  2
                """;
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var statements = parse.statements();
        assertThat(statements, hasSize(1));
        assertThat(document.copySpan(statements.get(0).start, statements.get(0).end), equalTo("$version  2"));
    }

    @Test
    public void goodTraitWithNodeDef() {
        String text = """
               @integration(
                    requestParameters: {
                        "param1": "a"
                    }
               )
               """;
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var statements = parse.statements();
        var trait = (Syntax.Statement.TraitApplication) statements.get(0);
        assertThat(document.copySpan(trait.start, trait.end), equalTo(
                ("""
                @integration(
                     requestParameters: {
                         "param1": "a"
                     }
                )
                """).trim()));
        var value = (Syntax.Node.Kvps)trait.value();
        assertThat(document.copySpan(value.start, value.end).trim(), equalTo(
                    ("""
               (
                    requestParameters: {
                        "param1": "a"
                    }
               """).trim()));
    }

    @Test
    public void goodTraitWithEmptyDef() {
        String text ="@integration()";
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);

        var trait = (Syntax.Statement.TraitApplication) parse.statements().get(0);
        var value = (Syntax.Node.Kvps)trait.value();
        assertThat(document.copySpan(trait.start, trait.end), equalTo("@integration()"));
        assertThat(document.copySpan(value.start, value.end), equalTo("("));
    }

    @Test
    public void goodTraitWithStringKeyAndKvpsValue() {
        String text = """
               @integration(
                    "foo" :{
                        "param1": "a"
                    }
               )
               """;
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var trait = (Syntax.Statement.TraitApplication) parse.statements().get(0);
        var kvps = (Syntax.Node.Kvps)trait.value();
        assertThat(parse.statements(), hasSize(1));
        assertThat(document.copySpan(trait.start, trait.end), equalTo(("""
               @integration(
                    "foo" :{
                        "param1": "a"
                    }
               )
               """).trim()));
        assertThat(document.copySpan(kvps.start, kvps.end), equalTo(("""
             (      
                  "foo" :{
                      "param1": "a"
                  }
              """)));
    }

    @Test
    public void goodTraitWithStrOnly() {
        String text = "@integration(\"foo bar\")";
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var trait = (Syntax.Statement.TraitApplication) parse.statements().get(0);
        var str = (Syntax.Node.Str)trait.value();
        assertThat(parse.statements(), hasSize(1));
        assertThat(document.copySpan(trait.start, trait.end), equalTo("@integration(\"foo bar\")"));
        assertThat(document.copySpan(str.start, str.end), equalTo("(\"foo bar\""));
    }

    @Test
    public void goodTraitWithNestedKvps() {
        String text = """
               @integration({
                    "abc": {
                        "abc": {
                            "abc": "abc"
                        },
                        "def": "def"
                    }
               })
               """;
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var trait = (Syntax.Statement.TraitApplication) parse.statements().get(0);
        Syntax.Node.Obj firstObj = (Syntax.Node.Obj)trait.value();
        Syntax.Node.Kvps firstKvps = firstObj.kvps();
        assertThat(document.copySpan(firstKvps.start, firstKvps.end), equalTo(("""
               { 
                    "abc": {
                        "abc": {
                            "abc": "abc"
                        },
                        "def": "def"
                    }
               }
                """).trim()));
        Syntax.Node.Obj secondObj = (Syntax.Node.Obj)firstKvps.kvps().get(0).value();
        Syntax.Node.Kvps secondKvps = secondObj.kvps();
        assertThat(document.copySpan(secondKvps.start, secondKvps.end), equalTo(("""
               {
                        "abc": {
                            "abc": "abc"
                        },
                        "def": "def"
                    }
                """).trim()));
        Syntax.Node.Obj thirdObj = (Syntax.Node.Obj)secondKvps.kvps().get(0).value();
        Syntax.Node.Kvps thirdKvps = thirdObj.kvps();
        assertThat(document.copySpan(thirdKvps.start, thirdKvps.end), equalTo(("""
               {
                            "abc": "abc"
                        }
                """).trim()));
    }

    @Test
    public void goodTraitWithNum() {
        String text = """
        @a(1)
        @b(-2)
        """;
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var statements = parse.statements();
        Syntax.Statement.TraitApplication firstTrait = (Syntax.Statement.TraitApplication) statements.get(0);
        Syntax.Statement.TraitApplication secondTrait = (Syntax.Statement.TraitApplication) statements.get(1);
        var firstTraitValue = firstTrait.value();
        var secondTraitValue = secondTrait.value();
        assertThat(document.copySpan(firstTrait.start, firstTrait.end),
                equalTo("@a(1)"));
        assertThat(document.copySpan(firstTraitValue.start, firstTraitValue.end),
                equalTo("(1"));
        assertThat(document.copySpan(secondTrait.start, secondTrait.end),
                equalTo("@b(-2)"));
        assertThat(document.copySpan(secondTraitValue.start, secondTraitValue.end),
                equalTo("(-2"));
    }

    @Test
    public void badInlineMemberHitEof() {
        String text = """
                operation foo{
                input:=
                """;
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var errors = parse.errors();
        assertThat(errors.get(0).message(), equalTo("expected {"));
    }

    @Test
    public void goodTraitWithIdent() {
        String text = "@a(b)";
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var statements = parse.statements();
        Syntax.Statement.TraitApplication trait = (Syntax.Statement.TraitApplication) statements.get(0);
        var traitValue = trait.value();
        assertThat(document.copySpan(trait.start, trait.end),
                equalTo("@a(b)"));
        assertThat(document.copySpan(traitValue.start, traitValue.end),
                equalTo("(b"));
    }

    @Test
    public void goodTraitWithEmptyValue() {
        String text = "@a()";
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var statements = parse.statements();
        Syntax.Statement.TraitApplication trait = (Syntax.Statement.TraitApplication) statements.get(0);
        var traitValue = trait.value();
        assertThat(document.copySpan(trait.start, trait.end),
                equalTo("@a()"));
        assertThat(document.copySpan(traitValue.start, traitValue.end),
                equalTo("("));
    }

    @Test
    public void badTraitWithInvalidNode() {
        String text = "@a(?)";
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var statements = parse.statements();
        Syntax.Statement.TraitApplication trait = (Syntax.Statement.TraitApplication) statements.get(0);
        Syntax.Node.Err traitValue = (Syntax.Node.Err) trait.value();
        assertThat(document.copySpan(trait.start, trait.end),
                equalTo("@a(?)"));
        assertThat(document.copySpan(traitValue.start, traitValue.end),
                equalTo("(?"));
        assertThat((traitValue.message), equalTo("unexpected token ?"));
    }

    @Test
    public void badTraitWithInvalidNodeAndUnclosed() {
        String text = "@a(?";
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var statements = parse.statements();
        Syntax.Statement.TraitApplication trait = (Syntax.Statement.TraitApplication) statements.get(0);
        Syntax.Node.Err traitValue = (Syntax.Node.Err) trait.value();
        assertThat(document.copySpan(trait.start, trait.end),
                equalTo("@a(?"));
        assertThat(document.copySpan(traitValue.start, traitValue.end),
                equalTo("(?"));
        assertThat((traitValue.message), equalTo("unexpected eof"));
    }

    @Test
    public void badTraitWithUnclosedTextBlock() {
        String text = "@a(\"\"\")";
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var statements = parse.statements();
        Syntax.Statement.TraitApplication trait = (Syntax.Statement.TraitApplication)statements.get(0);
        Syntax.Node value = trait.value();
        assertThat(value, instanceOf(Syntax.Node.Err.class));
    }

    @Test
    public void badTraitWithTextBlockKey() {
        String text = "@a(\"\"\"b\"\"\":1)";
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var statements = parse.statements();
        Syntax.Statement.TraitApplication trait = (Syntax.Statement.TraitApplication)statements.get(0);
        Syntax.Node node = trait.value();
        assertThat(node, instanceOf(Syntax.Node.Kvps.class));
        Syntax.Node.Kvps kvps = (Syntax.Node.Kvps)node;
        Syntax.Node.Kvp kvp = kvps.kvps().get(0);
        assertThat(kvp.key().stringValue(), equalTo("b"));
    }

    @Test
    public void badTraitWithNestedUnclosedKvps() {
        String text = """
                @test({
                    key1: {
                        key2: {
                            key3: {
                                key4: {
                                    key5: {
               """;
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var statements = parse.statements();
        Syntax.Statement.TraitApplication traitApplication = (Syntax.Statement.TraitApplication)statements.get(0);
        Syntax.Node.Obj obj = (Syntax.Node.Obj) traitApplication.value();
        for (int i = 1; i <= 5; i++){
            Syntax.Node.Kvps kvps = obj.kvps();
            assertThat(kvps.kvps().get(0).key().stringValue(), equalTo("key" + i));
            obj = (Syntax.Node.Obj) kvps.kvps().get(0).value();
        }
    }

    @Test
    public void badMetadataWithUnclosedArr() {
        String text = "metadata foo = [a,b,c";
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        Syntax.Statement.Metadata metadata = (Syntax.Statement.Metadata ) parse.statements().get(0);
        assertThat(document.copySpan(metadata.start, metadata.end), equalTo("metadata foo = [a,b,c"));
        assertThat(document.copySpan(metadata.value.start, metadata.value.end), equalTo("[a,b,c"));
    }

    @Test
    public void badMetadataWithoutEqual() {
        String text = """
               metadata a 
               metadata b
               """;
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        Syntax.Statement.Metadata metadataOne = (Syntax.Statement.Metadata ) parse.statements().get(0);
        Syntax.Statement.Metadata metadataTwo = (Syntax.Statement.Metadata) parse.statements().get(1);
        assertThat(document.copySpan(metadataOne.start, metadataOne.end), equalTo("metadata a"));
        assertThat(document.copySpan(metadataTwo.start, metadataTwo.end), equalTo("metadata b"));
    }

    @Test
    public void goodApplyWithSingularTrait() {
        String text = "apply foo @examples ";
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        Syntax.Statement.Apply apply = (Syntax.Statement.Apply) parse.statements().get(0);
        Syntax.Statement.TraitApplication trait = (Syntax.Statement.TraitApplication) parse.statements().get(1);
        assertThat(document.copySpan(apply.start, apply.end), equalTo("apply foo"));
        assertThat(document.copySpan(trait.start, trait.end), equalTo("@examples"));
    }

    @Test
    public void badApplyWithMissingTraitMark() {
        String text = "apply foo{bar,@buz}";
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);

        Syntax.Statement.Apply apply = (Syntax.Statement.Apply) parse.statements().get(0);
        Syntax.Statement.TraitApplication trait = (Syntax.Statement.TraitApplication) parse.statements().get(3);
        assertThat(document.copySpan(apply.start, apply.end), equalTo("apply foo"));
        assertThat(document.copySpan(trait.start, trait.end), equalTo("@buz"));
    }

    @Test
    public void goodEnumShapeDef() {
        String text = """
               enum foo {
               }
               intEnum bar {
                    @test
                    a = 1
               }
               """;
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var statements = parse.statements();
        assertThat(statements, hasSize(6));
        assertThat(statements.get(0), instanceOf(Syntax.Statement.ShapeDef.class));
        assertThat(statements.get(1), instanceOf(Syntax.Statement.Block.class));
        assertThat(document.copySpan(statements.get(0).start, statements.get(0).end), equalTo("enum foo"));
        assertThat(document.copySpan(statements.get(2).start, statements.get(2).end), equalTo("intEnum bar"));
        assertThat(document.copySpan(statements.get(3).start, statements.get(3).end), equalTo("""
               {
                    @test
                    a = 1
               }"""));
        assertThat(document.copySpan(statements.get(4).start, statements.get(4).end), equalTo("@test"));
        assertThat(document.copySpan(statements.get(5).start, statements.get(5).end), equalTo("a = 1"));
    }

    @Test
    public void goodStructListMapUnion() {
        String text = """
               structure a {
               }
               list b {
               }
               map c {
               }
               union d {
               }
               """;
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var statements = parse.statements();
        assertThat(statements, hasSize(8));
        assertThat(document.copySpan(statements.get(0).start, statements.get(0).end), equalTo("structure a"));
        assertThat(document.copySpan(statements.get(2).start, statements.get(2).end), equalTo("list b"));
        assertThat(document.copySpan(statements.get(4).start, statements.get(4).end), equalTo("map c"));
        assertThat(document.copySpan(statements.get(6).start, statements.get(6).end), equalTo("union d"));
    }

    @Test
    public void goodResourceService() {
        String text = """
               resource a {
               }
               service b {
               }
               """;
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var statements = parse.statements();
        assertThat(statements, hasSize(4));
        assertThat(statements.get(0), instanceOf(Syntax.Statement.ShapeDef.class));
        assertThat(statements.get(1), instanceOf(Syntax.Statement.Block.class));
        assertThat(document.copySpan(statements.get(0).start, statements.get(0).end), equalTo("resource a"));
        assertThat(document.copySpan(statements.get(2).start, statements.get(2).end), equalTo("service b"));
    }

    @Test
    public void goodElideMember() {
        String text = "structure a {foo:$bar}";
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var statements = parse.statements();
        assertThat(statements, hasSize(4));
        assertThat(statements.get(0), instanceOf(Syntax.Statement.ShapeDef.class));
        assertThat(statements.get(1), instanceOf(Syntax.Statement.Block.class));
        assertThat(document.copySpan(statements.get(0).start, statements.get(0).end), equalTo("structure a"));
        assertThat(document.copySpan(statements.get(2).start, statements.get(2).end), equalTo("foo:"));
        assertThat(document.copySpan(statements.get(3).start, statements.get(3).end), equalTo("$bar"));
    }

    @Test
    public void badTraitWithArrWithoutLeftBrace() {
        String text = "@test(a,b,c])";
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var statements = parse.statements();
        assertThat(document.copySpan(statements.get(0).start, statements.get(0).end), equalTo("@test(a,"));
        assertThat(document.copySpan(statements.get(1).start, statements.get(1).end), equalTo("b"));
        assertThat(document.copySpan(statements.get(2).start, statements.get(2).end), equalTo("c"));
    }

    @Test
    public void badStructureWithUseStatement() {
        String text = "structure a {use abc}";
        Document document = Document.of(text);
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);
        var statements = parse.statements();
        assertThat(document.copySpan(statements.get(0).start, statements.get(0).end), equalTo("structure a"));
        assertThat(document.copySpan(statements.get(2).start, statements.get(2).end), equalTo("use abc"));
    }

    @Test
    public void traitApplicationsDontContainTrailingWhitespace() {
        var twp = TextWithPositions.from("""
                %@foo(foo: "")%
                structure Foo {
                    foo: Foo
                }
                """);
        Document document = Document.of(twp.text());
        Syntax.IdlParseResult parse = Syntax.parseIdl(document);

        assertThat(getTypes(parse), contains(
                Syntax.Statement.Type.TraitApplication,
                Syntax.Statement.Type.ShapeDef,
                Syntax.Statement.Type.MemberDef));

        Syntax.Statement traitApplication = parse.statements().get(0);
        assertThat(document.positionAtIndex(traitApplication.start()), equalTo(twp.positions()[0]));
        assertThat(document.positionAtIndex(traitApplication.end()), equalTo(twp.positions()[1]));
    }

    @ParameterizedTest
    @MethodSource("brokenProvider")
    public void broken(String desc, String text, List<String> expectedErrorMessages, List<Syntax.Statement.Type> expectedTypes) {
        Syntax.IdlParseResult parse = Syntax.parseIdl(Document.of(text));
        List<String> errorMessages = parse.errors().stream().map(Syntax.Err::message).toList();
        List<Syntax.Statement.Type> types = parse.statements().stream()
                .map(Syntax.Statement::type)
                .toList();

        assertThat(desc, errorMessages, equalTo(expectedErrorMessages));
        assertThat(desc, types, equalTo(expectedTypes));
    }

    record InvalidSyntaxTestCase(
            String description,
            String text,
            List<String> expectedErrorMessages,
            List<Syntax.Statement.Type> expectedTypes
    ) {}

    private static final List<InvalidSyntaxTestCase> INVALID_SYNTAX_TEST_CASES = List.of(
            new InvalidSyntaxTestCase(
                    "empty",
                    "",
                    List.of(),
                    List.of()
            ),
            new InvalidSyntaxTestCase(
                    "just shape type",
                    "structure",
                    List.of("expected identifier"),
                    List.of(Syntax.Statement.Type.Incomplete)
            ),
            new InvalidSyntaxTestCase(
                    "missing resource",
                    "string Foo for",
                    List.of("expected identifier"),
                    List.of(Syntax.Statement.Type.ShapeDef, Syntax.Statement.Type.ForResource)
            ),
            new InvalidSyntaxTestCase(
                    "unexpected line break",
                    "string \nstring Foo",
                    List.of("expected identifier"),
                    List.of(Syntax.Statement.Type.Incomplete, Syntax.Statement.Type.ShapeDef)
            ),
            new InvalidSyntaxTestCase(
                    "unexpected token",
                    "string [",
                    List.of("expected identifier"),
                    List.of(Syntax.Statement.Type.Incomplete)
            ),
            new InvalidSyntaxTestCase(
                    "unexpected token 2",
                    "string Foo [",
                    List.of("expected identifier"),
                    List.of(Syntax.Statement.Type.ShapeDef)
            ),
            new InvalidSyntaxTestCase(
                    "enum missing {",
                    "enum Foo\nBAR}",
                    List.of("expected {"),
                    List.of(
                            Syntax.Statement.Type.ShapeDef,
                            Syntax.Statement.Type.Block,
                            Syntax.Statement.Type.EnumMemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "enum missing }",
                    "enum Foo {BAR",
                    List.of("expected }"),
                    List.of(
                            Syntax.Statement.Type.ShapeDef,
                            Syntax.Statement.Type.Block,
                            Syntax.Statement.Type.EnumMemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "enum using invalid value",
                    "enum Foo {?}",
                    List.of("unexpected token ? expected trait or member", "expected member or trait"),
                    List.of(Syntax.Statement.Type.ShapeDef, Syntax.Statement.Type.Block)
            ),
            new InvalidSyntaxTestCase(
                    "regular shape missing {",
                    "structure Foo\nbar: String}",
                    List.of("expected {"),
                    List.of(
                            Syntax.Statement.Type.ShapeDef,
                            Syntax.Statement.Type.Block,
                            Syntax.Statement.Type.MemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "regular shape missing }",
                    "structure Foo {bar: String",
                    List.of("expected }"),
                    List.of(
                            Syntax.Statement.Type.ShapeDef,
                            Syntax.Statement.Type.Block,
                            Syntax.Statement.Type.MemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "regular shape missing :",
                    "structure Foo {bar String}",
                    List.of("expected :"),
                    List.of(Syntax.Statement.Type.ShapeDef, Syntax.Statement.Type.Block, Syntax.Statement.Type.MemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "regular shape missing assignment",
                    """
                            structure Foo {
                                foo
                                bar
                            }
                            """,
                    List.of("expected :","expected :"),
                    List.of(Syntax.Statement.Type.ShapeDef, Syntax.Statement.Type.Block, Syntax.Statement.Type.MemberDef, Syntax.Statement.Type.MemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "regular shape with invalid member",
                    "structure Foo {?}",
                    List.of("unexpected token ? expected trait or member", "expected member or trait"),
                    List.of(Syntax.Statement.Type.ShapeDef, Syntax.Statement.Type.Block)
            ),
            new InvalidSyntaxTestCase(
                    "op with inline missing {",
                    "operation Foo\ninput := {}}",
                    List.of("expected {"),
                    List.of(
                            Syntax.Statement.Type.ShapeDef,
                            Syntax.Statement.Type.Block,
                            Syntax.Statement.Type.InlineMemberDef,
                            Syntax.Statement.Type.Block)
            ),
            new InvalidSyntaxTestCase(
                    "op with inline missing }",
                    "operation Foo{input:={}",
                    List.of("expected }"),
                    List.of(
                            Syntax.Statement.Type.ShapeDef,
                            Syntax.Statement.Type.Block,
                            Syntax.Statement.Type.InlineMemberDef,
                            Syntax.Statement.Type.Block)
            ),
            new InvalidSyntaxTestCase(
                    "node shape with missing {",
                    "resource Foo\nidentifiers:{}}",
                    List.of("expected {"),
                    List.of(
                            Syntax.Statement.Type.ShapeDef,
                            Syntax.Statement.Type.Block,
                            Syntax.Statement.Type.NodeMemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "node shape with missing }",
                    "service Foo{operations:[]",
                    List.of("expected }"),
                    List.of(
                            Syntax.Statement.Type.ShapeDef,
                            Syntax.Statement.Type.Block,
                            Syntax.Statement.Type.NodeMemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "node shape with missing :",
                    "service Foo{operations {}}",
                    List.of("expected :"),
                    List.of(Syntax.Statement.Type.ShapeDef, Syntax.Statement.Type.Block, Syntax.Statement.Type.NodeMemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "node shape with missing node",
                    "service Foo{bar:}",
                    List.of("expected node"),
                    List.of(Syntax.Statement.Type.ShapeDef, Syntax.Statement.Type.Block, Syntax.Statement.Type.NodeMemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "node shape missing assignment",
                            """
                            service Foo{
                                a
                                b
                            }
                    """,
                    List.of("expected :", "expected :"),
                    List.of(Syntax.Statement.Type.ShapeDef, Syntax.Statement.Type.Block, Syntax.Statement.Type.NodeMemberDef, Syntax.Statement.Type.NodeMemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "apply missing @",
                    "apply Foo",
                    List.of("expected trait or block"),
                    List.of(Syntax.Statement.Type.Apply)
            ),
            new InvalidSyntaxTestCase(
                    "apply missing trait in block",
                    "apply Foo {@bar,buz}",
                    List.of("expected trait", "expected identifier"),
                    List.of(Syntax.Statement.Type.Apply, Syntax.Statement.Type.Block, Syntax.Statement.Type.TraitApplication, Syntax.Statement.Type.Incomplete)
            ),
            new InvalidSyntaxTestCase(
                    "apply missing }",
                    "apply Foo {@bar",
                    List.of("expected }"),
                    List.of(
                            Syntax.Statement.Type.Apply,
                            Syntax.Statement.Type.Block,
                            Syntax.Statement.Type.TraitApplication)
            ),
            new InvalidSyntaxTestCase(
                    "trait missing member value",
                    "@foo(bar: )\nstring Foo",
                    List.of("expected value"),
                    List.of(Syntax.Statement.Type.TraitApplication, Syntax.Statement.Type.ShapeDef)
            ),
            new InvalidSyntaxTestCase(
                    "inline with member missing target",
                    """
                    operation Op {
                        input :=
                            @tags([])
                            {
                            foo:\s
                            }
                    }""",
                    List.of("expected identifier"),
                    List.of(
                            Syntax.Statement.Type.ShapeDef,
                            Syntax.Statement.Type.Block,
                            Syntax.Statement.Type.InlineMemberDef,
                            Syntax.Statement.Type.TraitApplication,
                            Syntax.Statement.Type.Block,
                            Syntax.Statement.Type.MemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "invalid mixin identifier",
                    """
                    structure Foo with [123] {}
                    """,
                    List.of("expected identifier"),
                    List.of(
                            Syntax.Statement.Type.ShapeDef,
                            Syntax.Statement.Type.Mixins,
                            Syntax.Statement.Type.Block)
            ),
            new InvalidSyntaxTestCase(
                    "mixin missing []",
                    """
                    structure Foo with abc {}
                    """,
                    List.of("expected [", "expected ]"),
                    List.of(
                            Syntax.Statement.Type.ShapeDef,
                            Syntax.Statement.Type.Mixins,
                            Syntax.Statement.Type.Block)
            ),
            new InvalidSyntaxTestCase(
                    "invalid mixin identifier missing []",
                    """
                    structure Foo with 123, abc {}
                    """,
                    List.of("expected [", "expected identifier", "expected ]"),
                    List.of(
                            Syntax.Statement.Type.ShapeDef,
                            Syntax.Statement.Type.Mixins,
                            Syntax.Statement.Type.Block)
            ),
            new InvalidSyntaxTestCase(
                    "operation using member value without :",
                    """
                    operation Op {
                        input
                        output
                    }""",
                    List.of("expected :", "expected :"),
                    List.of(
                            Syntax.Statement.Type.ShapeDef,
                            Syntax.Statement.Type.Block,
                            Syntax.Statement.Type.MemberDef,
                            Syntax.Statement.Type.MemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "trait use unexpected key",
                    """
                            @integration(String:
                    """,
                    List.of("unexpected token "),
                    List.of(
                            Syntax.Statement.Type.TraitApplication)
            ),
            new InvalidSyntaxTestCase(
                    "control without value",
                    """
                            $version
                            $operationInputSuffix "Request"
                            $operationInputSuffix: "Request"
                    """,
                    List.of("expected :", "expected :"),
                    List.of(
                            Syntax.Statement.Type.Control,
                            Syntax.Statement.Type.Control,
                            Syntax.Statement.Type.Control)
            ),
            new InvalidSyntaxTestCase(
                    "metadata without equal",
                    """
                            metadata bar
                            structure foo{}
                    """,
                    List.of("expected ="),
                    List.of(
                            Syntax.Statement.Type.Metadata,
                            Syntax.Statement.Type.ShapeDef,
                            Syntax.Statement.Type.Block)
            )
    );

    private static Stream<Arguments> brokenProvider() {
        return INVALID_SYNTAX_TEST_CASES.stream().map(invalidSyntaxTestCase -> Arguments.of(
                invalidSyntaxTestCase.description,
                invalidSyntaxTestCase.text,
                invalidSyntaxTestCase.expectedErrorMessages,
                invalidSyntaxTestCase.expectedTypes));
    }

    private static void assertTypesEqual(String text, Syntax.Statement.Type... types) {
        Syntax.IdlParseResult parse = Syntax.parseIdl(Document.of(text));
        var actualTypes = getTypes(parse);
        assertThat(actualTypes, contains(types));
    }

    private static List<Syntax.Statement.Type> getTypes(Syntax.IdlParseResult parse) {
        return parse.statements().stream()
                .map(Syntax.Statement::type)
                .filter(type -> type != Syntax.Statement.Type.Block)
                .toList();
    }
}
