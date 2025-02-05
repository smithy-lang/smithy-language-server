package software.amazon.smithy.lsp.language;


import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.ServerState;
import software.amazon.smithy.lsp.TestWorkspace;
import software.amazon.smithy.lsp.TextWithPositions;
import software.amazon.smithy.lsp.project.IdlFile;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectLoader;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static software.amazon.smithy.lsp.document.DocumentTest.safeString;

public class FoldingRangeHandlerTest {



    @Test
    public void foldingRangeForMultipleImports() {
        TextWithPositions model = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                use example.test1%
                use example.test2
                use example.test3%
                
                structure foo {%
                    bar: String
                }%
                """);

        List<int[]> ranges = getFoldingRanges(model.text());

        assertThat(ranges, hasSize(2));
        assertEquals(ranges.get(0)[0], model.positions()[0].getLine());
        assertEquals(ranges.get(0)[1], model.positions()[1].getLine());
        assertEquals(ranges.get(1)[0], model.positions()[2].getLine());
        assertEquals(ranges.get(1)[1], model.positions()[3].getLine());

    }

    @Test
    public void foldingRangeForSingleStructure() {
        String model = safeString("""
                $version: "2"
                namespace com.foo
                
                structure foo {
                    bar: String
                }
                """);

        List<int[]> ranges = getFoldingRanges(model);

        assertThat(ranges, hasSize(1));
        assertArrayEquals(new int[]{3, 5}, ranges.getFirst());
    }


    @Test
    public void foldingRangeForSingleEmptyShape() {
        String model = safeString("""
                $version: "2"
                namespace com.foo
                
                structure foo {
                
                }
                resource foo {
                
                }
                operation foo {
                
                }
                union foo{
                
                
                }
                service foo{
                
                }
                """);

        List<int[]> ranges = getFoldingRanges(model);

        assertThat(ranges, hasSize(0));

    }

    @Test
    public void foldingRangeForNestedEmptyShape() {
        String model = safeString("""
                resource foo {
                    bar:{
                    
                    }
                }
                """);

        List<int[]> ranges = getFoldingRanges(model);

        assertThat(ranges, hasSize(1));

    }

    @Test
    public void foldingRangeForMultipleAdjacentBlocks() {
        String model = safeString("""
                $version: "2"
                namespace com.foo
                
                structure First {
                    a: String
                }
                structure Second {
                    b: String
                }
                structure Third {
                    c: String
                }
                """);

        List<int[]> ranges = getFoldingRanges(model);

        assertThat(ranges, hasSize(3));

    }

    @Test
    public void foldingRangeForStructureWithComment() {
        TextWithPositions model = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                // Comment before
                structure WithComments {%
                    // Comment inside
                    field1: String,
                    field2: Integer // Inline comment
                    // Comment between fields
                    field3: Boolean
                }% // Comment after
                """);

        List<int[]> ranges = getFoldingRanges(model.text());

