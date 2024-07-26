/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.document;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static software.amazon.smithy.lsp.document.DocumentTest.safeIndex;
import static software.amazon.smithy.lsp.document.DocumentTest.safeString;
import static software.amazon.smithy.lsp.document.DocumentTest.string;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.shapes.Shape;

public class DocumentParserTest {
    @Test
    public void jumpsToLines() {
        String text = "abc\n" +
                      "def\n" +
                      "ghi\n" +
                      "\n" +
                      "\n";
        DocumentParser parser = DocumentParser.of(safeString(text));
        assertEquals(0, parser.position());
        assertEquals(1, parser.line());
        assertEquals(1, parser.column());

        parser.jumpToLine(0);
        assertEquals(0, parser.position());
        assertEquals(1, parser.line());
        assertEquals(1, parser.column());

        parser.jumpToLine(1);
        assertEquals(safeIndex(4, 1), parser.position());
        assertEquals(2, parser.line());
        assertEquals(1, parser.column());

        parser.jumpToLine(2);
        assertEquals(safeIndex(8, 2), parser.position());
        assertEquals(3, parser.line());
        assertEquals(1, parser.column());

        parser.jumpToLine(3);
        assertEquals(safeIndex(12, 3), parser.position());
        assertEquals(4, parser.line());
        assertEquals(1, parser.column());

        parser.jumpToLine(4);
        assertEquals(safeIndex(13, 4), parser.position());
        assertEquals(5, parser.line());
        assertEquals(1, parser.column());
    }

    @Test
    public void jumpsToSource() {
        String text = "abc\ndef\nghi\n";
        DocumentParser parser = DocumentParser.of(safeString(text));
        assertThat(parser.position(), is(0));
        assertThat(parser.line(), is(1));
        assertThat(parser.column(), is(1));
        assertThat(parser.currentPosition(), equalTo(new Position(0, 0)));

        boolean ok = parser.jumpToSource(new SourceLocation("", 1, 2));
        assertThat(ok, is(true));
        assertThat(parser.position(), is(1));
        assertThat(parser.line(), is(1));
        assertThat(parser.column(), is(2));
        assertThat(parser.currentPosition(), equalTo(new Position(0, 1)));

        ok = parser.jumpToSource(new SourceLocation("", 1, 4));
        assertThat(ok, is(true));
        assertThat(parser.position(), is(3));
        assertThat(parser.line(), is(1));
        assertThat(parser.column(), is(4));
        assertThat(parser.currentPosition(), equalTo(new Position(0, 3)));

        ok = parser.jumpToSource(new SourceLocation("", 1, 6));
        assertThat(ok, is(false));
        assertThat(parser.position(), is(3));
        assertThat(parser.line(), is(1));
        assertThat(parser.column(), is(4));
        assertThat(parser.currentPosition(), equalTo(new Position(0, 3)));

        ok = parser.jumpToSource(new SourceLocation("", 2, 1));
        assertThat(ok, is(true));
        assertThat(parser.position(), is(safeIndex(4, 1)));
        assertThat(parser.line(), is(2));
        assertThat(parser.column(), is(1));
        assertThat(parser.currentPosition(), equalTo(new Position(1, 0)));

        ok = parser.jumpToSource(new SourceLocation("", 4, 1));
        assertThat(ok, is(false));

        ok = parser.jumpToSource(new SourceLocation("", 3, 4));
        assertThat(ok, is(true));
        assertThat(parser.position(), is(safeIndex(11, 2)));
        assertThat(parser.line(), is(3));
        assertThat(parser.column(), is(4));
        assertThat(parser.currentPosition(), equalTo(new Position(2, 3)));
    }

