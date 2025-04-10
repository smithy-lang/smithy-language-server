/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.document;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.TextWithPositions;
import software.amazon.smithy.lsp.protocol.RangeBuilder;

public class DocumentTest {
    @Test
    public void appliesTrailingReplacementEdit() {
        String s = "abc\n" +
                   "def";
        Document document = makeDocument(s);

        Range editRange = new RangeBuilder()
                .startLine(1)
                .startCharacter(2)
                .endLine(1)
                .endCharacter(3)
                .build();
        String editText = "g";

        document.applyEdit(editRange, editText);

        assertThat(document.copyText(), equalTo(safeString("abc\n" +
                                                "deg")));
        assertThat(document.indexOfLine(0), equalTo(0));
        assertThat(document.indexOfLine(1), equalTo(safeIndex(4, 1)));
    }

    @Test
    public void appliesAppendingEdit() {
        String s = "abc\n" +
                   "def";
        Document document = makeDocument(s);

        Range editRange = new RangeBuilder()
                .startLine(1)
                .startCharacter(3)
                .endLine(1)
                .endCharacter(3)
                .build();
        String editText = "g";

        document.applyEdit(editRange, editText);

        assertThat(document.copyText(), equalTo(safeString("abc\n" +
                                                "defg")));
        assertThat(document.indexOfLine(0), equalTo(safeIndex(0, 0)));
        assertThat(document.indexOfLine(1), equalTo(safeIndex(4, 1)));
    }

    @Test
    public void appliesLeadingReplacementEdit() {
        String s = "abc\n" +
                   "def";
        Document document = makeDocument(s);

        Range editRange = new RangeBuilder()
                .startLine(0)
                .startCharacter(0)
                .endLine(0)
                .endCharacter(1)
                .build();
        String editText = "z";

        document.applyEdit(editRange, editText);

        assertThat(document.copyText(), equalTo(safeString("zbc\n" +
                                                "def")));
        assertThat(document.indexOfLine(0), equalTo(0));
        assertThat(document.indexOfLine(1), equalTo(safeIndex(4, 1)));
    }

    @Test
    public void appliesPrependingEdit() {
        String s = "abc\n" +
                   "def";
        Document document = makeDocument(s);

        Range editRange = new RangeBuilder()
                .startLine(0)
                .startCharacter(0)
                .endLine(0)
                .endCharacter(0)
                .build();
        String editText = "z";

        document.applyEdit(editRange, editText);

        assertThat(document.copyText(), equalTo(safeString("zabc\n" +
                                                "def")));
        assertThat(document.indexOfLine(0), equalTo(0));
        assertThat(document.indexOfLine(1), equalTo(safeIndex(5, 1)));
    }

    @Test
    public void appliesInnerReplacementEdit() {
        String s = "abc\n" +
                   "def";
        Document document = makeDocument(s);

        Range editRange = new RangeBuilder()
                .startLine(0)
                .startCharacter(1)
                .endLine(1)
                .endCharacter(1)
                .build();
        String editText = safeString("zy\n" +
                       "x");

        document.applyEdit(editRange, editText);

        assertThat(document.copyText(), equalTo(safeString("azy\n" +
                                                "xef")));
        assertThat(document.indexOfLine(0), equalTo(0));
        assertThat(document.indexOfLine(1), equalTo(safeIndex(4, 1)));
    }

    @Test
    public void appliesPrependingAndReplacingEdit() {
        String s = "abc";
        Document document = makeDocument(s);

        Range editRange = new RangeBuilder()
                .startLine(0)
                .startCharacter(0)
                .endLine(0)
                .endCharacter(1)
                .build();
        String editText = "zy";

        document.applyEdit(editRange, editText);

        assertThat(document.copyText(), equalTo("zybc"));
        assertThat(document.indexOfLine(0), equalTo(0));
    }

