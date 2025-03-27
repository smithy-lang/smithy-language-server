/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static software.amazon.smithy.lsp.LspMatchers.isLocationIncluding;
import static software.amazon.smithy.lsp.UtilMatchers.throwsWithMessage;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ReferenceParams;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.RequestBuilders;
import software.amazon.smithy.lsp.TestWorkspace;
import software.amazon.smithy.lsp.TextWithPositions;
import software.amazon.smithy.lsp.project.IdlFile;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectTest;

public class ReferencesHandlerTest {
    @Test
    public void shapeDef() {
        var twp = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                string %Foo
                
                structure Bar {
                    foo: %Foo
                }
                
                resource Baz {
                    identifiers: {
                        foo: %Foo
                    }
                    properties: {
                        foo: %Foo
                    }
                    put: %Foo
                }
                
                service Bux {
                    operations: [%Foo]
                    rename: {
                        "%com.foo#Foo": "Renamed"
                    }
                }
                """);
        var result = getLocations(twp);

        assertHasAllLocations(result, twp.positions());
    }

    @Test
    public void traitId() {
        var twp = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure %myTrait {
                    ref: ShapeId
                }
                
                @idRef
                string ShapeId
                
                @%myTrait
                string Foo
                
                @%myTrait(ref: %myTrait)
                string Bar
                """);
        var result = getLocations(twp);

        assertHasAllLocations(result, twp.positions());
    }

    @Test
    public void idRef() {
        var twp = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure myTrait {
                    ref: %ShapeId
                }
                
                @idRef
                string %ShapeId
                
                @myTrait(ref: %ShapeId)
                string Foo
                """);
        var result = getLocations(twp);

        assertHasAllLocations(result, twp.positions());
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
                string %Foo
                """);
        var result = getLocations(twp);

        assertHasAllLocations(result, twp.positions());
    }

    @Test
    public void mapIdRef() {
        var twp = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                map myTrait {
                    @idRef
                    key: String
                
                    @idRef
                    value: String
                }
                
                @myTrait(
                    "%com.foo#Foo": %Foo
                    "%com.foo#Foo$foo": %Foo$foo
                )
                structure %Foo {
                    foo: %Foo
                }
                """);
        var result = getLocations(twp);

        assertHasAllLocations(result, twp.positions());
    }

    @Test
    public void rootShapeReferencesIncludeIdsWithMembers() {
        var twp = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure myTrait {
                    @idRef
                    ref: String
                }
                
                @myTrait(ref: %Foo$foo)
                structure %Foo {
                    foo: String
                }
                """);
        var result = getLocations(twp);

        assertHasAllLocations(result, twp.positions());
    }

    @Test
    public void inlineIoReferences() {
        // No refs on the actual inline shape def. It isn't named in the text, so
        // don't consider it a ref.
        var twp = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure myTrait {
                    @idRef
                    ref: String
                }
                
                @myTrait(ref: %OpInput)
                operation Op {
                    input := {}
                }
                """);
        var result = getLocations(twp);

        assertHasAllLocations(result, twp.positions());
    }

    @Test
    public void serviceRenameReferences() {
        var twp = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure myTrait {
                    @idRef
                    ref: String
                }
                
                service Foo {
                    version: "1"
                    rename: {
                        "%com.foo#Bar": "Baz"
                    }
                }
                
                @myTrait(ref: %Bar)
                structure %Bar {}
                """);
        var result = getLocations(twp);

        assertHasAllLocations(result, twp.positions());
    }

    @Test
    public void referencesInNodeMembers() {
        var twp = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure myTrait {
                    @idRef
                    ref: %Bar
                }
                
                resource Foo {
                    identifiers: {
                        id: %Bar
                    }
                }
                
                @myTrait(ref: %Bar)
                string %Bar
                """);
        var result = getLocations(twp);

        assertHasAllLocations(result, twp.positions());
    }

    @Test
    public void unsupportedReferences() {
        var twp = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure myTrait {
                    @idRef
                    ref: String
                }
                
                @mixin
                @myTrait(ref: Foo$%foo)
                structure Foo {
                    %foo: String
                }
                
                operation Op {
                    %input := {
                        %foo: String
                    }
                    %output: OpOutput
                    %errors: []
                }
                
                structure OpOutput with [Foo] {
                    %$foo
                }
                """);
        var workspace = TestWorkspace.singleModel(twp.text());
        var project = ProjectTest.load(workspace.getRoot());
        var uri = workspace.getUri("main.smithy");
        IdlFile idlFile = (IdlFile) project.getProjectFile(uri);

        for (var position : twp.positions()) {
            assertThat(() -> ReferencesHandler.Config.create(project, idlFile, position),
                    throwsWithMessage(containsString("not supported")));
        }
    }

    private static void assertHasAllLocations(GetLocationsResult result, Position... positions) {
        String uri = result.workspace.getUri("main.smithy");
        List<Matcher<? super Location>> matchers = new ArrayList<>();
        for (Position position : positions) {
            matchers.add(isLocationIncluding(uri, position));
        }
        assertThat(result.locations, everyItem(containsInAnyOrder(matchers)));
    }

    private record GetLocationsResult(TestWorkspace workspace, List<List<Location>> locations) {}

    private static GetLocationsResult getLocations(TextWithPositions twp) {
        TestWorkspace workspace = TestWorkspace.singleModel(twp.text());
        Project project = ProjectTest.load(workspace.getRoot());
        String uri = workspace.getUri("main.smithy");
        IdlFile idlFile = (IdlFile) project.getProjectFile(uri);

        List<List<Location>> locations = new ArrayList<>();
        ReferencesHandler handler = new ReferencesHandler(project, idlFile);
        for (Position position : twp.positions()) {
            ReferenceParams params = RequestBuilders.positionRequest()
                    .uri(uri)
                    .position(position)
                    .buildReference();
            var result = handler.handle(params);
            locations.add(new ArrayList<>(result));
        }

        return new GetLocationsResult(workspace, locations);
    }
}
