/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static software.amazon.smithy.lsp.document.DocumentTest.safeString;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.RequestBuilders;
import software.amazon.smithy.lsp.TestWorkspace;
import software.amazon.smithy.lsp.TextWithPositions;
import software.amazon.smithy.lsp.project.IdlFile;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectTest;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.model.validation.Severity;

public class HoverHandlerTest {
    @Test
    public void controlKey() {
        String text = safeString("""
                $version: "2"
                """);
        List<String> hovers = getHovers(text, new Position(0, 1));

        assertThat(hovers, contains(containsString("version")));
    }

    @Test
    public void metadataKey() {
        String text = safeString("""
                metadata suppressions = []
                """);
        List<String> hovers = getHovers(text, new Position(0, 9));

        assertThat(hovers, contains(containsString("Suppressions")));
    }

    @Test
    public void metadataValue() {
        String text = safeString("""
                metadata suppressions = [{id: "foo"}]
                """);
        List<String> hovers = getHovers(text, new Position(0, 26));

        assertThat(hovers, contains(containsString("id")));
    }

    @Test
    public void traitValue() {
        String text = safeString("""
                $version: "2"
                namespace com.foo
                
                @http(method: "GET", uri: "/")
                operation Foo {}
                """);
        List<String> hovers = getHovers(text, new Position(3, 7));

        assertThat(hovers, contains(containsString("method: NonEmptyString")));
    }

    @Test
    public void elidedMember() {
        String text = safeString("""
                $version: "2"
                namespace com.foo
                
                @mixin
                structure Foo {
                    bar: String
                }
                
                structure Bar with [Foo] {
                    $bar
                }
                """);
        List<String> hovers = getHovers(text, new Position(9, 5));

        assertThat(hovers, contains(containsString("bar: String")));
    }

    @Test
    public void nodeMemberTarget() {
        String text = safeString("""
                $version: "2"
                namespace com.foo
                
                service Foo {
                    version: "0"
                    operations: [Bar]
                }
                
                operation Bar {}
                """);
        List<String> hovers = getHovers(text, new Position(5, 17));

        assertThat(hovers, contains(containsString("operation Bar")));
    }

    @Test
    public void absoluteShapeId() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                structure Foo {
                    bar: %smithy.api#String
                }
                """);
        List<String> hovers = getHovers(text);

        assertThat(hovers, contains(containsString("string String")));
    }

    @Test
    public void selfShapeDefinition() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                structure %Foo {}
                """);
        List<String> hovers = getHovers(text);

        assertThat(hovers, contains(containsString("structure Foo")));
    }

    @Test
    public void selfMemberDefinition() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                structure Foo {
                    %bar: String
                }
                """);
        List<String> hovers = getHovers(text);

        assertThat(hovers, contains(containsString("bar: String")));
    }

    @Test
    public void shapeKeywordHover() {
        var twp = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                %service MyService {}
                %operation MyOperation {}
                %resource MyResource {}
                %list MyList {}
                %map MyMap {}
                %structure MyStructure {}
                %union MyUnion {}
                %blob MyBlob
                %timestamp MyTimestamp
                %document MyDocument
                %enum MyEnum {}
                %intEnum MyIntEnum {}
                """);
        var hovers = getHovers(twp);
        assertThat(hovers, contains(
                containsString("Service Reference"),
                containsString("Operation Reference"),
                containsString("Resource Reference"),
                containsString("List Reference"),
                containsString("Map Reference"),
                containsString("Structure Reference"),
                containsString("Union Reference"),
                containsString("binary data"),
                containsString("Timestamp Reference"),
                containsString("Document Reference"),
                containsString("Enum Reference"),
                containsString("IntEnum Reference")
        ));
    }

    @Test
    public void builtinMemberHover() {
        var twp = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                service MyService {
                    %version: ""
                    %operations: []
                    %resources: []
                    %errors: []
                    %rename: {}
                }
                
                operation MyOperation {
                    %input := {}
                    %output := {}
                    %errors: []
                }
                
                operation NoInlineOperation {
                    %input: Foo
                    %output: Foo
                    %errors: []
                }
                
                resource MyResource {
                    %identifiers: {}
                    %properties: {}
                    %create: {}
                    %put: {}
                    %read: {}
                    %update: {}
                    %delete: {}
                    %list: {}
                    %operations: []
                    %collectionOperations: []
                    %resources: []
                }
                """);
        var hovers = getHovers(twp);
        assertThat(hovers, contains(
                containsString("optional version"),
                containsString("operation shapes"),
                containsString("resource shapes"),
                containsString("common errors"),
                containsString("Disambiguates"),
                containsString("input of the operation"),
                containsString("output of the operation"),
                containsString("errors that an operation"),
                containsString("input of the operation"),
                containsString("output of the operation"),
                containsString("errors that an operation"),
                containsString("map of identifier"),
                containsString("map of property"),
                containsString("create a resource"),
                containsString("idempotent"),
                containsString("retrieve the resource"),
                containsString("update the resource"),
                containsString("delete the resource"),
                containsString("list resources"),
                containsString("instance operations"),
                containsString("collection operations"),
                containsString("child resource")
        ));
    }

    @Test
    public void nonShapeKeywordHover() {
        var twp = TextWithPositions.from("""
                $version: "2"
                
                %metadata foo = "foo"
                
                %namespace com.foo
                
                %use com.foo#Foo
                
                %apply Foo @bar
                
                structure Foo %for Bar {}
                
                structure Baz %with [Foo] {}
                """);
        var hovers = getHovers(twp);

        assertThat(hovers, contains(
                containsString("schema-less"),
                containsString("A namespace is"),
                containsString("The use section"),
                containsString("Applies a trait"),
                containsString("Allows referencing"),
                containsString("Mixes in")
        ));
    }

    @Test
    public void builtinsHoverIncludeInheritedDocs() {
        var twp = TextWithPositions.from("""
                $version: "2"
                
                metadata validators = [
                    {
                        %name: ""
                    }
                ]
                
                namespace com.foo
                
                operation MyOperation {
                    %input := {}
                }
                """);
        var hovers = getHovers(twp);

        assertThat(hovers, contains(
                containsString("Validators Reference"),
                containsString("Operation Reference")
        ));
    }

    @Test
    public void builtinHoverDoesntClobberUserDocs() {
        var twp = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                list Foo {
                    /// One
                    %member: String
                }
                
                map Bar {
                    /// Two
                    %key: String
                
                    /// Three
                    %value: String
                }
                
                structure Baz {
                    /// Four
                    /// Five
                    %baz: String
                }
                """);
        var hovers = getHovers(twp);

        assertThat(hovers, contains(
                containsString("One"),
                containsString("Two"),
                containsString("Three"),
                containsString("Four")
        ));
    }

    private static List<String> getHovers(TextWithPositions text) {
        return getHovers(text.text(), text.positions());
    }

    private static List<String> getHovers(String text, Position... positions) {
        TestWorkspace workspace = TestWorkspace.singleModel(text);
        Project project = ProjectTest.load(workspace.getRoot());
        String uri = workspace.getUri("main.smithy");
        SmithyFile smithyFile = (SmithyFile) project.getProjectFile(uri);

        List<String> hover = new ArrayList<>();
        HoverHandler handler = new HoverHandler(project, (IdlFile) smithyFile, Severity.WARNING);
        for (Position position : positions) {
            HoverParams params = RequestBuilders.positionRequest()
                    .uri(uri)
                    .position(position)
                    .buildHover();
            hover.add(handler.handle(params).getContents().getRight().getValue());
        }

        return hover;
    }
}