    @Test
    public void getsDocumentNamespace() {
        DocumentParser noNamespace = DocumentParser.of(safeString("abc\ndef\n"));
        DocumentParser incompleteNamespace = DocumentParser.of(safeString("abc\nnamespac"));
        DocumentParser incompleteNamespaceValue = DocumentParser.of(safeString("namespace "));
        DocumentParser likeNamespace = DocumentParser.of(safeString("anamespace com.foo\n"));
        DocumentParser otherLikeNamespace = DocumentParser.of(safeString("namespacea com.foo"));
        DocumentParser namespaceAtEnd = DocumentParser.of(safeString("\n\nnamespace com.foo"));
        DocumentParser brokenNamespace = DocumentParser.of(safeString("\nname space com.foo\n"));
        DocumentParser commentedNamespace = DocumentParser.of(safeString("abc\n//namespace com.foo\n"));
        DocumentParser wsPrefixedNamespace = DocumentParser.of(safeString("abc\n    namespace com.foo\n"));
        DocumentParser notNamespace = DocumentParser.of(safeString("namespace !foo"));
        DocumentParser trailingComment = DocumentParser.of(safeString("namespace com.foo//foo\n"));

        assertThat(noNamespace.documentNamespace(), nullValue());
        assertThat(incompleteNamespace.documentNamespace(), nullValue());
        assertThat(incompleteNamespaceValue.documentNamespace(), nullValue());
        assertThat(likeNamespace.documentNamespace(), nullValue());
        assertThat(otherLikeNamespace.documentNamespace(), nullValue());
        assertThat(namespaceAtEnd.documentNamespace().namespace().toString(), equalTo("com.foo"));
        assertThat(namespaceAtEnd.documentNamespace().statementRange(), equalTo(LspAdapter.of(2, 0, 2, 17)));
        assertThat(brokenNamespace.documentNamespace(), nullValue());
        assertThat(commentedNamespace.documentNamespace(), nullValue());
        assertThat(wsPrefixedNamespace.documentNamespace().namespace().toString(), equalTo("com.foo"));
        assertThat(wsPrefixedNamespace.documentNamespace().statementRange(), equalTo(LspAdapter.of(1, 4, 1, 21)));
        assertThat(notNamespace.documentNamespace(), nullValue());
        assertThat(trailingComment.documentNamespace().namespace().toString(), equalTo("com.foo"));
        assertThat(trailingComment.documentNamespace().statementRange(), equalTo(LspAdapter.of(0, 0, 0, 22)));
    }

    @Test
    public void getsDocumentImports() {
        DocumentParser noImports = DocumentParser.of(safeString("abc\ndef\n"));
        DocumentParser incompleteImport = DocumentParser.of(safeString("abc\nus"));
        DocumentParser incompleteImportValue = DocumentParser.of(safeString("use "));
        DocumentParser oneImport = DocumentParser.of(safeString("use com.foo#bar"));
        DocumentParser leadingWsImport = DocumentParser.of(safeString("    use com.foo#bar"));
        DocumentParser trailingCommentImport = DocumentParser.of(safeString("use com.foo#bar//foo"));
        DocumentParser commentedImport = DocumentParser.of(safeString("//use com.foo#bar"));
        DocumentParser multiImports = DocumentParser.of(safeString("use com.foo#bar\nuse com.foo#baz"));
        DocumentParser notImport = DocumentParser.of(safeString("usea com.foo#bar"));

        assertThat(noImports.documentImports(), nullValue());
        assertThat(incompleteImport.documentImports(), nullValue());
        assertThat(incompleteImportValue.documentImports(), nullValue());
        assertThat(oneImport.documentImports().imports(), containsInAnyOrder("com.foo#bar"));
        assertThat(leadingWsImport.documentImports().imports(), containsInAnyOrder("com.foo#bar"));
        assertThat(trailingCommentImport.documentImports().imports(), containsInAnyOrder("com.foo#bar"));
        assertThat(commentedImport.documentImports(), nullValue());
        assertThat(multiImports.documentImports().imports(), containsInAnyOrder("com.foo#bar", "com.foo#baz"));
        assertThat(notImport.documentImports(), nullValue());

        // Some of these aren't shape ids, but its ok
        DocumentParser brokenImport = DocumentParser.of(safeString("use com.foo"));
        DocumentParser commentSeparatedImports = DocumentParser.of(safeString("use com.foo#bar //foo\nuse com.foo#baz\n//abc\nuse com.foo#foo"));
        DocumentParser oneBrokenImport = DocumentParser.of(safeString("use com.foo\nuse com.foo#bar"));
        DocumentParser innerBrokenImport = DocumentParser.of(safeString("use com.foo#bar\nuse com.foo\nuse com.foo#baz"));
        DocumentParser innerNotImport = DocumentParser.of(safeString("use com.foo#bar\nstring Foo\nuse com.foo#baz"));
        assertThat(brokenImport.documentImports().imports(), containsInAnyOrder("com.foo"));
        assertThat(commentSeparatedImports.documentImports().imports(), containsInAnyOrder("com.foo#bar", "com.foo#baz", "com.foo#foo"));
        assertThat(oneBrokenImport.documentImports().imports(), containsInAnyOrder("com.foo#bar", "com.foo"));
        assertThat(innerBrokenImport.documentImports().imports(), containsInAnyOrder("com.foo#bar", "com.foo", "com.foo#baz"));
        assertThat(innerNotImport.documentImports().imports(), containsInAnyOrder("com.foo#bar"));
    }

