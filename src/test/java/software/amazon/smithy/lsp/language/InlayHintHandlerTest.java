package software.amazon.smithy.lsp.language;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.LspMatchers;
import software.amazon.smithy.lsp.ServerState;
import software.amazon.smithy.lsp.TestWorkspace;
import software.amazon.smithy.lsp.TextWithPositions;
import software.amazon.smithy.lsp.project.IdlFile;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectLoader;
import software.amazon.smithy.lsp.project.ProjectTest;


import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
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
        var positions = model.positions();
        Position startPosition = new Position(0,0);
        Position endPosition = positions[2];
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        assertThat(hints, hasSize(2));
        assertThat(hints, contains(
                LspMatchers.inlayHint("GetUserRequest", positions[0]),
                LspMatchers.inlayHint("GetUserResponse", positions[1])
        ));

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
        var positions = model.positions();
        Position startPosition = new Position(0,0);
        Position endPosition = positions[2];
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        assertThat(hints, hasSize(2));
        assertThat(hints, contains(
                LspMatchers.inlayHint("GetUserInput", positions[0]),
                LspMatchers.inlayHint("GetUserOutput", positions[1])
        ));
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
        var positions = model.positions();
        Position startPosition = new Position(0,0);
        Position endPosition = positions[1];
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        assertThat(hints, hasSize(1));
        assertThat(hints, contains(
                LspMatchers.inlayHint("GetUserInput", positions[0])
        ));
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
        var positions = model.positions();
        Position startPosition = new Position(0,0);
        Position endPosition = positions[1];
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        assertThat(hints, hasSize(1));
        assertThat(hints, contains(
                LspMatchers.inlayHint("GetUserInput", positions[0])
        ));
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
        var positions = model.positions();
        Position startPosition = new Position(0,0);
        Position endPosition = positions[0];
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
        Position startPosition = positions[0];
        Position endPosition = positions[3];
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        assertThat(hints, hasSize(2));
        assertThat(hints, contains(
                LspMatchers.inlayHint("GetUserRequest", positions[1]),
                LspMatchers.inlayHint("GetUserResponse", positions[2])
        ));
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
        Position startPosition =positions[0];
        Position endPosition = positions[2];
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        assertThat(hints, hasSize(1));
        assertThat(hints, contains(
                LspMatchers.inlayHint("GetUserRequest", positions[1])
        ));
    }

    @Test
    public void inlayHintForInlineOperationNotInRange() {
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
                %
                """);
        var positions = model.positions();
        Position startPosition =positions[0];
        Position endPosition =positions[1];
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        assertThat(hints, hasSize(1));
        assertThat(hints, contains(
                LspMatchers.inlayHint("GetUserOutput", positions[1])
        ));
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
        Position startPosition = positions[0];
        Position endPosition = positions[1];
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        assertThat(hints, hasSize(1));
        assertThat(hints, contains(
                LspMatchers.inlayHint("GetUserOutput", positions[1])
        ));
    }

    @Test
    public void inlayHintForMultipleInlineOperations() {
        TextWithPositions model = TextWithPositions.from("""
                %$version: "2"
                
                operation GetUser {
                    input :=% {
                        userId: String
                    }
                
                    output :=% {
                        username: String
                        userId: String
                    }
                }
                
                operation GetAddress {
                    input :=% {
                        userId: String
                    }
                
                   output :=% {
                        address: String
                    }
                }
                %
                """);
        var positions = model.positions();
        Position startPosition = positions[0];
        Position endPosition = positions[5];
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        assertThat(hints, hasSize(4));
        assertThat(hints, contains(
                LspMatchers.inlayHint("GetUserInput", positions[1]),
                LspMatchers.inlayHint("GetUserOutput", positions[2]),
                LspMatchers.inlayHint("GetAddressInput", positions[3]),
                LspMatchers.inlayHint("GetAddressOutput", positions[4])
        ));
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
                    input :=% {
                        userId: String
                    }
                
                    output := {
                        address: String
                    }
                }
                
                """);
        var positions = model.positions();
        Position startPosition = positions[0];
        Position endPosition = positions[1];
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        assertThat(hints, hasSize(1));
        assertThat(hints, contains(
                LspMatchers.inlayHint("GetAddressInput", positions[1])
        ));
    }

    @Test
    public void inlayHintsForInlineOperationWithMixinAndFor() {
        TextWithPositions model = TextWithPositions.from("""
                %$version: "2"
                
                operation GetUser {
                    output :=% for foo with [bar] {
                            @required
                            check: Boolean
                        }
                }
                %
                """);
        var positions = model.positions();
        Position startPosition = positions[0];
        Position endPosition = positions[2];
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        assertThat(hints, hasSize(1));
        assertEquals("GetUserOutput", hints.get(0).getLabel().getLeft());
        assertEquals(positions[1], hints.get(0).getPosition());

    }

    @Test
    public void inlayHintsForInvalidInlineOperation() {
        TextWithPositions model = TextWithPositions.from("""
                %$version: "2"
                
                operation GetUser {
                    output :=% test
                }
                %
                """);
        var positions = model.positions();
        Position startPosition = positions[0];
        Position endPosition = positions[1];
        List<InlayHint>hints = getInlayHints(model.text(), startPosition, endPosition);
        assertThat(hints, hasSize(1));
        assertThat(hints, contains(
                LspMatchers.inlayHint("GetUserOutput", positions[1])
        ));
    }

    private static List<InlayHint> getInlayHints(String text, Position startPosition, Position endPosition) {
        TestWorkspace workspace = TestWorkspace.singleModel(text);
        Project project = ProjectTest.load(workspace.getRoot());
        String uri = workspace.getUri("main.smithy");
        IdlFile idlFile = (IdlFile) project.getProjectFile(uri);

        var handler = new InlayHintHandler(idlFile.document(),
                idlFile.getParse().statements(),
                new Range(startPosition, endPosition));
        return handler.handle();
    }
}
