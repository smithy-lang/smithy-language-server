/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.fail;
import static software.amazon.smithy.lsp.document.DocumentTest.safeString;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.RequestBuilders;
import software.amazon.smithy.lsp.ServerState;
import software.amazon.smithy.lsp.TestWorkspace;
import software.amazon.smithy.lsp.TextWithPositions;
import software.amazon.smithy.lsp.project.IdlFile;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectLoader;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.lsp.syntax.StatementView;
import software.amazon.smithy.lsp.syntax.Syntax;

public class DefinitionHandlerTest {
    @Test
    public void getsPreludeTraitIdLocations() {
        String text = safeString("""
                $version: "2"
                namespace com.foo
                
                @tags([])
                string Foo
                """);
        GetLocationsResult onAt = getLocations(text, new Position(3, 0));
        GetLocationsResult ok = getLocations(text, new Position(3, 1));
        GetLocationsResult atEnd = getLocations(text, new Position(3, 5));

        assertThat(onAt.locations, empty());

        assertThat(ok.locations, hasSize(1));
        assertThat(ok.locations.getFirst().getUri(), endsWith("prelude.smithy"));
        assertIsShapeDef(ok, ok.locations.getFirst(), "list tags");

        assertThat(atEnd.locations, empty());
    }

    @Test
    public void getsTraitIdsLocationsInCurrentFile() {
        String text = safeString("""
                $version: "2"
                namespace com.foo
                
                @trait
                string foo
                
                @foo
                string Bar
                """);
        GetLocationsResult result = getLocations(text, new Position(6, 1));

        assertThat(result.locations, hasSize(1));
        Location location = result.locations.getFirst();
        assertThat(location.getUri(), endsWith("main.smithy"));
        assertIsShapeDef(result, location, "string foo");
    }

    @Test
    public void shapeDefs() {
        String text = safeString("""
                $version: "2"
                namespace com.foo
                
                structure Foo {}
                
                structure Bar {
                    foo: Foo
                }
                """);
        GetLocationsResult onShapeDef = getLocations(text, new Position(3, 10));
        assertThat(onShapeDef.locations, hasSize(1));
        assertThat(onShapeDef.locations.getFirst().getUri(), endsWith("main.smithy"));
        assertIsShapeDef(onShapeDef, onShapeDef.locations.getFirst(), "structure Foo");

        GetLocationsResult memberTarget = getLocations(text, new Position(6, 9));
        assertThat(memberTarget.locations, hasSize(1));
        assertThat(memberTarget.locations.getFirst().getUri(), endsWith("main.smithy"));
        assertIsShapeDef(memberTarget, memberTarget.locations.getFirst(), "structure Foo");
    }

    @Test
    public void forResource() {
        String text = safeString("""
                $version: "2"
                namespace com.foo
                
                resource Foo {}
                
                structure Bar for Foo {}
                """);
        GetLocationsResult result = getLocations(text, new Position(5, 18));
        assertThat(result.locations, hasSize(1));
        assertThat(result.locations.getFirst().getUri(), endsWith("main.smithy"));
        assertIsShapeDef(result, result.locations.getFirst(), "resource Foo");
    }

    @Test
    public void mixin() {
        String text = safeString("""
                $version: "2"
                namespace com.foo
                
                @mixin
                structure Foo {}
                
                structure Bar with [Foo] {}
                """);
        GetLocationsResult result = getLocations(text, new Position(6, 20));
        assertThat(result.locations, hasSize(1));
        assertThat(result.locations.getFirst().getUri(), endsWith("main.smithy"));
        assertIsShapeDef(result, result.locations.getFirst(), "structure Foo");
    }

    @Test
    public void useTarget() {
        String text = safeString("""
                $version: "2"
                namespace com.foo
                use smithy.api#tags
                """);
        GetLocationsResult result = getLocations(text, new Position(2, 4));
        assertThat(result.locations, hasSize(1));
        assertThat(result.locations.getFirst().getUri(), endsWith("prelude.smithy"));
        assertIsShapeDef(result, result.locations.getFirst(), "list tags");
    }

    @Test
    public void applyTarget() {
        String text = safeString("""
                $version: "2"
                namespace com.foo
                
                structure Foo {}
                
                apply Foo @tags([])
                """);
        GetLocationsResult result = getLocations(text, new Position(5, 6));
        assertThat(result.locations, hasSize(1));
        assertThat(result.locations.getFirst().getUri(), endsWith("main.smithy"));
        assertIsShapeDef(result, result.locations.getFirst(), "structure Foo");
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
        GetLocationsResult result = getLocations(text, new Position(5, 17));
        assertThat(result.locations, hasSize(1));
        assertThat(result.locations.getFirst().getUri(), endsWith("main.smithy"));
        assertIsShapeDef(result, result.locations.getFirst(), "operation Bar");
    }

    @Test
    public void nestedNodeMemberTarget() {
        String text = safeString("""
                $version: "2"
                namespace com.foo
                
                resource Foo {
                    identifiers: {
                        foo: String
                    }
                }
                """);
        GetLocationsResult result = getLocations(text, new Position(5, 13));
        assertThat(result.locations, hasSize(1));
        assertThat(result.locations.getFirst().getUri(), endsWith("prelude.smithy"));
        assertIsShapeDef(result, result.locations.getFirst(), "string String");
    }

