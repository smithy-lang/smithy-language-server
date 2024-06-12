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
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.protocol.RangeAdapter;

public class DocumentTest {
    @Test
    public void appliesTrailingReplacementEdit() {
        String s = "abc\n" +
                   "def";
        Document document = Document.of(s);

        Range editRange = new RangeAdapter()
                .startLine(1)
                .startCharacter(2)
                .endLine(1)
                .endCharacter(3)
                .build();
        String editText = "g";

        document.applyEdit(editRange, editText);

        assertThat(document.copyText(), equalTo("abc\n" +
                                                "deg"));
        assertThat(document.indexOfLine(0), equalTo(0));
        assertThat(document.indexOfLine(1), equalTo(4));
    }

    @Test
    public void appliesAppendingEdit() {
        String s = "abc\n" +
                   "def";
        Document document = Document.of(s);

        Range editRange = new RangeAdapter()
                .startLine(1)
                .startCharacter(3)
                .endLine(1)
                .endCharacter(3)
                .build();
        String editText = "g";

        document.applyEdit(editRange, editText);

        assertThat(document.copyText(), equalTo("abc\n" +
                                                "defg"));
        assertThat(document.indexOfLine(0), equalTo(0));
        assertThat(document.indexOfLine(1), equalTo(4));
    }

    @Test
    public void appliesLeadingReplacementEdit() {
        String s = "abc\n" +
                   "def";
        Document document = Document.of(s);

        Range editRange = new RangeAdapter()
                .startLine(0)
                .startCharacter(0)
                .endLine(0)
                .endCharacter(1)
                .build();
        String editText = "z";

        document.applyEdit(editRange, editText);

        assertThat(document.copyText(), equalTo("zbc\n" +
                                                "def"));
        assertThat(document.indexOfLine(0), equalTo(0));
        assertThat(document.indexOfLine(1), equalTo(4));
    }

    @Test
    public void appliesPrependingEdit() {
        String s = "abc\n" +
                   "def";
        Document document = Document.of(s);

        Range editRange = new RangeAdapter()
                .startLine(0)
                .startCharacter(0)
                .endLine(0)
                .endCharacter(0)
                .build();
        String editText = "z";

        document.applyEdit(editRange, editText);

        assertThat(document.copyText(), equalTo("zabc\n" +
                                                "def"));
        assertThat(document.indexOfLine(0), equalTo(0));
        assertThat(document.indexOfLine(1), equalTo(5));
    }

    @Test
    public void appliesInnerReplacementEdit() {
        String s = "abc\n" +
                   "def";
        Document document = Document.of(s);

        Range editRange = new RangeAdapter()
                .startLine(0)
                .startCharacter(1)
                .endLine(1)
                .endCharacter(1)
                .build();
        String editText = "zy\n" +
                       "x";

        document.applyEdit(editRange, editText);

        assertThat(document.copyText(), equalTo("azy\n" +
                                                "xef"));
        assertThat(document.indexOfLine(0), equalTo(0));
        assertThat(document.indexOfLine(1), equalTo(4));
    }

    @Test
    public void appliesPrependingAndReplacingEdit() {
        String s = "abc";
        Document document = Document.of(s);

        Range editRange = new RangeAdapter()
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
        Document document = Document.of(s);

        Range editRange = new RangeAdapter()
                .startLine(0)
                .startCharacter(2)
                .endLine(0)
                .endCharacter(2)
                .build();
        String editText = "zx\n" +
                       "y";

        document.applyEdit(editRange, editText);

        assertThat(document.copyText(), equalTo("abzx\n" +
                                                "yc\n" +
                                                "def"));
        assertThat(document.indexOfLine(0), equalTo(0));
        assertThat(document.indexOfLine(1), equalTo(5));
        assertThat(document.indexOfLine(2), equalTo(8));
    }

    @Test
    public void appliesDeletionEdit() {
        String s = "abc\n" +
                   "def";
        Document document = Document.of(s);

        Range editRange = new RangeAdapter()
                .startLine(0)
                .startCharacter(0)
                .endLine(0)
                .endCharacter(1)
                .build();
        String editText = "";

        document.applyEdit(editRange, editText);

        assertThat(document.copyText(), equalTo("bc\n" +
                                                "def"));
        assertThat(document.indexOfLine(0), equalTo(0));
        assertThat(document.indexOfLine(1), equalTo(3));
    }

    @Test
    public void getsIndexOfLine() {
        String s = "abc\n" +
                   "def\n" +
                   "hij\n";
        Document document = Document.of(s);

        assertThat(document.indexOfLine(0), equalTo(0));
        assertThat(document.indexOfLine(-1), equalTo(-1));
        assertThat(document.indexOfLine(1), equalTo(4));
        assertThat(document.indexOfLine(2), equalTo(8));
        assertThat(document.indexOfLine(3), equalTo(12));
        assertThat(document.indexOfLine(4), equalTo(-1));
    }

