/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.document.syntax;

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
        Syntax.IdlParse parse = Syntax.parseIdl(Document.of(text));
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

    @ParameterizedTest
    @MethodSource("brokenProvider")
    public void broken(String desc, String text, List<String> expectedErrorMessages, List<Syntax.Statement.Type> expectedTypes) {
        if (desc.equals("trait missing member value")) {
            System.out.println();
        }
        Syntax.IdlParse parse = Syntax.parseIdl(Document.of(text));
        List<String> errorMessages = parse.errors().stream().map(Syntax.Err::message).toList();
        List<Syntax.Statement.Type> types = parse.statements().stream()
                .map(Syntax.Statement::type)
                .filter(type -> type != Syntax.Statement.Type.Block)
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
                    List.of(Syntax.Statement.Type.ShapeDef, Syntax.Statement.Type.EnumMemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "enum missing }",
                    "enum Foo {BAR",
                    List.of("expected }"),
                    List.of(Syntax.Statement.Type.ShapeDef, Syntax.Statement.Type.EnumMemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "regular shape missing {",
                    "structure Foo\nbar: String}",
                    List.of("expected {"),
                    List.of(Syntax.Statement.Type.ShapeDef, Syntax.Statement.Type.MemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "regular shape missing }",
                    "structure Foo {bar: String",
                    List.of("expected }"),
                    List.of(Syntax.Statement.Type.ShapeDef, Syntax.Statement.Type.MemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "op with inline missing {",
                    "operation Foo\ninput := {}}",
                    List.of("expected {"),
                    List.of(Syntax.Statement.Type.ShapeDef, Syntax.Statement.Type.InlineMemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "op with inline missing }",
                    "operation Foo{input:={}",
                    List.of("expected }"),
                    List.of(Syntax.Statement.Type.ShapeDef, Syntax.Statement.Type.InlineMemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "node shape with missing {",
                    "resource Foo\nidentifiers:{}}",
                    List.of("expected {"),
                    List.of(Syntax.Statement.Type.ShapeDef, Syntax.Statement.Type.NodeMemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "node shape with missing }",
                    "service Foo{operations:[]",
                    List.of("expected }"),
                    List.of(Syntax.Statement.Type.ShapeDef, Syntax.Statement.Type.NodeMemberDef)
            ),
            new InvalidSyntaxTestCase(
                    "apply missing @",
                    "apply Foo",
                    List.of("expected trait or block"),
                    List.of(Syntax.Statement.Type.Apply)
            ),
            new InvalidSyntaxTestCase(
                    "apply missing }",
                    "apply Foo {@bar",
                    List.of("expected }"),
                    List.of(Syntax.Statement.Type.Apply, Syntax.Statement.Type.TraitApplication)
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
                            Syntax.Statement.Type.InlineMemberDef,
                            Syntax.Statement.Type.TraitApplication,
                            Syntax.Statement.Type.MemberDef)
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
        Syntax.IdlParse parse = Syntax.parseIdl(Document.of(text));
        List<Syntax.Statement.Type> actualTypes = parse.statements().stream()
                .map(Syntax.Statement::type)
                .filter(type -> type != Syntax.Statement.Type.Block)
                .toList();
        assertThat(actualTypes, contains(types));
    }
}
