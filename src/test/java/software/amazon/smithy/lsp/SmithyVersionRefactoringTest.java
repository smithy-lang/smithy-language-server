/*
 * Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.lsp;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static software.amazon.smithy.lsp.SmithyLanguageServerTest.initFromWorkspace;
import static software.amazon.smithy.lsp.document.DocumentTest.safeString;

import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeActionTriggerKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.diagnostics.SmithyDiagnostics;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.protocol.LspAdapter;

/**
 * This test suite test the generation of the correct {@link CodeAction} given {@link CodeActionParams}
 * but also the generation of the right version {@link org.eclipse.lsp4j.Diagnostic} given a file with
 * some content in it.
 */
public class SmithyVersionRefactoringTest {
    @Test
    public void noVersionDiagnostic() throws Exception {
        String model = safeString("""
                namespace com.foo
                string Foo
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        StubClient client = new StubClient();
        SmithyLanguageServer server = initFromWorkspace(workspace, client);
        String uri = workspace.getUri("main.smithy");

        server.didOpen(new RequestBuilders.DidOpen().uri(uri).text(model).build());

        List<Diagnostic> diagnostics = server.getFileDiagnostics(uri);
        List<String> codes = diagnostics.stream()
                .filter(d -> d.getCode().isLeft())
                .map(d -> d.getCode().getLeft())
                .collect(Collectors.toList());
        assertThat(codes, hasItem(SmithyDiagnostics.DEFINE_VERSION));

        List<Diagnostic> defineVersionDiagnostics = diagnostics.stream()
                .filter(d -> d.getCode().isLeft())
                .filter(d -> d.getCode().getLeft().equals(SmithyDiagnostics.DEFINE_VERSION))
                .collect(Collectors.toList());
        assertThat(defineVersionDiagnostics, hasSize(1));

        CodeActionContext context = new CodeActionContext(diagnostics);
        context.setTriggerKind(CodeActionTriggerKind.Automatic);
        CodeActionParams codeActionParams = new CodeActionParams(
                new TextDocumentIdentifier(uri),
                LspAdapter.point(0, 3),
                context);
        List<Either<Command, CodeAction>> response = server.codeAction(codeActionParams).get();
        assertThat(response, hasSize(1));
        CodeAction action = response.get(0).getRight();
        assertThat(action.getEdit().getChanges(), hasKey(uri));
        List<TextEdit> edits = action.getEdit().getChanges().get(uri);
        assertThat(edits, hasSize(1));
        TextEdit edit = edits.get(0);
        Document document = server.getFirstProject().getDocument(uri);
        document.applyEdit(edit.getRange(), edit.getNewText());
        assertThat(document.copyText(), equalTo(safeString("""
                $version: "1"

                namespace com.foo
                string Foo
                """)));
    }

    @Test
    public void oldVersionDiagnostic() throws Exception {
        String model = """
                $version: "1"
                namespace com.foo
                string Foo
                """;
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        StubClient client = new StubClient();
        SmithyLanguageServer server = initFromWorkspace(workspace, client);
        String uri = workspace.getUri("main.smithy");

        server.didOpen(new RequestBuilders.DidOpen().uri(uri).text(model).build());

        List<Diagnostic> diagnostics = server.getFileDiagnostics(uri);
        List<String> codes = diagnostics.stream()
                .filter(d -> d.getCode().isLeft())
                .map(d -> d.getCode().getLeft())
                .collect(Collectors.toList());
        assertThat(codes, hasItem(SmithyDiagnostics.UPDATE_VERSION));

        List<Diagnostic> updateVersionDiagnostics = diagnostics.stream()
                .filter(d -> d.getCode().isLeft())
                .filter(d -> d.getCode().getLeft().equals(SmithyDiagnostics.UPDATE_VERSION))
                .collect(Collectors.toList());
        assertThat(updateVersionDiagnostics, hasSize(1));

        Diagnostic diagnostic = updateVersionDiagnostics.get(0);
        assertThat(diagnostic.getRange().getStart(), equalTo(new Position(0, 0)));
        assertThat(diagnostic.getRange().getEnd(), equalTo(new Position(0, 13)));
        CodeActionContext context = new CodeActionContext(diagnostics);
        context.setTriggerKind(CodeActionTriggerKind.Automatic);
        CodeActionParams codeActionParams = new CodeActionParams(
                new TextDocumentIdentifier(uri),
                LspAdapter.point(0, 3),
                context);
        List<Either<Command, CodeAction>> response = server.codeAction(codeActionParams).get();
        assertThat(response, hasSize(1));
        CodeAction action = response.get(0).getRight();
        assertThat(action.getEdit().getChanges(), hasKey(uri));
        List<TextEdit> edits = action.getEdit().getChanges().get(uri);
        assertThat(edits, hasSize(1));
        TextEdit edit = edits.get(0);
        Document document = server.getFirstProject().getDocument(uri);
        document.applyEdit(edit.getRange(), edit.getNewText());
        assertThat(document.copyText(), equalTo("""
                $version: "2"
                namespace com.foo
                string Foo
                """));
    }

    @Test
    public void mostRecentVersion() {
        String model = """
                $version: "2"
                namespace com.foo
                string Foo
                """;
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);
        String uri = workspace.getUri("main.smithy");

        server.didOpen(new RequestBuilders.DidOpen().uri(uri).text(model).build());

        List<Diagnostic> diagnostics = server.getFileDiagnostics(uri);
        List<String> codes = diagnostics.stream()
                .filter(d -> d.getCode().isLeft())
                .map(d -> d.getCode().getLeft())
                .filter(c -> c.equals(SmithyDiagnostics.DEFINE_VERSION)
                    || c.equals(SmithyDiagnostics.UPDATE_VERSION))
                .collect(Collectors.toList());
        assertThat(codes, hasSize(0));
    }

    @Test
    public void noShapes() {
        String model = "namespace com.foo\n";
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);
        String uri = workspace.getUri("main.smithy");

        server.didOpen(new RequestBuilders.DidOpen().uri(uri).text(model).build());

        List<Diagnostic> diagnostics = server.getFileDiagnostics(uri);
        List<String> codes = diagnostics.stream()
                .filter(d -> d.getCode().isLeft())
                .map(d -> d.getCode().getLeft())
                .collect(Collectors.toList());
        assertThat(codes, containsInAnyOrder(SmithyDiagnostics.DEFINE_VERSION));
    }
}