    @Test
    public void getsIndexOfPosition() {
        Document document = Document.of("abc\ndef");

        assertThat(Document.of("").indexOfPosition(new Position(0, 0)), is(-1));
        assertThat(Document.of("").indexOfPosition(new Position(-1, 0)), is(-1));
        assertThat(document.indexOfPosition(new Position(0, 0)), is(0));
        assertThat(document.indexOfPosition(new Position(0, 3)), is(3));
        assertThat(document.indexOfPosition(new Position(1, 0)), is(4));
        assertThat(document.indexOfPosition(new Position(1, 2)), is(6));
        assertThat(document.indexOfPosition(new Position(1, 3)), is(-1));
        assertThat(document.indexOfPosition(new Position(0, 4)), is(-1));
        assertThat(document.indexOfPosition(new Position(2, 0)), is(-1));
    }

    @Test
    public void getsPositionAtIndex() {
        Document document = Document.of("abc\ndef\nhij\n");

        assertThat(Document.of("").positionAtIndex(0), nullValue());
        assertThat(Document.of("").positionAtIndex(-1), nullValue());
        assertThat(document.positionAtIndex(0), equalTo(new Position(0, 0)));
        assertThat(document.positionAtIndex(3), equalTo(new Position(0, 3)));
        assertThat(document.positionAtIndex(4), equalTo(new Position(1, 0)));
        assertThat(document.positionAtIndex(11), equalTo(new Position(2, 3)));
        assertThat(document.positionAtIndex(12), nullValue());
    }

    @Test
    public void getsEnd() {
        String s = "abc\n" +
                   "def";
        Document document = Document.of(s);

        Position end = document.end();

        assertThat(end.getLine(), equalTo(1));
        assertThat(end.getCharacter(), equalTo(3));
    }

    @Test
    public void borrowsToken() {
        String s = "abc\n" +
                   "def";
        Document document = Document.of(s);

        CharSequence token = document.borrowToken(new Position(0, 2));

        assertThat(token, string("abc"));
    }

    @Test
    public void borrowsTokenWithNoWs() {
        String s = "abc";
        Document document = Document.of(s);

        CharSequence token = document.borrowToken(new Position(0, 1));

        assertThat(token, string("abc"));
    }

    @Test
    public void borrowsTokenAtStart() {
        String s = "abc\n" +
                   "def";
        Document document = Document.of(s);

        CharSequence token = document.borrowToken(new Position(0, 0));

        assertThat(token, string("abc"));
    }

    @Test
    public void borrowsTokenAtEnd() {
        String s = "abc\n" +
                   "def";
        Document document = Document.of(s);

        CharSequence token = document.borrowToken(new Position(1, 2));

        assertThat(token, string("def"));
    }

    @Test
    public void borrowsTokenAtBoundaryStart() {
        String s = "a bc d";
        Document document = Document.of(s);

        CharSequence token = document.borrowToken(new Position(0, 2));

        assertThat(token, string("bc"));
    }

    @Test
    public void borrowsTokenAtBoundaryEnd() {
        String s = "a bc d";
        Document document = Document.of(s);

        CharSequence token = document.borrowToken(new Position(0, 3));

        assertThat(token, string("bc"));
    }

    @Test
    public void doesntBorrowNonToken() {
        String s = "abc def";
        Document document = Document.of(s);

        CharSequence token = document.borrowToken(new Position(0, 3));

        assertThat(token, nullValue());
    }

    @Test
    public void borrowsLine() {
        Document document = Document.of("abc\n\ndef");

        assertThat(Document.of("").borrowLine(0), string(""));
        assertThat(document.borrowLine(0), string("abc\n"));
        assertThat(document.borrowLine(1), string("\n"));
        assertThat(document.borrowLine(2), string("def"));
        assertThat(document.borrowLine(-1), nullValue());
        assertThat(document.borrowLine(3), nullValue());
    }

    @Test
    public void getsNextIndexOf() {
        Document document = Document.of("abc\ndef");

        assertThat(Document.of("").nextIndexOf("a", 0), is(-1));
        assertThat(document.nextIndexOf("a", 0), is(0));
        assertThat(document.nextIndexOf("a", 1), is(-1));
        assertThat(document.nextIndexOf("abc", 0), is(0));
        assertThat(document.nextIndexOf("abc", 1), is(-1)); // doesn't match if match goes out of boundary
        assertThat(document.nextIndexOf("\n", 3), is(3));
        assertThat(document.nextIndexOf("f", 6), is(6));
        assertThat(document.nextIndexOf("f", 7), is(-1)); // oob
    }