    @Test
    public void traitValueTopLevelKey() {
        String text = safeString("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure foo {
                    bar: String
                }
                
                @foo(bar: "")
                string Baz
                """);
        GetLocationsResult result = getLocations(text, new Position(8, 7));
        assertThat(result.locations, hasSize(1));
        assertThat(result.locations.getFirst().getUri(), endsWith("main.smithy"));
        assertIsShapeDef(result, result.locations.getFirst(), "bar: String");
    }

    @Test
    public void traitValueNestedKey() {
        String text = safeString("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure foo {
                    bar: BarList
                }
                
                list BarList {
                    member: Bar
                }
                
                structure Bar {
                    baz: String
                }
                
                @foo(bar: [{ baz: "one" }, { baz: "two" }])
                string S
                """);
        GetLocationsResult result = getLocations(text, new Position(16, 29));
        assertThat(result.locations, hasSize(1));
        assertThat(result.locations.getFirst().getUri(), endsWith("main.smithy"));
        assertIsShapeDef(result, result.locations.getFirst(), "baz: String");
    }

    @Test
    public void elidedMixinMember() {
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
        GetLocationsResult result = getLocations(text, new Position(9, 4));
        assertThat(result.locations, hasSize(1));
        assertThat(result.locations.getFirst().getUri(), endsWith("main.smithy"));
        assertIsShapeDef(result, result.locations.getFirst(), "structure Foo");
    }

    @Test
    public void elidedResourceMember() {
        String text = safeString("""
                $version: "2"
                namespace com.foo
                
                resource Foo {
                    identifiers: {
                        bar: String
                    }
                }
                
                structure Bar for Foo {
                    $bar
                }
                """);
        GetLocationsResult result = getLocations(text, new Position(10, 4));
        assertThat(result.locations, hasSize(1));
        assertThat(result.locations.getFirst().getUri(), endsWith("main.smithy"));
        assertIsShapeDef(result, result.locations.getFirst(), "resource Foo");
    }

    @Test
    public void idRefTraitValue() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @idRef
                string ShapeId
                
                @trait
                structure foo {
                    id: ShapeId
                }
                
                string Bar
                
                @foo(id: %Bar)
                structure Baz {}
                """);
        GetLocationsResult result = getLocations(text);
        assertThat(result.locations, hasSize(1));
        assertThat(result.locations.getFirst().getUri(), endsWith("main.smithy"));
        assertIsShapeDef(result, result.locations.getFirst(), "string Bar");
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
        GetLocationsResult result = getLocations(text);
        assertThat(result.locations, hasSize(1));
        assertThat(result.locations.getFirst().getUri(), endsWith("prelude.smithy"));
        assertIsShapeDef(result, result.locations.getFirst(), "string String");
    }

    private static void assertIsShapeDef(
            GetLocationsResult result,
            Location location,
            String expected
    ) {
        String uri = location.getUri();
        SmithyFile smithyFile = (SmithyFile) result.handler.project.getProjectFile(uri);
        assertThat(smithyFile, notNullValue());

        int documentIndex = smithyFile.document().indexOfPosition(location.getRange().getStart());
        assertThat(documentIndex, greaterThanOrEqualTo(0));

        StatementView view = StatementView.createAt(((IdlFile) smithyFile).getParse(), documentIndex).orElse(null);
        assertThat(view, notNullValue());
        assertThat(view.statementIndex(), greaterThanOrEqualTo(0));

        var statement = view.getStatement();
        if (statement instanceof Syntax.Statement.ShapeDef shapeDef) {
            String shapeType = shapeDef.shapeType().stringValue();
            String shapeName = shapeDef.shapeName().stringValue();
            assertThat(shapeType + " " + shapeName, equalTo(expected));
        } else if (statement instanceof Syntax.Statement.MemberDef memberDef) {
            String memberName = memberDef.name().stringValue();
            String memberTarget = memberDef.target().stringValue();
            assertThat(memberName + ": " + memberTarget, equalTo(expected));
        } else {
            fail("Expected shape or member def, but was " + statement.getClass().getName());
        }
    }

    record GetLocationsResult(DefinitionHandler handler, List<Location> locations) {}

    private static GetLocationsResult getLocations(TextWithPositions textWithPositions) {
        return getLocations(textWithPositions.text(), textWithPositions.positions());
    }

    private static GetLocationsResult getLocations(String text, Position... positions) {
        TestWorkspace workspace = TestWorkspace.singleModel(text);
        Project project = ProjectLoader.load(workspace.getRoot(), new ServerState()).unwrap();
        String uri = workspace.getUri("main.smithy");
        SmithyFile smithyFile = (SmithyFile) project.getProjectFile(uri);

        List<Location> locations = new ArrayList<>();
        DefinitionHandler handler = new DefinitionHandler(project, (IdlFile) smithyFile);
        for (Position position : positions) {
            DefinitionParams params = RequestBuilders.positionRequest()
                    .uri(uri)
                    .position(position)
                    .buildDefinition();
            locations.addAll(handler.handle(params));
        }

        return new GetLocationsResult(handler, locations);
    }
}