        assertThat(ranges, hasSize(1));
        assertEquals(ranges.get(0)[0], model.positions()[0].getLine());
        assertEquals(ranges.get(0)[1], model.positions()[1].getLine());
    }


    @Test
    public void foldingRangeForNestedStructures() {
        String model = safeString("""
                $version: "2"
                namespace com.foo
                
                resource Person {
                    name: {
                        firstName: String,
                        lastName: String
                    },
                    address: {
                        street: String,
                        city: String,
                        country: String
                    }
                    operations: [GetName,
                                 GetAddress,
                                 GetCountry,
                                 GetStreet,
                                 GetCity]
                }
                """);

        List<int[]> ranges = getFoldingRanges(model);

        assertThat(ranges, hasSize(4));
        assertArrayEquals(new int[]{3, 18}, ranges.get(0));
        assertArrayEquals(new int[]{4, 7}, ranges.get(1));
        assertArrayEquals(new int[]{8, 12}, ranges.get(2));
        assertArrayEquals(new int[]{13, 17}, ranges.get(3));
    }



    @Test
    public void foldingRangeForMultipleAndNestedTraits() {
        TextWithPositions model = TextWithPositions.from("""
                @restJson1%
                @title("")
                @cors()
                @sigv4()
                @aws.api#service(%
                    foo: "bar",
                    foo2: "bar"
                )%
                @documentation("foo bar")%
                """);

        List<int[]> ranges = getFoldingRanges(model.text());

        assertThat(ranges, hasSize(2));
        assertEquals(ranges.get(0)[0], model.positions()[1].getLine());
        assertEquals(ranges.get(0)[1], model.positions()[2].getLine());
        assertEquals(ranges.get(1)[0], model.positions()[0].getLine());
        assertEquals(ranges.get(1)[1], model.positions()[3].getLine());
    }

    @Test
    public void foldingRangeTest() {
        TextWithPositions model = TextWithPositions.from("""
                @required
                """);

        List<int[]> ranges = getFoldingRanges(model.text());

    }

    @Test
    public void foldingRangeForTraitsBlockContainNewline() {
        TextWithPositions model = TextWithPositions.from("""
                @restJson1%
                @title("")
                @cors()
                
                @sigv4()
                
                @aws.api#service(%
                    foo: "bar",
                    foo2: "bar"
                )%
                @documentation("foo bar")%
                """);

        List<int[]> ranges = getFoldingRanges(model.text());

        assertThat(ranges, hasSize(2));
        assertEquals(ranges.get(0)[0], model.positions()[1].getLine());
        assertEquals(ranges.get(0)[1], model.positions()[2].getLine());
        assertEquals(ranges.get(1)[0], model.positions()[0].getLine());
        assertEquals(ranges.get(1)[1], model.positions()[3].getLine());
    }

    @Test
    public void foldingRangeForMultipleTraitsBlocks() {
        TextWithPositions model = TextWithPositions.from("""
                @restJson1%
                @title("")
                @cors()%
                structure foo{%
                    bar: String
                }%
                
                @restJson1%
                @title("")
                @cors()%
                
                structure foo2{%
                    bar: String
                }%
                
                """);

        List<int[]> ranges = getFoldingRanges(model.text());

        assertThat(ranges, hasSize(4));
        assertEquals(ranges.get(0)[0], model.positions()[0].getLine());
        assertEquals(ranges.get(0)[1], model.positions()[1].getLine());
        assertEquals(ranges.get(1)[0], model.positions()[2].getLine());
        assertEquals(ranges.get(1)[1], model.positions()[3].getLine());
        assertEquals(ranges.get(2)[0], model.positions()[4].getLine());
        assertEquals(ranges.get(2)[1], model.positions()[5].getLine());
        assertEquals(ranges.get(3)[0], model.positions()[6].getLine());
        assertEquals(ranges.get(3)[1], model.positions()[7].getLine());
    }

    @Test
    public void foldingRangeForTraitWithNestedMembers() {
        TextWithPositions model = TextWithPositions.from("""
                @integration(%
                    requestTemplates: {%
                        "application/json": {%
                            "field1": "value1",
                        }%
                    }%
                )%
                """);

        List<int[]> ranges = getFoldingRanges(model.text());

        assertThat(ranges, hasSize(3));
        assertEquals(ranges.get(0)[0], model.positions()[0].getLine());
        assertEquals(ranges.get(0)[1], model.positions()[5].getLine());
        assertEquals(ranges.get(1)[0], model.positions()[1].getLine());
        assertEquals(ranges.get(1)[1], model.positions()[4].getLine());
        assertEquals(ranges.get(2)[0], model.positions()[2].getLine());
        assertEquals(ranges.get(2)[1], model.positions()[3].getLine());
    }

    @Test
    public void foldingRangeForNestedTraitsWithOperation() {
        TextWithPositions model = TextWithPositions.from("""
                @integration(%
                    requestParameters: {%
                        "param1": "a",
                        "param2": "b",
                        "param3": "c",
                    },%
                    requestTemplates: {%
                        "application/json": "{}"
                    }%
                )%
                
                @http(%
                    uri: "a/b/c"
                    method: "POST"
                    code: 200
                )%
                @documentation("foo bar")%
                operation CreateBeer {%
                    input: Beer
                    output: Beer
                    errors: [%
                        fooException
                    ]%
                }%
                """);

        List<int[]> ranges = getFoldingRanges(model.text());

        assertThat(ranges, hasSize(7));
    }
    @Test
    public void foldingRangeForMixedStructuresAndTraits() {
        TextWithPositions model = TextWithPositions.from("""
                @deprecated%
                @documentation("Additional docs")%
                structure DocumentedStruct {%
                    @required%
                    @range(min: 1, max: 100)%
                    field: Integer
                }%
                """);

        List<int[]> ranges = getFoldingRanges(model.text());

        assertThat(ranges, hasSize(3));
        assertEquals(ranges.get(0)[0], model.positions()[0].getLine());
        assertEquals(ranges.get(0)[1], model.positions()[1].getLine());
        assertEquals(ranges.get(1)[0], model.positions()[2].getLine());
        assertEquals(ranges.get(1)[1], model.positions()[5].getLine());
        assertEquals(ranges.get(2)[0], model.positions()[3].getLine());
        assertEquals(ranges.get(2)[1], model.positions()[4].getLine());
    }

    @Test
    public void foldingRangeForMetaData() {
        TextWithPositions model = TextWithPositions.from("""
                $version: "2"
                metadata test = [
                    {
                        id: "foo"
                        namespace: "m1"
                    },
                    {
                        id: "foo2"
                        namespace: "m2"
                    }
                ]
                """);
        List<int[]> ranges = getFoldingRanges(model.text());
        assertThat(ranges, hasSize(3));
    }

    @Test
    public void foldingRangeForInlineDefinition() {
        TextWithPositions model = TextWithPositions.from("""
                operation foo {
                    input := {
                        string: String
                    }
                    output := {
                        string: String
                    }
                }
                """);
        List<int[]> ranges = getFoldingRanges(model.text());
        assertThat(ranges, hasSize(3));
    }

    @Test
    public void foldingRangeForEnumWithSimpleMember() {
        TextWithPositions model = TextWithPositions.from("""
                enum Stage {
                    ABC
                    DEF
                    GHI
                }
                """);
        List<int[]> ranges = getFoldingRanges(model.text());
        assertThat(ranges, hasSize(1));
    }

    @Test
    public void foldingRangeForEnumWithAssignedMember() {
        TextWithPositions model = TextWithPositions.from("""
                enum Stage {
                    ABC = 1
                    DEF = 2
                    GHI = 3
                }
                """);
        List<int[]> ranges = getFoldingRanges(model.text());
        assertThat(ranges, hasSize(1));
    }

    @Test
    public void foldingRangeForListAndMap() {
        TextWithPositions model = TextWithPositions.from("""
                list StringList{
                    @required
                    @length(min: 1, max: 10)
                    @documentation("Member docs")
                    member: String
                }
                map StringMap {
                    key: String,
                    value: String
                }
                """);
        List<int[]> ranges = getFoldingRanges(model.text());
        assertThat(ranges, hasSize(3));
    }

    private static List<int[]> getFoldingRanges(String text) {
        TestWorkspace workspace = TestWorkspace.singleModel(text);
        Project project = ProjectLoader.load(workspace.getRoot(), new ServerState()).unwrap();
        String uri = workspace.getUri("main.smithy");
        IdlFile idlFile = (IdlFile) project.getProjectFile(uri);

        List<int[]> ranges = new ArrayList<>();
        var handler = new FoldingRangeHandler(idlFile.document(), idlFile.getParse().imports(), idlFile.getParse().statements());

        for (var range : handler.handle()) {
            ranges.add(new int[]{range.getStartLine(), range.getEndLine()});
        }
        for (var range : ranges) {
            System.out.println(range[0] + " " + range[1]);
        }
        return ranges;
    }
}