    @Test
    public void getsLastIndexOf() {
        Document document = Document.of("abc\ndef");

        assertThat(Document.of("").lastIndexOf("a", 1), is(-1));
        assertThat(document.lastIndexOf("a", 0), is(0)); // start
        assertThat(document.lastIndexOf("a", 1), is(0));
        assertThat(document.lastIndexOf("a", 6), is(0));
        assertThat(document.lastIndexOf("f", 6), is(6));
        assertThat(document.lastIndexOf("f", 7), is(6)); // oob
        assertThat(document.lastIndexOf("\n", 3), is(3));
        assertThat(document.lastIndexOf("ab", 1), is(0));
        assertThat(document.lastIndexOf("ab", 0), is(0)); // can match even if match goes out of boundary
        assertThat(document.lastIndexOf("ab", -1), is(-1));
        assertThat(document.lastIndexOf(" ", 8), is(-1)); // not found
    }

    @Test
    public void borrowsSpan() {
        Document empty = Document.of("");
        Document line = Document.of("abc");
        Document multi = Document.of("abc\ndef\n\n");

        assertThat(empty.borrowSpan(0, 1), nullValue()); // empty
        assertThat(line.borrowSpan(-1, 1), nullValue()); // negative
        assertThat(line.borrowSpan(0, 0), string("")); // empty
        assertThat(line.borrowSpan(0, 1), string("a")); // one
        assertThat(line.borrowSpan(0, 3), string("abc")); // all
        assertThat(line.borrowSpan(0, 4), nullValue()); // oob
        assertThat(multi.borrowSpan(0, 4), string("abc\n")); // with newline
        assertThat(multi.borrowSpan(3, 5), string("\nd")); // inner
        assertThat(multi.borrowSpan(5, 9), string("ef\n\n")); // up to end
    }

    @Test
    public void getsLineOfIndex() {
        Document empty = Document.of("");
        Document single = Document.of("abc");
        Document twoLine = Document.of("abc\ndef");
        Document leadingAndTrailingWs = Document.of("\nabc\n");
        Document threeLine = Document.of("abc\ndef\nhij\n");

        assertThat(empty.lineOfIndex(1), is(-1)); // oob
        assertThat(single.lineOfIndex(0), is(0)); // start
        assertThat(single.lineOfIndex(2), is(0)); // end
        assertThat(single.lineOfIndex(3), is(-1)); // oob
        assertThat(twoLine.lineOfIndex(1), is(0)); // first line
        assertThat(twoLine.lineOfIndex(4), is(1)); // second line start
        assertThat(twoLine.lineOfIndex(3), is(0)); // new line
        assertThat(twoLine.lineOfIndex(6), is(1)); // end
        assertThat(twoLine.lineOfIndex(7), is(-1)); // oob
        assertThat(leadingAndTrailingWs.lineOfIndex(0), is(0)); // new line
        assertThat(leadingAndTrailingWs.lineOfIndex(1), is(1)); // start of line
        assertThat(leadingAndTrailingWs.lineOfIndex(4), is(1)); // new line
        assertThat(threeLine.lineOfIndex(12), is(-1));
        assertThat(threeLine.lineOfIndex(11), is(2));
    }

