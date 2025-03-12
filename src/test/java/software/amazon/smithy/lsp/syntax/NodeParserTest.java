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
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.syntax.shaded.prettier4j.Doc;

public class NodeParserTest {
    @Test
    public void goodEmptyObj() {
        String text = "{}";
        assertTypesEqual(text,
                Syntax.Node.Type.Obj,
                Syntax.Node.Type.Kvps);
    }

    @Test
    public void goodEmptyObjWithWs() {
        String text = "{  }";
        assertTypesEqual(text,
                Syntax.Node.Type.Obj,
                Syntax.Node.Type.Kvps);
    }

    @Test
    public void goodObjSingleKey() {
        String text = """
                {"abc": "def"}""";
        assertTypesEqual(text,
                Syntax.Node.Type.Obj,
                Syntax.Node.Type.Kvps,
                Syntax.Node.Type.Kvp,
                Syntax.Node.Type.Str,
                Syntax.Node.Type.Str);
    }

    @Test
    public void goodObjMultiKey() {
        String text = """
                {"abc": "def", "ghi": "jkl"}""";
        assertTypesEqual(text,
                Syntax.Node.Type.Obj,
                Syntax.Node.Type.Kvps,
                Syntax.Node.Type.Kvp,
                Syntax.Node.Type.Str,
                Syntax.Node.Type.Str,
                Syntax.Node.Type.Kvp,
                Syntax.Node.Type.Str,
                Syntax.Node.Type.Str);
    }

    @Test
    public void goodNestedObjs() {
        String text = """
                {"abc": {"abc": {"abc": "abc"}, "def": "def"}}""";
        assertTypesEqual(text,
                Syntax.Node.Type.Obj,
                Syntax.Node.Type.Kvps,
                Syntax.Node.Type.Kvp,
                Syntax.Node.Type.Str,
                Syntax.Node.Type.Obj,
                Syntax.Node.Type.Kvps,
                Syntax.Node.Type.Kvp,
                Syntax.Node.Type.Str,
                Syntax.Node.Type.Obj,
                Syntax.Node.Type.Kvps,
                Syntax.Node.Type.Kvp,
                Syntax.Node.Type.Str,
                Syntax.Node.Type.Str,
                Syntax.Node.Type.Kvp,
                Syntax.Node.Type.Str,
                Syntax.Node.Type.Str);
    }
    @Test
    public void goodObjSingleKeyWithTrailingComma() {
        String text = """
                {"a":{"abc": "def"} , }""";
        assertTypesEqual(text,
                Syntax.Node.Type.Obj,
                Syntax.Node.Type.Kvps,
                Syntax.Node.Type.Kvp,
                Syntax.Node.Type.Str,
                Syntax.Node.Type.Obj,
                Syntax.Node.Type.Kvps,
                Syntax.Node.Type.Kvp,
                Syntax.Node.Type.Str,
                Syntax.Node.Type.Str);
    }

    @Test
    public void goodEmptyArr() {
        String text = "[]";
        assertTypesEqual(text,
                Syntax.Node.Type.Arr);
    }

    @Test
    public void goodEmptyArrWithWs() {
        String text = "[  ]";
        assertTypesEqual(text,
                Syntax.Node.Type.Arr);
    }

    @Test
    public void goodSingleElemArr() {
        String text = "[1]";
        assertTypesEqual(text,
                Syntax.Node.Type.Arr,
                Syntax.Node.Type.Num);
    }

    @Test
    public void goodMultiElemArr() {
        String text = """
                [1, 2, "3"]""";
        assertTypesEqual(text,
                Syntax.Node.Type.Arr,
                Syntax.Node.Type.Num,
                Syntax.Node.Type.Num,
                Syntax.Node.Type.Str);
    }

    @Test
    public void goodNestedArr() {
        String text = """
                [[1, [1, 2], []] 3]""";
        assertTypesEqual(text,
                Syntax.Node.Type.Arr,
                Syntax.Node.Type.Arr,
                Syntax.Node.Type.Num,
                Syntax.Node.Type.Arr,
                Syntax.Node.Type.Num,
                Syntax.Node.Type.Num,
                Syntax.Node.Type.Arr,
                Syntax.Node.Type.Num);
    }

    @ParameterizedTest
    @MethodSource("goodStringsProvider")
    public void goodStrings(String text, String expectedValue) {
        Document document = Document.of(text);
        Syntax.Node value = Syntax.parseNode(document).value();
        if (value instanceof Syntax.Node.Str s) {
            String actualValue = s.stringValue();
            if (!expectedValue.equals(actualValue)) {
                fail(String.format("expected text of %s to be parsed as a string with value %s, but was %s",
                        text, expectedValue, actualValue));
            }
        } else {
            fail(String.format("expected text of %s to be parsed as a string, but was %s",
                    text, value.type()));
        }
    }