    @Test
    public void appliesInsertionEdit() {
        String s = "abc\n" +
                   "def";
        Document document = makeDocument(s);

        Range editRange = new RangeBuilder()
                .startLine(0)
                .startCharacter(2)
                .endLine(0)
                .endCharacter(2)
                .build();
        String editText = safeString("zx\n" +
                       "y");

        document.applyEdit(editRange, editText);

        assertThat(document.copyText(), equalTo(safeString("""
                abzx
                yc
                def""")));
        assertThat(document.indexOfLine(0), equalTo(0));
        assertThat(document.indexOfLine(1), equalTo(safeIndex(5, 1)));
        assertThat(document.indexOfLine(2), equalTo(safeIndex(8, 2)));
    }

    @Test
    public void appliesDeletionEdit() {
        String s = "abc\n" +
                   "def";
        Document document = makeDocument(s);

        Range editRange = new RangeBuilder()
                .startLine(0)
                .startCharacter(0)
                .endLine(0)
                .endCharacter(1)
                .build();
        String editText = "";

        document.applyEdit(editRange, editText);

        assertThat(document.copyText(), equalTo(safeString("bc\n" +
                                                "def")));
        assertThat(document.indexOfLine(0), equalTo(0));
        assertThat(document.indexOfLine(1), equalTo(safeIndex(3, 1)));
    }

    @Test
    public void getsIndexOfLine() {
        String s = """
                abc
                def
                hij
                """;
        Document document = makeDocument(s);

        assertThat(document.indexOfLine(0), equalTo(0));
        assertThat(document.indexOfLine(-1), equalTo(-1));
        assertThat(document.indexOfLine(1), equalTo(safeIndex(4, 1)));
        assertThat(document.indexOfLine(2), equalTo(safeIndex(8, 2)));
        assertThat(document.indexOfLine(3), equalTo(safeIndex(12, 3)));
        assertThat(document.indexOfLine(4), equalTo(-1));
    }

    @Test
    public void getsIndexOfPosition() {
        Document document = makeDocument("abc\ndef");

        assertThat(makeDocument("").indexOfPosition(new Position(0, 0)), is(-1));
        assertThat(makeDocument("").indexOfPosition(new Position(-1, 0)), is(-1));
        assertThat(document.indexOfPosition(new Position(0, 0)), is(0));
        assertThat(document.indexOfPosition(new Position(0, 3)), is(3));
        assertThat(document.indexOfPosition(new Position(1, 0)), is(safeIndex(4, 1)));
        assertThat(document.indexOfPosition(new Position(1, 2)), is(safeIndex(6, 1)));
        assertThat(document.indexOfPosition(new Position(1, 3)), is(-1));
        assertThat(document.indexOfPosition(new Position(0, 6)), is(-1));
        assertThat(document.indexOfPosition(new Position(2, 0)), is(-1));
    }

    @Test
    public void getsPositionAtIndex() {
        Document document = makeDocument("abc\ndef\nhij\n");

        assertThat(makeDocument("").positionAtIndex(0), nullValue());
        assertThat(makeDocument("").positionAtIndex(-1), nullValue());
        assertThat(document.positionAtIndex(0), equalTo(new Position(0, 0)));
        assertThat(document.positionAtIndex(3), equalTo(new Position(0, 3)));
        assertThat(document.positionAtIndex(safeIndex(4, 1)), equalTo(new Position(1, 0)));
        assertThat(document.positionAtIndex(safeIndex(11, 2)), equalTo(new Position(2, 3)));
        assertThat(document.positionAtIndex(safeIndex(12, 3)), nullValue());
    }

    @Test
    public void getsEnd() {
        String s = "abc\n" +
                   "def";
        Document document = makeDocument(s);

        Position end = document.end();

        assertThat(end.getLine(), equalTo(1));
        assertThat(end.getCharacter(), equalTo(3));
    }