    @Test
    public void getsDocumentVersion() {
        DocumentParser noVersion = DocumentParser.of(safeString("abc\ndef"));
        DocumentParser notVersion = DocumentParser.of(safeString("$versionNot: \"2\""));
        DocumentParser noDollar = DocumentParser.of(safeString("version: \"2\""));
        DocumentParser noColon = DocumentParser.of(safeString("$version \"2\""));
        DocumentParser commented = DocumentParser.of(safeString("//$version: \"2\""));
        DocumentParser leadingWs = DocumentParser.of(safeString("    $version: \"2\""));
        DocumentParser leadingLines = DocumentParser.of(safeString("\n\n//abc\n$version: \"2\""));
        DocumentParser notStringNode = DocumentParser.of(safeString("$version: 2"));
        DocumentParser trailingComment = DocumentParser.of(safeString("$version: \"2\"//abc"));
        DocumentParser trailingLine = DocumentParser.of(safeString("$version: \"2\"\n"));
        DocumentParser invalidNode = DocumentParser.of(safeString("$version: \"2"));
        DocumentParser notFirst = DocumentParser.of(safeString("$foo: \"bar\"\n// abc\n$version: \"2\""));
        DocumentParser notSecond = DocumentParser.of(safeString("$foo: \"bar\"\n$bar: 1\n// abc\n$baz: 2\n    $version: \"2\""));
        DocumentParser notFirstNoVersion = DocumentParser.of(safeString("$foo: \"bar\"\nfoo\n"));

        assertThat(noVersion.documentVersion(), nullValue());
        assertThat(notVersion.documentVersion(), nullValue());
        assertThat(noDollar.documentVersion(), nullValue());
        assertThat(noColon.documentVersion(), nullValue());
        assertThat(commented.documentVersion(), nullValue());
        assertThat(leadingWs.documentVersion().version(), equalTo("2"));
        assertThat(leadingLines.documentVersion().version(), equalTo("2"));
        assertThat(notStringNode.documentVersion(), nullValue());
        assertThat(trailingComment.documentVersion().version(), equalTo("2"));
        assertThat(trailingLine.documentVersion().version(), equalTo("2"));
        assertThat(invalidNode.documentVersion(), nullValue());
        assertThat(notFirst.documentVersion().version(), equalTo("2"));
        assertThat(notSecond.documentVersion().version(), equalTo("2"));
        assertThat(notFirstNoVersion.documentVersion(), nullValue());

        Range leadingWsRange = leadingWs.documentVersion().range();
        Range trailingCommentRange = trailingComment.documentVersion().range();
        Range trailingLineRange = trailingLine.documentVersion().range();
        Range notFirstRange = notFirst.documentVersion().range();
        Range notSecondRange = notSecond.documentVersion().range();
        assertThat(leadingWs.getDocument().copyRange(leadingWsRange), equalTo("$version: \"2\""));
        assertThat(trailingComment.getDocument().copyRange(trailingCommentRange), equalTo("$version: \"2\""));
        assertThat(trailingLine.getDocument().copyRange(trailingLineRange), equalTo("$version: \"2\""));
        assertThat(notFirst.getDocument().copyRange(notFirstRange), equalTo("$version: \"2\""));
        assertThat(notSecond.getDocument().copyRange(notSecondRange), equalTo("$version: \"2\""));
    }