    private static Stream<Arguments> goodStringsProvider() {
        return Stream.of(
                Arguments.of("\"foo\"", "foo"),
                Arguments.of("\"\"", "")
        );
    }

    @ParameterizedTest
    @MethodSource("goodIdentsProvider")
    public void goodIdents(String text, String expectedValue) {
        Document document = Document.of(text);
        Syntax.Node value = Syntax.parseNode(document).value();
        if (value instanceof Syntax.Ident ident) {
            String actualValue = ident.stringValue();
            if (!expectedValue.equals(actualValue)) {
                fail(String.format("expected text of %s to be parsed as an ident with value %s, but was %s",
                        text, expectedValue, actualValue));
            }
        } else {
            fail(String.format("expected text of %s to be parsed as an ident, but was %s",
                    text, value.type()));
        }
    }

    private static Stream<Arguments> goodIdentsProvider() {
        return Stream.of(
                Arguments.of("true", "true"),
                Arguments.of("false", "false"),
                Arguments.of("null", "null")
        );
    }

    @ParameterizedTest
    @MethodSource("goodNumbersProvider")
    public void goodNumbers(String text, BigDecimal expectedValue) {
        Document document = Document.of(text);
        Syntax.Node value = Syntax.parseNode(document).value();

        if (value instanceof Syntax.Node.Num num) {
            if (!expectedValue.equals(num.value)) {
                fail(String.format("Expected text of %s to be parsed as a number with value %s, but was %s",
                        text, expectedValue, num.value));
            }
        } else {
            fail(String.format("Expected text of %s to be parsed as a number but was %s",
                    text, value.type()));
        }
    }

    private static Stream<Arguments> goodNumbersProvider() {
        return Stream.of(
                Arguments.of("-10", BigDecimal.valueOf(-10)),
                Arguments.of("0", BigDecimal.valueOf(0)),
                Arguments.of("123", BigDecimal.valueOf(123))
        );
    }

    @ParameterizedTest
    @MethodSource("brokenProvider")
    public void broken(String desc, String text, List<String> expectedErrorMessages, List<Syntax.Node.Type> expectedTypes) {
        Syntax.NodeParseResult parse = Syntax.parseNode(Document.of(text));
        List<String> errorMessages = parse.errors().stream().map(Syntax.Err::message).toList();
        List<Syntax.Node.Type> types = getNodeTypes(parse.value());

        assertThat(desc, errorMessages, equalTo(expectedErrorMessages));
        assertThat(desc, types, equalTo(expectedTypes));
    }

    record InvalidSyntaxTestCase(
            String description,
            String text,
            List<String> expectedErrorMessages,
            List<Syntax.Node.Type> expectedTypes
    ) {}

