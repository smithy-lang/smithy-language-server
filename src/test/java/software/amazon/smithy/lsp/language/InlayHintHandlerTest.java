package software.amazon.smithy.lsp.language;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.ServerState;
import software.amazon.smithy.lsp.TestWorkspace;
import software.amazon.smithy.lsp.TextWithPositions;
import software.amazon.smithy.lsp.project.IdlFile;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectLoader;
import software.amazon.smithy.lsp.project.ProjectTest;


import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class InlayHintHandlerTest {
    @Test
    public void inlayHintForInlineOperationWithCustomizedSuffix() {
        TextWithPositions model = TextWithPositions.from("""
                $version: "2"
                $operationInputSuffix: "Request"
                $operationOutputSuffix: "Response"
                
                namespace smithy.example
                
                operation GetUser {
                    input :=% {
                        userId: String
                    }
                
                    output :=% {
                        username: String
                        userId: String
                    }
                }
                %
                """);
        Position startPosition = new Position(0,0);
        Position endPosition = new Position(model.positions()[2].getLine(),0);
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        var positions = model.positions();
        assertThat(hints, hasSize(2));
        assertEquals(hints.get(0).getPosition(), new Position(positions[0].getLine(), positions[0].getCharacter()));
        assertEquals(hints.get(1).getPosition(), new Position(positions[1].getLine(), positions[1].getCharacter()));
        assertEquals("GetUserRequest", hints.get(0).getLabel().getLeft());
        assertEquals("GetUserResponse", hints.get(1).getLabel().getLeft());
    }

    @Test
    public void inlayHintForInlineOperationWithoutCustomizedSuffix() {
        TextWithPositions model = TextWithPositions.from("""
                $version: "2"
                
                namespace smithy.example
                
                operation GetUser {
                    input :=% {
                        userId: String
                    }
                
                    output :=% {
                        username: String
                        userId: String
                    }
                }
                %
                """);
        Position startPosition = new Position(0,0);
        Position endPosition = new Position(model.positions()[2].getLine(),0);
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        var positions = model.positions();
        assertThat(hints, hasSize(2));
        assertEquals(hints.get(0).getPosition(), new Position(positions[0].getLine(), positions[0].getCharacter()));
        assertEquals(hints.get(1).getPosition(), new Position(positions[1].getLine(), positions[1].getCharacter()));
        assertEquals("GetUserInput", hints.get(0).getLabel().getLeft());
        assertEquals("GetUserOutput", hints.get(1).getLabel().getLeft());
    }

    @Test
    public void inlayHintForInputInlineOperation() {
        TextWithPositions model = TextWithPositions.from("""
                $version: "2"
                
                namespace smithy.example
                
                operation GetUser {
                    input :=% {
                        userId: String
                    }
                }
                %
                """);
        Position startPosition = new Position(0,0);
        Position endPosition = new Position(model.positions()[1].getLine(),0);
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        var positions = model.positions();
        assertThat(hints, hasSize(1));
        assertEquals(new Position(positions[0].getLine(), positions[0].getCharacter()), hints.get(0).getPosition());
        assertEquals("GetUserInput", hints.get(0).getLabel().getLeft());
    }

    @Test
    public void inlayHintForInputInlineOperationWithMismatchedSuffix() {
        TextWithPositions model = TextWithPositions.from("""
                $version: "2"
                
                namespace smithy.example
                $operationOutputSuffix: "Response"
                
                operation GetUser {
                    input :=% {
                        userId: String
                    }
                }
                %
                """);
        Position startPosition = new Position(0,0);
        Position endPosition = new Position(model.positions()[1].getLine(),0);
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        var positions = model.positions();
        assertThat(hints, hasSize(1));
        assertEquals(new Position(positions[0].getLine(), positions[0].getCharacter()), hints.get(0).getPosition());
        assertEquals("GetUserInput", hints.get(0).getLabel().getLeft());
    }

    @Test
    public void inlayHintForOperationWithoutInlineMemberDef() {
        TextWithPositions model = TextWithPositions.from("""
                $version: "2"
                
                namespace smithy.example
                $operationOutputSuffix: "Response"
                
                operation GetUser {
                }
                %
                """);
        Position startPosition = new Position(0,0);
        Position endPosition = new Position(model.positions()[0].getLine(),0);
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        assertThat(hints, hasSize(0));
    }

    @Test
    public void inlayHintForInlineOperationOffRangeSuffix() {
        TextWithPositions model = TextWithPositions.from("""
                $version: "2"
                $operationInputSuffix: "Request"
                $operationOutputSuffix: "Response"
                
                namespace smithy.example
                
                %operation GetUser {
                    input :=% {
                        userId: String
                    }
                
                    output :=% {
                        username: String
                        userId: String
                    }
                }
                %
                """);
        var positions = model.positions();
        Position startPosition = new Position(positions[0].getLine(),0);
        Position endPosition = new Position(positions[3].getLine(),0);
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        assertThat(hints, hasSize(2));
        assertEquals(new Position(positions[1].getLine(), positions[1].getCharacter()), hints.get(0).getPosition());
        assertEquals("GetUserRequest", hints.get(0).getLabel().getLeft());
        assertEquals(new Position(positions[2].getLine(), positions[2].getCharacter()), hints.get(1).getPosition());
        assertEquals("GetUserResponse", hints.get(1).getLabel().getLeft());
    }

    @Test
    public void inlayHintForInlineOperationPartiallyInRange() {
        TextWithPositions model = TextWithPositions.from("""
                $version: "2"
                $operationInputSuffix: "Request"
                $operationOutputSuffix: "Response"
                
                namespace smithy.example
                
                %operation GetUser {
                    input :=% {
                        userId: String
                    }
                %
                    output := {
                        username: String
                        userId: String
                    }
                }
                """);
        var positions = model.positions();
        Position startPosition = new Position(positions[0].getLine(),0);
        Position endPosition = new Position(positions[2].getLine(),0);
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        assertThat(hints, hasSize(1));
        assertEquals(new Position(positions[1].getLine(), positions[1].getCharacter()), hints.get(0).getPosition());
        assertEquals("GetUserRequest", hints.get(0).getLabel().getLeft());
    }

    @Test
    public void inlayHintForInlineOperationNotInRange() {
        TextWithPositions model = TextWithPositions.from("""
                $version: "2"
                
                operation GetUser {
                    input := {
                        userId: String
                    }
                
                %    output := {
                        username: String
                        userId: String
                    }
                }
                %
                """);
        var positions = model.positions();
        Position startPosition = new Position(positions[0].getLine(),0);
        Position endPosition = new Position(positions[1].getLine(),0);
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        assertThat(hints, hasSize(1));
        assertEquals("GetUserOutput", hints.get(0).getLabel().getLeft());
    }

    @Test
    public void inlayHintForInlineOperationInOneLine() {
        TextWithPositions model = TextWithPositions.from("""
                $version: "2"
                
                operation GetUser {
                    input := {
                        userId: String
                    }
                
                %    output :=% {
                        username: String
                        userId: String
                    }
                }
                
                """);
        var positions = model.positions();
        Position startPosition = new Position(positions[0].getLine(),positions[0].getCharacter());
        Position endPosition = new Position(positions[1].getLine(),positions[1].getCharacter());
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        assertThat(hints, hasSize(1));
        assertEquals("GetUserOutput", hints.get(0).getLabel().getLeft());
    }

    @Test
    public void inlayHintForMultipleInlineOperations() {
        TextWithPositions model = TextWithPositions.from("""
                %$version: "2"
                
                operation GetUser {
                    input := {
                        userId: String
                    }
                
                    output := {
                        username: String
                        userId: String
                    }
                }
                
                operation GetAddress {
                    input := {
                        userId: String
                    }
                
                   output := {
                        address: String
                    }
                }
                %
                """);
        var positions = model.positions();
        Position startPosition = new Position(positions[0].getLine(),positions[0].getCharacter());
        Position endPosition = new Position(positions[1].getLine(),positions[1].getCharacter());
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        assertThat(hints, hasSize(4));
        assertEquals("GetUserInput", hints.get(0).getLabel().getLeft());
        assertEquals("GetUserOutput", hints.get(1).getLabel().getLeft());
        assertEquals("GetAddressInput", hints.get(2).getLabel().getLeft());
        assertEquals("GetAddressOutput", hints.get(3).getLabel().getLeft());

    }

    @Test
    public void inlayHintForMultipleInlineOperationWithLimitedRange() {
        TextWithPositions model = TextWithPositions.from("""
                $version: "2"
                
                operation GetUser {
                    input := {
                        userId: String
                    }
                
                    output := {
                        username: String
                        userId: String
                    }
                }
                structure foo{
                    id: String
                }
                
                %operation GetAddress {
                    input := %{
                        userId: String
                    }
                
                    output := {
                        address: String
                    }
                }
                
                """);
        var positions = model.positions();
        Position startPosition = new Position(positions[0].getLine(),positions[0].getCharacter());
        Position endPosition = new Position(positions[1].getLine(),positions[1].getCharacter());
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        assertThat(hints, hasSize(1));
        assertEquals("GetAddressInput", hints.get(0).getLabel().getLeft());
    }

    private static List<InlayHint> getInlayHints(String text, Position startPosition, Position endPosition) {
        TestWorkspace workspace = TestWorkspace.singleModel(text);
        Project project = ProjectTest.load(workspace.getRoot());
        String uri = workspace.getUri("main.smithy");
        IdlFile idlFile = (IdlFile) project.getProjectFile(uri);

        var handler = new InlayHintHandler(idlFile.document(),
                idlFile.getParse().statements(),
                new Range(startPosition, endPosition));
        List<InlayHint> hints = handler.handle();
        return hints;
    }
}
