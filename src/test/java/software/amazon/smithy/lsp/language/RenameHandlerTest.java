/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.lsp.language;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static software.amazon.smithy.lsp.UtilMatchers.stringEquals;
import static software.amazon.smithy.lsp.UtilMatchers.throwsWithMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.RequestBuilders;
import software.amazon.smithy.lsp.TestWorkspace;
import software.amazon.smithy.lsp.TextWithPositions;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.project.IdlFile;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectTest;

public class RenameHandlerTest {
    @Test
    public void renamesRootShapesInTheSameFile() {
        var twp = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure myTrait {
                    @idRef
                    ref: String
                }
                
                string %Foo
                
                @myTrait(ref: %Foo)
                structure Bar {
                    foo: %Foo
                }
                """);
        var result = getEdits("Baz", twp);

        assertHasEditsForAllPositions(result, twp);

        assertAllEditsMake(result, """
                $version: "2"
                namespace com.foo
                
                @trait
                structure myTrait {
                    @idRef
                    ref: String
                }
                
                string Baz
                
                @myTrait(ref: Baz)
                structure Bar {
                    foo: Baz
                }
                """);
    }

    @Test
    public void renamesAbsoluteIds() {
        var twp = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure myTrait {
                    @idRef
                    ref: String
                }
                
                string %Foo
                
                @myTrait(ref: com.foo#%Foo)
                structure Bar {
                    foo: com.foo#%Foo
                }
                """);
        var result = getEdits("Baz", twp);

        assertHasEditsForAllPositions(result, twp);