    private static final List<InvalidSyntaxTestCase> INVALID_SYNTAX_TEST_CASES = List.of(
            new InvalidSyntaxTestCase(
                    "invalid element token",
                    "[1, 2}]",
                    List.of("unexpected token }"),
                    List.of(Syntax.Node.Type.Arr, Syntax.Node.Type.Num, Syntax.Node.Type.Num)
            ),
            new InvalidSyntaxTestCase(
                    "unclosed empty",
                    "[",
                    List.of("missing ]"),
                    List.of(Syntax.Node.Type.Arr)
            ),
            new InvalidSyntaxTestCase(
                    "unclosed",
                    "[1,",
                    List.of("missing ]"),
                    List.of(Syntax.Node.Type.Arr, Syntax.Node.Type.Num)
            ),
            new InvalidSyntaxTestCase(
                    "unclosed with sp",
                    "[1,   ",
                    List.of("missing ]"),
                    List.of(Syntax.Node.Type.Arr, Syntax.Node.Type.Num)
            ),
            new InvalidSyntaxTestCase(
                    "unclosed with multi elem",
                    "[1,a",
                    List.of("missing ]"),
                    List.of(Syntax.Node.Type.Arr, Syntax.Node.Type.Num, Syntax.Node.Type.Ident)
            ),
            new InvalidSyntaxTestCase(
                    "unclosed with multi elem and sp",
                    "[1,a ",
                    List.of("missing ]"),
                    List.of(Syntax.Node.Type.Arr, Syntax.Node.Type.Num, Syntax.Node.Type.Ident)
            ),
            new InvalidSyntaxTestCase(
                    "unclosed with multi elem no ,",
                    "[a 2",
                    List.of("missing ]"),
                    List.of(Syntax.Node.Type.Arr, Syntax.Node.Type.Ident, Syntax.Node.Type.Num)
            ),
            new InvalidSyntaxTestCase(
                    "unclosed in member",
                    "{foo: [1, 2}",
                    List.of("unexpected token }", "missing ]", "missing }"),
                    List.of(
                            Syntax.Node.Type.Obj,
                            Syntax.Node.Type.Kvps,
                            Syntax.Node.Type.Kvp,
                            Syntax.Node.Type.Ident,
                            Syntax.Node.Type.Arr,
                            Syntax.Node.Type.Num,
                            Syntax.Node.Type.Num)
            ),
            new InvalidSyntaxTestCase(
                    "Non-string key with no value",
                    "{1}",
                    List.of("unexpected Num", "expected :", "expected value"),
                    List.of(Syntax.Node.Type.Obj, Syntax.Node.Type.Kvps)
            ),
            new InvalidSyntaxTestCase(
                    "Non-string key with : but no value",
                    "{1:}",
                    List.of("unexpected Num", "expected value"),
                    List.of(Syntax.Node.Type.Obj, Syntax.Node.Type.Kvps)
            ),
            new InvalidSyntaxTestCase(
                    "String key with no value",
                    "{\"1\"}",
                    List.of("expected :", "expected value"),
                    List.of(Syntax.Node.Type.Obj, Syntax.Node.Type.Kvps, Syntax.Node.Type.Kvp, Syntax.Node.Type.Str)
            ),
            new InvalidSyntaxTestCase(
                    "String key with : but no value",
                    "{\"1\":}",
                    List.of("expected value"),
                    List.of(Syntax.Node.Type.Obj, Syntax.Node.Type.Kvps, Syntax.Node.Type.Kvp, Syntax.Node.Type.Str)
            ),
            new InvalidSyntaxTestCase(
                    "String key with no value but a trailing ,",
                    "{\"1\",}",
                    List.of("expected :", "expected value"),
                    List.of(Syntax.Node.Type.Obj, Syntax.Node.Type.Kvps, Syntax.Node.Type.Kvp, Syntax.Node.Type.Str)
            ),
            new InvalidSyntaxTestCase(
                    "String key with : but no value and a trailing ,",
                    "{\"1\":,}",
                    List.of("expected value"),
                    List.of(Syntax.Node.Type.Obj, Syntax.Node.Type.Kvps, Syntax.Node.Type.Kvp, Syntax.Node.Type.Str)
            ),
            new InvalidSyntaxTestCase(
                    "String key with : but no }",
                    "{\"1\": abc, \"2\": def",
                    List.of("missing }"),
                    List.of(Syntax.Node.Type.Obj, Syntax.Node.Type.Kvps, Syntax.Node.Type.Kvp,
                            Syntax.Node.Type.Str, Syntax.Node.Type.Ident, Syntax.Node.Type.Kvp,
                            Syntax.Node.Type.Str, Syntax.Node.Type.Ident)
            ),
            new InvalidSyntaxTestCase(
                    "Invalid key",
                    "{\"abc}",
                    List.of("unexpected eof", "missing }"),
                    List.of(Syntax.Node.Type.Obj, Syntax.Node.Type.Kvps)
            ),
            new InvalidSyntaxTestCase(
                    "Missing :",
                    "{\"abc\" 1}",
                    List.of("expected :"),
                    List.of(Syntax.Node.Type.Obj, Syntax.Node.Type.Kvps, Syntax.Node.Type.Kvp, Syntax.Node.Type.Str)
            ),
            new InvalidSyntaxTestCase(
                    "Missing key in obj",
                            "{,}",
                    List.of(),
                    List.of(Syntax.Node.Type.Obj, Syntax.Node.Type.Kvps)
            ),
            new InvalidSyntaxTestCase(
                    "Missing colon and unexpected value in obj",
                    "{foo ?}",
                    List.of("expected :","unexpected token ?"),
                    List.of(Syntax.Node.Type.Obj, Syntax.Node.Type.Kvps, Syntax.Node.Type.Kvp, Syntax.Node.Type.Ident)
            ),
            new InvalidSyntaxTestCase(
                    "Unclosed text block",
                    """
                            \"\"\"abc
                            """,
                    List.of(),
                    List.of(Syntax.Node.Type.Err)
            ),
            new InvalidSyntaxTestCase(
                    "Invalid number",
                    """
                           123?
                            """,
                    List.of(),
                    List.of(Syntax.Node.Type.Err)
            )
    );