    @Test
    public void getsNextIndexOf() {
        Document document = makeDocument("abc\ndef");

        assertThat(makeDocument("").nextIndexOf("a", 0), is(-1));
        assertThat(document.nextIndexOf("a", 0), is(0));
        assertThat(document.nextIndexOf("a", 1), is(-1));
        assertThat(document.nextIndexOf("abc", 0), is(0));
        assertThat(document.nextIndexOf("abc", 1), is(-1)); // doesn't match if match goes out of boundary
        assertThat(document.nextIndexOf(System.lineSeparator(), 3), is(3));
        assertThat(document.nextIndexOf("f", safeIndex(6, 1)), is(safeIndex(6, 1)));
        assertThat(document.nextIndexOf("f", safeIndex(7, 1)), is(-1)); // oob
    }

    @Test
    public void getsLastIndexOf() {
        Document document = makeDocument("abc\ndef");

        assertThat(makeDocument("").lastIndexOf("a", 1), is(-1));
        assertThat(document.lastIndexOf("a", 0), is(0)); // start
        assertThat(document.lastIndexOf("a", 1), is(0));
        assertThat(document.lastIndexOf("a", safeIndex(6, 1)), is(0));
        assertThat(document.lastIndexOf("f", safeIndex(6, 1)), is(safeIndex(6, 1)));
        assertThat(document.lastIndexOf("f", safeIndex(7, 1)), is(safeIndex(6, 1))); // oob
        assertThat(document.lastIndexOf(System.lineSeparator(), 3), is(3));
        assertThat(document.lastIndexOf("ab", 1), is(0));
        assertThat(document.lastIndexOf("ab", 0), is(0)); // can match even if match goes out of boundary
        assertThat(document.lastIndexOf("ab", -1), is(-1));
        assertThat(document.lastIndexOf(" ", safeIndex(8, 1)), is(-1)); // not found
    }

    @Test
    public void getsLineOfIndex() {
        Document empty = makeDocument("");
        Document single = makeDocument("abc");
        Document twoLine = makeDocument("abc\ndef");
        Document leadingAndTrailingWs = makeDocument("\nabc\n");
        Document threeLine = makeDocument("abc\ndef\nhij\n");

        assertThat(empty.lineOfIndex(0), is(-1)); // empty has no lines, so oob
        assertThat(empty.lineOfIndex(1), is(-1)); // oob
        assertThat(single.lineOfIndex(0), is(0)); // start
        assertThat(single.lineOfIndex(2), is(0)); // end
        assertThat(single.lineOfIndex(3), is(-1)); // oob
        assertThat(twoLine.lineOfIndex(1), is(0)); // first line
        assertThat(twoLine.lineOfIndex(safeIndex(4, 1)), is(1)); // second line start
        assertThat(twoLine.lineOfIndex(3), is(0)); // new line
        assertThat(twoLine.lineOfIndex(safeIndex(6, 1)), is(1)); // end
        assertThat(twoLine.lineOfIndex(safeIndex(7, 1)), is(-1)); // oob
        assertThat(leadingAndTrailingWs.lineOfIndex(0), is(0)); // new line
        assertThat(leadingAndTrailingWs.lineOfIndex(safeIndex(1, 1)), is(1)); // start of line
        assertThat(leadingAndTrailingWs.lineOfIndex(safeIndex(4, 1)), is(1)); // new line
        assertThat(threeLine.lineOfIndex(safeIndex(12, 3)), is(-1));
        assertThat(threeLine.lineOfIndex(safeIndex(11, 2)), is(2));
    }