    @Test
    public void getsDocumentShapes() {
        String text = "$version: \"2\"\n"
                + "namespace com.foo\n"
                + "string Foo\n"
                + "structure Bar {\n"
                + "    bar: Foo\n"
                + "}\n"
                + "enum Baz {\n"
                + "    ONE\n"
                + "    TWO\n"
                + "}\n"
                + "intEnum Biz {\n"
                + "    ONE = 1\n"
                + "}\n"
                + "@mixin\n"
                + "structure Boz {\n"
                + "    elided: String\n"
                + "}\n"
                + "structure Mixed with [Boz] {\n"
                + "    $elided\n"
                + "}\n"
                + "operation Get {\n"
                + "    input := {\n"
                + "        a: Integer\n"
                + "    }\n"
                + "}\n";
        Set<Shape> shapes = Model.assembler()
                .addUnparsedModel("main.smithy", text)
                .assemble()
                .unwrap()
                .shapes()
                .filter(shape -> shape.getId().getNamespace().equals("com.foo"))
                .collect(Collectors.toSet());

        DocumentParser parser = DocumentParser.of(safeString(text));
        Map<Position, DocumentShape> documentShapes = parser.documentShapes(shapes);

        DocumentShape fooDef = documentShapes.get(new Position(2, 7));
        DocumentShape barDef = documentShapes.get(new Position(3, 10));
        DocumentShape barMemberDef = documentShapes.get(new Position(4, 4));
        DocumentShape targetFoo = documentShapes.get(new Position(4, 9));
        DocumentShape bazDef = documentShapes.get(new Position(6, 5));
        DocumentShape bazOneDef = documentShapes.get(new Position(7, 4));
        DocumentShape bazTwoDef = documentShapes.get(new Position(8, 4));
        DocumentShape bizDef = documentShapes.get(new Position(10, 8));
        DocumentShape bizOneDef = documentShapes.get(new Position(11, 4));
        DocumentShape bozDef = documentShapes.get(new Position(14, 10));
        DocumentShape elidedDef = documentShapes.get(new Position(15, 4));
        DocumentShape targetString = documentShapes.get(new Position(15, 12));
        DocumentShape mixedDef = documentShapes.get(new Position(17, 10));
        DocumentShape elided = documentShapes.get(new Position(18, 4));
        DocumentShape get = documentShapes.get(new Position(20, 10));
        DocumentShape getInput = documentShapes.get(new Position(21, 13));
        DocumentShape getInputA = documentShapes.get(new Position(22, 8));

        assertThat(fooDef.kind(), equalTo(DocumentShape.Kind.DefinedShape));
        assertThat(fooDef.shapeName(), string("Foo"));
        assertThat(barDef.kind(), equalTo(DocumentShape.Kind.DefinedShape));
        assertThat(barDef.shapeName(), string("Bar"));
        assertThat(barMemberDef.kind(), equalTo(DocumentShape.Kind.DefinedMember));
        assertThat(barMemberDef.shapeName(), string("bar"));
        assertThat(barMemberDef.targetReference(), equalTo(targetFoo));
        assertThat(targetFoo.kind(), equalTo(DocumentShape.Kind.Targeted));
        assertThat(targetFoo.shapeName(), string("Foo"));
        assertThat(bazDef.kind(), equalTo(DocumentShape.Kind.DefinedShape));
        assertThat(bazDef.shapeName(), string("Baz"));
        assertThat(bazOneDef.kind(), equalTo(DocumentShape.Kind.DefinedMember));
        assertThat(bazOneDef.shapeName(), string("ONE"));
        assertThat(bazTwoDef.kind(), equalTo(DocumentShape.Kind.DefinedMember));
        assertThat(bazTwoDef.shapeName(), string("TWO"));
        assertThat(bizDef.kind(), equalTo(DocumentShape.Kind.DefinedShape));
        assertThat(bizDef.shapeName(), string("Biz"));
        assertThat(bizOneDef.kind(), equalTo(DocumentShape.Kind.DefinedMember));
        assertThat(bizOneDef.shapeName(), string("ONE"));
        assertThat(bozDef.kind(), equalTo(DocumentShape.Kind.DefinedShape));
        assertThat(bozDef.shapeName(), string("Boz"));
        assertThat(elidedDef.kind(), equalTo(DocumentShape.Kind.DefinedMember));
        assertThat(elidedDef.shapeName(), string("elided"));
        assertThat(elidedDef.targetReference(), equalTo(targetString));
        assertThat(targetString.kind(), equalTo(DocumentShape.Kind.Targeted));
        assertThat(targetString.shapeName(), string("String"));
        assertThat(mixedDef.kind(), equalTo(DocumentShape.Kind.DefinedShape));
        assertThat(mixedDef.shapeName(), string("Mixed"));
        assertThat(elided.kind(), equalTo(DocumentShape.Kind.Elided));
        assertThat(elided.shapeName(), string("elided"));
        assertThat(parser.getDocument().borrowRange(elided.range()), string("$elided"));
        assertThat(get.kind(), equalTo(DocumentShape.Kind.DefinedShape));
        assertThat(get.shapeName(), string("Get"));
        assertThat(getInput.kind(), equalTo(DocumentShape.Kind.Inline));
        assertThat(getInputA.kind(), equalTo(DocumentShape.Kind.DefinedMember));
        assertThat(getInputA.shapeName(), string("a"));
    }
}