    private static Stream<Arguments> brokenProvider() {
        return INVALID_SYNTAX_TEST_CASES.stream().map(invalidSyntaxTestCase -> Arguments.of(
                invalidSyntaxTestCase.description,
                invalidSyntaxTestCase.text,
                invalidSyntaxTestCase.expectedErrorMessages,
                invalidSyntaxTestCase.expectedTypes));
    }

    @Test
    public void parsesStringsWithEscapes() {
        String text = """
                "a\\"b"
                """;
        assertTypesEqual(text,
                Syntax.Node.Type.Str);
    }

    @Test
    public void parsesTextBlocks() {
        String text = "[\"\"\"foo\"\"\", 2, \"bar\", 3, \"\", 4, \"\"\"\"\"\"]";
        assertTypesEqual(text,
                Syntax.Node.Type.Arr,
                Syntax.Node.Type.Str,
                Syntax.Node.Type.Num,
                Syntax.Node.Type.Str,
                Syntax.Node.Type.Num,
                Syntax.Node.Type.Str,
                Syntax.Node.Type.Num,
                Syntax.Node.Type.Str);
    }

    @Test
    public void stringValues() {
        Syntax.Node node = Syntax.parseNode(Document.of("""
                [
                    "abc",
                    "",
                    \"""
                    foo
                    \"""
                ]
                """)).value();

        assertThat(node, instanceOf(Syntax.Node.Arr.class));
        Syntax.Node.Arr arr = (Syntax.Node.Arr) node;
        assertThat(arr.elements(), hasSize(3));

        Syntax.Node first = arr.elements().get(0);
        assertThat(first, instanceOf(Syntax.Node.Str.class));
        assertThat(((Syntax.Node.Str) first).stringValue(), equalTo("abc"));

        Syntax.Node second = arr.elements().get(1);
        assertThat(second, instanceOf(Syntax.Node.Str.class));
        assertThat(((Syntax.Node.Str) second).stringValue(), equalTo(""));

        Syntax.Node third = arr.elements().get(2);
        assertThat(third, instanceOf(Syntax.Node.Str.class));
        assertThat(((Syntax.Node.Str) third).stringValue().trim(), equalTo("foo"));
    }

    @Test
    public void badKvpWithTrailingIncompleteKvp() {
        String text = "{\"foo\":bar, \"buz\"}";
        Document document = Document.of(text);
        Syntax.Node.Obj node = (Syntax.Node.Obj)Syntax.parseNode(document).value();
        Syntax.Node.Kvps kvps = node.kvps();
        assertThat(document.copySpan(kvps.start, kvps.end), equalTo("{\"foo\":bar, \"buz\"}"));
        Syntax.Node.Kvp first = kvps.kvps().get(0);
        assertThat(document.copySpan(first.start, first.end), equalTo("\"foo\":bar"));
        Syntax.Node.Kvp second = kvps.kvps().get(1);
        assertThat(document.copySpan(second.start, second.end), equalTo("\"buz\""));
    }

    @Test
    public void badKvpWithLeadingComma() {
        String text = "{,\"foo\":bar}";
        Document document = Document.of(text);
        Syntax.Node.Obj node = (Syntax.Node.Obj)Syntax.parseNode(document).value();
        Syntax.Node.Kvps kvps = node.kvps();
        assertThat(document.copySpan(kvps.start, kvps.end), equalTo("{,\"foo\":bar}"));
        Syntax.Node.Kvp first = kvps.kvps().get(0);
        assertThat(document.copySpan(first.start, first.end), equalTo("\"foo\":bar"));
    }

    @Test
    public void badArrWithLeadingComma() {
        String text = "[,a,";
        Document document = Document.of(text);
        Syntax.Node.Arr arr = (Syntax.Node.Arr)Syntax.parseNode(document).value();
        assertThat(arr.elements(), hasSize(1));
        assertThat(document.copySpan(arr.elements.get(0).start, arr.elements.get(0).end), equalTo("a"));
    }

    @Test
    public void badArrWithInvalidNum() {
        String text = "[456?,123]";
        Document document = Document.of(text);
        Syntax.Node.Arr arr = (Syntax.Node.Arr)Syntax.parseNode(document).value();
        assertThat(arr.elements(), hasSize(1));
        assertThat(document.copySpan(arr.elements.get(0).start, arr.elements.get(0).end), equalTo("123"));
    }

    private static void assertTypesEqual(String text, Syntax.Node.Type... types) {
        assertThat(getNodeTypes(Syntax.parseNode(Document.of(text)).value()), contains(types));
    }

    static List<Syntax.Node.Type> getNodeTypes(Syntax.Node value) {
        List<Syntax.Node.Type> types = new ArrayList<>();
        value.consume(v -> types.add(v.type()));
        return types;
    }
}