    @Test
    public void copiesDocumentIds() {
        assertThat("%", isDocumentShapeId(nullValue()));

        assertThat("%?!&", isDocumentShapeId(nullValue()));
        assertThat("?!&%", isDocumentShapeId(nullValue()));

        assertThat("%abc.def hij", isDocumentShapeId(withValueAndType("abc.def", DocumentId.Type.ROOT)));
        assertThat("abc.def% hij", isDocumentShapeId(nullValue()));
        assertThat("abc.def %hij", isDocumentShapeId(withValueAndType("hij", DocumentId.Type.ROOT)));
        assertThat("abc.def hij%", isDocumentShapeId(nullValue()));

        makeDocument("com.foo# com.foo$ com.foo. com$foo$bar com...foo $foo .foo #foo");
        makeDocument("com.foo#bar com.foo#bar$baz com.foo foo#bar foo#bar$baz foo$bar");

        assertThat("%com.foo#bar", isDocumentShapeId(withValueAndType("com.foo#bar", DocumentId.Type.ROOT)));
        assertThat("com.foo#%bar", isDocumentShapeId(withValueAndType("com.foo#bar", DocumentId.Type.ROOT)));

        assertThat("%com.foo#bar$baz", isDocumentShapeId(withValueAndType("com.foo#bar", DocumentId.Type.ROOT)));
        assertThat("com.foo#%bar$baz", isDocumentShapeId(withValueAndType("com.foo#bar", DocumentId.Type.ROOT)));
        assertThat("com.foo#bar$%baz", isDocumentShapeId(withValueAndType("com.foo#bar$baz", DocumentId.Type.MEMBER)));
        assertThat("com.foo#bar%$baz", isDocumentShapeId(withValueAndType("com.foo#bar$baz", DocumentId.Type.MEMBER)));

        assertThat("%foo$bar", isDocumentShapeId(withValueAndType("foo", DocumentId.Type.ROOT)));
        assertThat("fo%o$bar", isDocumentShapeId(withValueAndType("foo", DocumentId.Type.ROOT)));

        assertThat("foo%$bar", isDocumentShapeId(withValueAndType("foo$bar", DocumentId.Type.MEMBER)));
        assertThat("foo$%bar", isDocumentShapeId(withValueAndType("foo$bar", DocumentId.Type.MEMBER)));

        assertThat("%$foo", isDocumentShapeId(withValueAndType("$foo", DocumentId.Type.MEMBER)));
    }

    public static Matcher<String> isDocumentShapeId(Matcher<? super DocumentId> matcher) {
        return new CustomTypeSafeMatcher<>("a DocumentShapeId matching " + matcher) {
            @Override
            protected boolean matchesSafely(String item) {
                var twp = TextWithPositions.from(item);
                var document = Document.of(twp.text());
                var id = document.copyDocumentId(twp.positions()[0]);
                return matcher.matches(id);
            }
        };
    }

    // This is used to convert the character offset in a file that assumes a single character
    // line break, and make that same offset safe with multi character line breaks.
    //
    // This is preferable to simply adjusting how we test Document because bugs in these low-level
    // primitive methods will break a lot of stuff, so it's good to be exact.
    public static int safeIndex(int standardOffset, int line) {
        return standardOffset + (line * (System.lineSeparator().length() - 1));
    }

    // Makes a string literal with '\n' newline characters use the actual OS line separator.
    // Don't use this if you didn't manually type out the '\n's.
    public static String safeString(String s) {
        return s.replace("\n", System.lineSeparator());
    }

    private static Document makeDocument(String s) {
        return Document.of(safeString(s));
    }

    public static Matcher<CharSequence> string(String other) {
        return new CustomTypeSafeMatcher<>(other) {
            @Override
            protected boolean matchesSafely(CharSequence item) {
                return other.replace("\n", "\\n").replace("\r", "\\r").equals(item.toString().replace("\n", "\\n").replace("\r", "\\r"));
            }

            @Override
            public void describeMismatchSafely(CharSequence item, Description description) {
                String o = other.replace("\n", "\\n").replace("\r", "\\r");
                String it = item.toString().replace("\n", "\\n").replace("\r", "\\r");
                equalTo(o).describeMismatch(it, description);
            }
        };
    }

    public static Matcher<DocumentId> withValueAndType(String other, DocumentId.Type type) {
        return new CustomTypeSafeMatcher<>(other + " with type: " + type) {
            @Override
            protected boolean matchesSafely(DocumentId item) {
                return item != null && other.equals(item.copyIdValue()) && item.type() == type;
            }
        };
    }
}