        assertAllEditsMake(result, """
                $version: "2"
                namespace com.foo
                
                @trait
                structure myTrait {
                    @idRef
                    ref: String
                }
                
                string Baz
                
                @myTrait(ref: com.foo#Baz)
                structure Bar {
                    foo: com.foo#Baz
                }
                """);
    }

    @Test
    public void renamesRootShapeAbsoluteIdsWithMember() {
        var twp = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure myTrait {
                    @idRef
                    ref: String
                }
                
                @myTrait(ref: %com.foo#Foo$foo)
                structure %Foo {
                    foo: String
                }
                """);
        var result = getEdits("Bar", twp);

        assertHasEditsForAllPositions(result, twp);

        assertAllEditsMake(result, """
                $version: "2"
                namespace com.foo
                
                @trait
                structure myTrait {
                    @idRef
                    ref: String
                }
                
                @myTrait(ref: com.foo#Bar$foo)
                structure Bar {
                    foo: String
                }
                """);
    }

    @Test
    public void multiFileSameNamespace() {
        var twp1 = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                @trait
                structure myTrait {
                    @idRef
                    ref: String
                }
                
                string %Foo
                
                @myTrait(ref: com.foo#%Foo)
                structure Bar {
                    foo: %Foo
                }
                """);
        var twp2 = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @myTrait(ref: com.foo#%Foo)
                structure Abc {
                    foo: %Foo
                }
                """);
        var result = getEdits("Baz", twp1, twp2);

        assertHasEditsForAllPositions(result, twp1, twp2);

        for (var workspaceEdit : result.edits) {
            var edited0 = getEditedText(result.workspace, workspaceEdit, "model-0.smithy");
            assertThat(edited0, stringEquals("""
                    $version: "2"
                    namespace com.foo
                    @trait
                    structure myTrait {
                        @idRef
                        ref: String
                    }
                
                    string Baz
                    
                    @myTrait(ref: com.foo#Baz)
                    structure Bar {
                        foo: Baz
                    }
                    """));
            var edited1 = getEditedText(result.workspace, workspaceEdit, "model-1.smithy");
            assertThat(edited1, stringEquals("""
                    $version: "2"
                    namespace com.foo
                    
                    @myTrait(ref: com.foo#Baz)
                    structure Abc {
                        foo: Baz
                    }
                    """));
        }
    }

    @Test
    public void differentNamespaces() {
        var twp1 = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                structure %Foo {}
                """);
        var twp2 = TextWithPositions.from("""
                $version: "2"
                namespace com.bar
                
                use com.foo#%Foo
                
                structure Bar {
                    foo: %Foo
                }
                """);
        var result = getEdits("Baz", twp1, twp2);

        assertHasEditsForAllPositions(result, twp1, twp2);

        for (var workspaceEdit : result.edits) {
            var edited0 = getEditedText(result.workspace, workspaceEdit, "model-0.smithy");
            assertThat(edited0, stringEquals("""
                    $version: "2"
                    namespace com.foo
                    
                    structure Baz {}
                    """));

            var edited1 = getEditedText(result.workspace, workspaceEdit, "model-1.smithy");
            assertThat(edited1, stringEquals("""
                    $version: "2"
                    namespace com.bar
                    
                    use com.foo#Baz
                    
                    structure Bar {
                        foo: Baz
                    }
                    """));
        }
    }

    @Test
    public void localConflicts() {
        var twp1 = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                structure %Foo {}
                """);
        var twp2 = TextWithPositions.from("""
                $version: "2"
                namespace com.bar
                
                use com.foo#%Foo
                
                structure Bar {
                    foo: %Foo
                }
                
                string Baz
                """);
        var result = getEdits("Baz", twp1, twp2);

        assertHasEditsForAllPositions(result, twp1, twp2);

        for (var workspaceEdit : result.edits) {
            var edited0 = getEditedText(result.workspace, workspaceEdit, "model-0.smithy");
            assertThat(edited0, stringEquals("""
                    $version: "2"
                    namespace com.foo
                    
                    structure Baz {}
                    """));

            var edited1 = getEditedText(result.workspace, workspaceEdit, "model-1.smithy");
            // Note: Formatter can take care of cleaning this up.
            assertThat(edited1, stringEquals("""
                    $version: "2"
                    namespace com.bar
                    
                    
                    
                    structure Bar {
                        foo: com.foo#Baz
                    }
                    
                    string Baz
                    """));
        }
    }

    @Test
    public void importConflicts() {
        var twp1 = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                structure %Foo {}
                """);
        var twp2 = TextWithPositions.from("""
                $version: "2"
                namespace com.bar
                
                use com.foo#%Foo
                use com.baz#Baz
                
                structure Bar {
                    foo: %Foo
                    baz: Baz
                }
                """);
        var twp3 = TextWithPositions.from("""
                $version: "2"
                namespace com.baz
                
                structure Baz {}
                """);
        var result = getEdits("Baz", twp1, twp2, twp3);

        assertHasEditsForAllPositions(result, twp1, twp2, twp3);

        for (var workspaceEdit : result.edits) {
            var edit0 = getEditedText(result.workspace, workspaceEdit, "model-0.smithy");
            assertThat(edit0, stringEquals("""
                    $version: "2"
                    namespace com.foo
                    
                    structure Baz {}
                    """));
            var edit1 = getEditedText(result.workspace, workspaceEdit, "model-1.smithy");
            assertThat(edit1, stringEquals("""
                    $version: "2"
                    namespace com.bar
                    
                    
                    use com.baz#Baz
                    
                    structure Bar {
                        foo: com.foo#Baz
                        baz: Baz
                    }
                    """));

            String uri = result.workspace.getUri("model-2.smithy");
            var unrelatedEdit = workspaceEdit.getChanges().get(uri);
            assertThat(unrelatedEdit, nullValue());
        }
    }

    @Test
    public void importConflictsInDefinitionFile() {
        var twp1 = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                use com.bar#Bar
                
                structure %Foo {
                    foo: %Foo
                    bar: Bar
                }
                """);
        var twp2 = TextWithPositions.from("""
                $version: "2"
                namespace com.bar
                
                structure Bar {}
                """);
        var result = getEdits("Bar", twp1, twp2);

        assertHasEditsForAllPositions(result, twp1, twp2);

        for (var workspaceEdit : result.edits) {
            var edit0 = getEditedText(result.workspace, workspaceEdit, "model-0.smithy");
            assertThat(edit0, stringEquals("""
                    $version: "2"
                    namespace com.foo
                    
                    
                    
                    structure Bar {
                        foo: Bar
                        bar: com.bar#Bar
                    }
                    """));

            String uri = result.workspace.getUri("model-1.smithy");
            var unrelatedEdit = workspaceEdit.getChanges().get(uri);
            assertThat(unrelatedEdit, nullValue());
        }
    }

    @Test
    public void importConflictsInSameNamespaceFile() {
        var twp1 = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                structure %Foo {}
                """);
        var twp2 = TextWithPositions.from("""
                $version: "2"
                namespace com.bar
                
                structure Bar {}
                """);
        var twp3 = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                use com.bar#Bar
                
                structure Baz {
                    foo: %Foo
                    bar: Bar
                }
                """);
        var result = getEdits("Bar", twp1, twp2, twp3);

        assertHasEditsForAllPositions(result, twp1, twp2, twp3);

        for (var workspaceEdit : result.edits) {
            var edit0 = getEditedText(result.workspace, workspaceEdit, "model-0.smithy");
            assertThat(edit0, stringEquals("""
                    $version: "2"
                    namespace com.foo
                    
                    structure Bar {}
                    """));

            String uri = result.workspace.getUri("model-1.smithy");
            var unrelatedEdit = workspaceEdit.getChanges().get(uri);
            assertThat(unrelatedEdit, nullValue());

            var edit2 = getEditedText(result.workspace, workspaceEdit, "model-2.smithy");
            assertThat(edit2, stringEquals("""
                    $version: "2"
                    namespace com.foo
                    
                    use com.bar#Bar
                    
                    structure Baz {
                        foo: com.foo#Bar
                        bar: Bar
                    }
                    """));
        }
    }

    @Test
    public void importConflictsAcrossFiles() {
        var twp1 = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                use com.bar#Bar
                
                structure %Foo {}
                """);
        var twp2 = TextWithPositions.from("""
                $version: "2"
                namespace com.bar
                
                use com.foo#%Foo
                
                structure Bar {
                    foo: %Foo
                }
                """);
        var twp3 = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                use com.bar#Bar
                
                structure A {
                    foo: %Foo
                    bar: Bar
                }
                """);
        var twp4 = TextWithPositions.from("""
                $version: "2"
                namespace com.baz
                
                use com.foo#%Foo
                use com.bar#Bar
                
                structure B {
                    foo: %Foo
                    bar: Bar
                }
                """);
        var result = getEdits("Bar", twp1, twp2, twp3, twp4);

        assertHasEditsForAllPositions(result, twp1, twp2, twp3, twp4);

        for (var workspaceEdit : result.edits) {
            var edit0 = getEditedText(result.workspace, workspaceEdit, "model-0.smithy");
            assertThat(edit0, stringEquals("""
                    $version: "2"
                    namespace com.foo



                    structure Bar {}
                    """));

            var edit1 = getEditedText(result.workspace, workspaceEdit, "model-1.smithy");
            assertThat(edit1, stringEquals("""
                    $version: "2"
                    namespace com.bar
                    
                    
                    
                    structure Bar {
                        foo: com.foo#Bar
                    }
                    """));

            var edit2 = getEditedText(result.workspace, workspaceEdit, "model-2.smithy");
            assertThat(edit2, stringEquals("""
                    $version: "2"
                    namespace com.foo
                    
                    use com.bar#Bar
                    
                    structure A {
                        foo: com.foo#Bar
                        bar: Bar
                    }
                    """));

            var edit3 = getEditedText(result.workspace, workspaceEdit, "model-3.smithy");
            assertThat(edit3, stringEquals("""
                    $version: "2"
                    namespace com.baz
                    
                    
                    use com.bar#Bar
                    
                    structure B {
                        foo: com.foo#Bar
                        bar: Bar
                    }
                    """));
        }
    }

    @Test
    public void importConflictsInTraitsSimple() {
        var twp1 = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                use com.bar#Bar
                
                @trait
                structure myTrait {
                    @idRef
                    ref: String
                }
                
                structure %Foo {
                    @myTrait(ref: Bar$bar)
                    bar: Bar
                }
                """);
        var twp2 = TextWithPositions.from("""
                $version: "2"
                namespace com.bar
                
                use com.foo#myTrait
                
                structure Bar {
                    bar: Bar
                }
                """);
        var result = getEdits("Bar", twp1, twp2);

        assertHasEditsForAllPositions(result, twp1, twp2);
        for (var workspaceEdit : result.edits) {
            var edit0 = getEditedText(result.workspace, workspaceEdit, "model-0.smithy");
            assertThat(edit0, stringEquals("""
                    $version: "2"
                    namespace com.foo
                    
                    
                    
                    @trait
                    structure myTrait {
                        @idRef
                        ref: String
                    }
                    
                    structure Bar {
                        @myTrait(ref: com.bar#Bar$bar)
                        bar: com.bar#Bar
                    }
                    """));
        }
    }

    @Test
    public void importConflictsInTraits() {
        var twp1 = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                use com.bar#Bar
                
                @trait
                structure myTrait {
                    @idRef
                    ref: String
                }
                
                @myTrait(ref: Bar)
                structure %Foo {
                    @myTrait(ref: com.bar#Bar)
                    bar: Bar
                
                    @myTrait(ref: com.bar#Bar$bar)
                    bar2: com.bar#Bar
                
                    @myTrait(ref: Bar$bar)
                    foo: %Foo
                
                    @myTrait(ref: %Foo$bar)
                    foo2: com.foo#%Foo
                
                    @myTrait(ref: com.foo#%Foo$bar)
                    foo3: %Foo
                }
                """);
        var twp2 = TextWithPositions.from("""
                $version: "2"
                namespace com.bar
                
                use com.foo#myTrait
                use com.foo#%Foo
                
                @myTrait(ref: %Foo)
                structure Bar {
                    @myTrait(ref: com.foo#%Foo)
                    foo: %Foo
                
                    @myTrait(ref: com.foo#%Foo$bar)
                    foo2: com.foo#%Foo
                
                    @myTrait(ref: Foo$bar)
                    foo3: %Foo
                
                    bar: Bar
                }
                """);
        var twp3 = TextWithPositions.from("""
                $version: "2"
                namespace com.baz
                
                use com.foo#myTrait
                use com.foo#%Foo
                use com.bar#Bar
                
                @myTrait(ref: %Foo)
                structure Baz {
                    @myTrait(ref: com.foo#%Foo)
                    foo: %Foo
                
                    @myTrait(ref: com.foo#%Foo$bar)
                    bar: Bar
                
                    @myTrait(ref: %Foo$bar)
                    foo2: com.foo#%Foo
                }
                """);
        var result = getEdits("Bar", twp1, twp2, twp3);

        assertHasEditsForAllPositions(result, twp1, twp2, twp3);

        for (var workspaceEdit : result.edits) {
            var edit0 = getEditedText(result.workspace, workspaceEdit, "model-0.smithy");
            assertThat(edit0, stringEquals("""
                    $version: "2"
                    namespace com.foo
                    
                    
                    
                    @trait
                    structure myTrait {
                        @idRef
                        ref: String
                    }
                    
                    @myTrait(ref: com.bar#Bar)
                    structure Bar {
                        @myTrait(ref: com.bar#Bar)
                        bar: com.bar#Bar
                    
                        @myTrait(ref: com.bar#Bar$bar)
                        bar2: com.bar#Bar
                    
                        @myTrait(ref: com.bar#Bar$bar)
                        foo: Bar
                    
                        @myTrait(ref: Bar$bar)
                        foo2: com.foo#Bar
                    
                        @myTrait(ref: com.foo#Bar$bar)
                        foo3: Bar
                    }
                    """));

            var edit1 = getEditedText(result.workspace, workspaceEdit, "model-1.smithy");
            assertThat(edit1, stringEquals("""
                    $version: "2"
                    namespace com.bar
                    
                    use com.foo#myTrait
                    
                    
                    @myTrait(ref: com.foo#Bar)
                    structure Bar {
                        @myTrait(ref: com.foo#Bar)
                        foo: com.foo#Bar
                    
                        @myTrait(ref: com.foo#Bar$bar)
                        foo2: com.foo#Bar
                    
                        @myTrait(ref: com.foo#Bar$bar)
                        foo3: com.foo#Bar
                    
                        bar: Bar
                    }
                    """));

            var edit2 = getEditedText(result.workspace, workspaceEdit, "model-2.smithy");
            assertThat(edit2, stringEquals("""
                    $version: "2"
                    namespace com.baz
                    
                    use com.foo#myTrait
                    
                    use com.bar#Bar
                    
                    @myTrait(ref: com.foo#Bar)
                    structure Baz {
                        @myTrait(ref: com.foo#Bar)
                        foo: com.foo#Bar
                    
                        @myTrait(ref: com.foo#Bar$bar)
                        bar: Bar
                    
                        @myTrait(ref: com.foo#Bar$bar)
                        foo2: com.foo#Bar
                    }
                    """));
        }
    }

    @Test
    public void multipleEditsOnSameLine() {
        var twp = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure myTrait {
                    @idRef
                    ref: String
                
                    @idRef
                    ref2: String
                }
                
                @myTrait(ref: %Foo, ref2: %Foo)
                structure %Foo {
                    foo: %Foo
                }
                """);
        var result = getEdits("A", twp);

        assertHasEditsForAllPositions(result, twp);

        assertAllEditsMake(result, """
                $version: "2"
                namespace com.foo
                
                @trait
                structure myTrait {
                    @idRef
                    ref: String
                
                    @idRef
                    ref2: String
                }
                
                @myTrait(ref: A, ref2: A)
                structure A {
                    foo: A
                }
                """);
    }

    @Test
    public void stringIdRef() {
        var twp = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure myTrait {
                    @idRef
                    ref: String
                }
                
                @myTrait(ref: "%com.foo#Foo")
                structure %Foo {
                    @myTrait(ref: "%com.foo#Foo$foo")
                    foo: String
                }
                """);
        var result = getEdits("Bar", twp);

        assertHasEditsForAllPositions(result, twp);
        assertAllEditsMake(result, """
                $version: "2"
                namespace com.foo
                
                @trait
                structure myTrait {
                    @idRef
                    ref: String
                }
                
                @myTrait(ref: "com.foo#Bar")
                structure Bar {
                    @myTrait(ref: "com.foo#Bar$foo")
                    foo: String
                }
                """);
    }

    @Test
    public void prepare() {
        var twp = TextWithPositions.from("""
                $version: "2"
                namespace com.foo

                @trait
                structure myTrait {
                    @idRef
                    ref: String
                }

                @myTrait(ref: %com.foo#%Foo%$foo)
                structure %Foo% {
                    @myTrait(ref: %Foo%$foo)
                    foo: %Foo%
                }
                
                @myTrait(ref: %Foo%)
                string Bar
                """);
        var onNs = twp.positions()[0];
        var onAbsNameWithMember = twp.positions()[1];
        var onAbsNameWithMemberEnd = twp.positions()[2];
        var onDef = twp.positions()[3];
        var onDefEnd = twp.positions()[4];
        var onRelNameWithMember = twp.positions()[5];
        var onRelNameWithMemberEnd = twp.positions()[6];
        var onTarget = twp.positions()[7];
        var onTargetEnd = twp.positions()[8];
        var onRelName = twp.positions()[9];
        var onRelNameEnd = twp.positions()[10];

        TestWorkspace workspace = TestWorkspace.singleModel(twp.text());
        Project project = ProjectTest.load(workspace.getRoot());
        String uri = workspace.getUri("main.smithy");
        IdlFile idlFile = (IdlFile) project.getProjectFile(uri);
        RenameHandler handler = new RenameHandler(project, idlFile);

        var rangeOnNs = handler.prepare(RequestBuilders.positionRequest()
                .uri(uri).position(onNs).buildPrepareRename());
        var rangeOnAbsNameWithMember = handler.prepare(RequestBuilders.positionRequest()
                .uri(uri).position(onAbsNameWithMember).buildPrepareRename());
        var rangeOnDef = handler.prepare(RequestBuilders.positionRequest()
            .uri(uri).position(onDef).buildPrepareRename());
        var rangeOnRelNameWithMember = handler.prepare(RequestBuilders.positionRequest()
            .uri(uri).position(onRelNameWithMember).buildPrepareRename());
        var rangeOnTarget = handler.prepare(RequestBuilders.positionRequest()
            .uri(uri).position(onTarget).buildPrepareRename());
        var rangeOnRelName = handler.prepare(RequestBuilders.positionRequest()
            .uri(uri).position(onRelName).buildPrepareRename());

        assertThat(rangeOnNs, equalTo(new Range(onAbsNameWithMember, onAbsNameWithMemberEnd)));
        assertThat(rangeOnAbsNameWithMember, equalTo(new Range(onAbsNameWithMember, onAbsNameWithMemberEnd)));
        assertThat(rangeOnDef, equalTo(new Range(onDef, onDefEnd)));
        assertThat(rangeOnRelNameWithMember, equalTo(new Range(onRelNameWithMember, onRelNameWithMemberEnd)));
        assertThat(rangeOnTarget, equalTo(new Range(onTarget, onTargetEnd)));
        assertThat(rangeOnRelName, equalTo(new Range(onRelName, onRelNameEnd)));
    }

    @Test
    public void invalidShapeId() {
        var twp = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                string %Foo
                """);

        assertThat(() -> getEdits("123", twp), throwsWithMessage(containsString("id would be invalid")));
    }

    @Test
    public void referenceInJar() {
        var twp = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                structure Foo {
                    foo: %String
                }
                """);

        assertThat(() -> getEdits("Bar", twp), throwsWithMessage(containsString("jar")));
    }

    private static void assertHasEditsForAllPositions(GetEditsResult result, TextWithPositions... twps) {
        int sum = 0;
        for (TextWithPositions twp : twps) {
            sum += twp.positions().length;
        }
        assertThat(result.edits, hasSize(sum));
    }

    private static void assertAllEditsMake(GetEditsResult result, String expected) {
        assertThat(result.edits, not(empty()));

        for (var edit : result.edits) {
            var editedText = getEditedText(result.workspace, edit, "model-0.smithy");
            assertThat(editedText, stringEquals(expected));
        }
    }

    private static String getEditedText(TestWorkspace workspace, WorkspaceEdit edit, String filename) {
        String uri = workspace.getUri(filename);
        var textEdits = edit.getChanges().get(uri);
        assertThat(textEdits, notNullValue());
        assertThat(textEdits, not(empty()));

        String text = workspace.readFile(filename);
        var document = Document.of(text);
        // Edits have to be applied in reverse order so that an edit earlier in the
        // file doesn't clobber the range a later edit would occupy.
        textEdits.sort((l, r) -> {
            int lIdx = document.indexOfPosition(l.getRange().getStart());
            int rIdx = document.indexOfPosition(r.getRange().getStart());
            return Integer.compare(rIdx, lIdx);
        });

        for (var textEdit : textEdits) {
            var s = document.indexOfPosition(textEdit.getRange().getStart());
            var e = document.indexOfPosition(textEdit.getRange().getEnd());
            var span = document.copySpan(s, e);
            document.applyEdit(textEdit.getRange(), textEdit.getNewText());
            var tmp = document.copyText();
            System.out.println();
        }
        return document.copyText();
    }

    private record GetEditsResult(TestWorkspace workspace, List<WorkspaceEdit> edits) {}

    private static GetEditsResult getEdits(String newName, TextWithPositions... twps) {
        var files = Arrays.stream(twps).map(TextWithPositions::text).toArray(String[]::new);
        TestWorkspace workspace = TestWorkspace.multipleModels(files);
        Project project = ProjectTest.load(workspace.getRoot());
        List<WorkspaceEdit> edits = new ArrayList<>();
        for (int i = 0; i < twps.length; i++) {
            String uri = workspace.getUri("model-" + i + ".smithy");
            IdlFile idlFile = (IdlFile) project.getProjectFile(uri);
            RenameHandler handler = new RenameHandler(project, idlFile);
            for (Position position : twps[i].positions()) {
                var params = RequestBuilders.positionRequest()
                        .uri(uri)
                        .position(position)
                        .buildRename(newName);
                var edit = handler.handle(params);
                edits.add(edit);
            }
        }
        return new GetEditsResult(workspace, edits);
    }
}