    @Test
    public void borrowsDocumentShapeId() {
        Document empty = Document.of("");
        Document notId = Document.of("?!&");
        Document onlyId = Document.of("abc");
        Document split = Document.of("abc.def hij");
        Document technicallyBroken = Document.of("com.foo# com.foo$ com.foo. com$foo$bar com...foo $foo .foo #foo");
        Document technicallyValid = Document.of("com.foo#bar com.foo#bar$baz com.foo foo#bar foo#bar$baz foo$bar");

        assertThat(empty.getDocumentIdAt(new Position(0, 0)), nullValue());
        assertThat(notId.getDocumentIdAt(new Position(0, 0)), nullValue());
        assertThat(notId.getDocumentIdAt(new Position(0, 2)), nullValue());
        assertThat(onlyId.getDocumentIdAt(new Position(0, 0)), documentShapeId("abc", DocumentId.Type.ID));
        assertThat(onlyId.getDocumentIdAt(new Position(0, 2)), documentShapeId("abc", DocumentId.Type.ID));
        assertThat(onlyId.getDocumentIdAt(new Position(0, 3)), nullValue());
        assertThat(split.getDocumentIdAt(new Position(0, 0)), documentShapeId("abc.def", DocumentId.Type.NAMESPACE));
        assertThat(split.getDocumentIdAt(new Position(0, 6)), documentShapeId("abc.def", DocumentId.Type.NAMESPACE));
        assertThat(split.getDocumentIdAt(new Position(0, 7)), nullValue());
        assertThat(split.getDocumentIdAt(new Position(0, 8)), documentShapeId("hij", DocumentId.Type.ID));
        assertThat(technicallyBroken.getDocumentIdAt(new Position(0, 0)), documentShapeId("com.foo#", DocumentId.Type.ABSOLUTE_ID));
        assertThat(technicallyBroken.getDocumentIdAt(new Position(0, 3)), documentShapeId("com.foo#", DocumentId.Type.ABSOLUTE_ID));
        assertThat(technicallyBroken.getDocumentIdAt(new Position(0, 7)), documentShapeId("com.foo#", DocumentId.Type.ABSOLUTE_ID));
        assertThat(technicallyBroken.getDocumentIdAt(new Position(0, 9)), documentShapeId("com.foo$", DocumentId.Type.RELATIVE_WITH_MEMBER));
        assertThat(technicallyBroken.getDocumentIdAt(new Position(0, 16)), documentShapeId("com.foo$", DocumentId.Type.RELATIVE_WITH_MEMBER));
        assertThat(technicallyBroken.getDocumentIdAt(new Position(0, 18)), documentShapeId("com.foo.", DocumentId.Type.NAMESPACE));
        assertThat(technicallyBroken.getDocumentIdAt(new Position(0, 25)), documentShapeId("com.foo.", DocumentId.Type.NAMESPACE));
        assertThat(technicallyBroken.getDocumentIdAt(new Position(0, 27)), documentShapeId("com$foo$bar", DocumentId.Type.RELATIVE_WITH_MEMBER));
        assertThat(technicallyBroken.getDocumentIdAt(new Position(0, 30)), documentShapeId("com$foo$bar", DocumentId.Type.RELATIVE_WITH_MEMBER));
        assertThat(technicallyBroken.getDocumentIdAt(new Position(0, 37)), documentShapeId("com$foo$bar", DocumentId.Type.RELATIVE_WITH_MEMBER));
        assertThat(technicallyBroken.getDocumentIdAt(new Position(0, 39)), documentShapeId("com...foo", DocumentId.Type.NAMESPACE));
        assertThat(technicallyBroken.getDocumentIdAt(new Position(0, 43)), documentShapeId("com...foo", DocumentId.Type.NAMESPACE));
        assertThat(technicallyBroken.getDocumentIdAt(new Position(0, 49)), documentShapeId("$foo", DocumentId.Type.RELATIVE_WITH_MEMBER));
        assertThat(technicallyBroken.getDocumentIdAt(new Position(0, 54)), documentShapeId(".foo", DocumentId.Type.NAMESPACE));
        assertThat(technicallyBroken.getDocumentIdAt(new Position(0, 59)), documentShapeId("#foo", DocumentId.Type.ABSOLUTE_ID));
        assertThat(technicallyValid.getDocumentIdAt(new Position(0, 0)), documentShapeId("com.foo#bar", DocumentId.Type.ABSOLUTE_ID));
        assertThat(technicallyValid.getDocumentIdAt(new Position(0, 12)), documentShapeId("com.foo#bar$baz", DocumentId.Type.ABSOLUTE_WITH_MEMBER));
        assertThat(technicallyValid.getDocumentIdAt(new Position(0, 28)), documentShapeId("com.foo", DocumentId.Type.NAMESPACE));
        assertThat(technicallyValid.getDocumentIdAt(new Position(0, 36)), documentShapeId("foo#bar", DocumentId.Type.ABSOLUTE_ID));
        assertThat(technicallyValid.getDocumentIdAt(new Position(0, 44)), documentShapeId("foo#bar$baz", DocumentId.Type.ABSOLUTE_WITH_MEMBER));
        assertThat(technicallyValid.getDocumentIdAt(new Position(0, 56)), documentShapeId("foo$bar", DocumentId.Type.RELATIVE_WITH_MEMBER));
    }

    public static Matcher<CharSequence> string(String other) {
        return new CustomTypeSafeMatcher<CharSequence>(other) {
            @Override
            protected boolean matchesSafely(CharSequence item) {
                return other.equals(item.toString());
            }
        };
    }

    public static Matcher<DocumentId> documentShapeId(String other, DocumentId.Type type) {
        return new CustomTypeSafeMatcher<DocumentId>(other + " with type: " + type) {
            @Override
            protected boolean matchesSafely(DocumentId item) {
                return other.equals(item.copyIdValue()) && item.getType() == type;
            }
        };
    }
}
