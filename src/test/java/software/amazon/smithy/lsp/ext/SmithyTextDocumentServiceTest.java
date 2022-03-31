/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.lsp.ext;

import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.Test;
import software.amazon.smithy.lsp.SmithyTextDocumentService;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SetUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

public class SmithyTextDocumentServiceTest {

    @Test
    public void correctlyAttributingDiagnostics() throws Exception {
        String brokenFileName = "foo/broken.smithy";
        String goodFileName = "good.smithy";

        Map<String, String> files = MapUtils.ofEntries(
                MapUtils.entry(brokenFileName, "namespace testFoo\n string_ MyId"),
                MapUtils.entry(goodFileName, "namespace testBla"));

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), files)) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            tds.createProject(hs.getConfig(), hs.getRoot());

            File broken = hs.file(brokenFileName);
            File good = hs.file(goodFileName);

            // When compiling broken file
            Set<String> filesWithDiagnostics = tds.recompile(broken, Optional.empty()).getRight().stream()
                    .filter(pds -> (pds.getDiagnostics().size() > 0)).map(pds -> pds.getUri())
                    .collect(Collectors.toSet());
            assertEquals(SetUtils.of(uri(broken)), filesWithDiagnostics);

            // When compiling good file
            filesWithDiagnostics = tds.recompile(good, Optional.empty()).getRight().stream()
                    .filter(pds -> (pds.getDiagnostics().size() > 0)).map(pds -> pds.getUri())
                    .collect(Collectors.toSet());
            assertEquals(SetUtils.of(uri(broken)), filesWithDiagnostics);

        }

    }

    @Test
    public void sendingDiagnosticsToTheClient() throws Exception {
        String brokenFileName = "foo/broken.smithy";
        String goodFileName = "good.smithy";

        Map<String, String> files = MapUtils.ofEntries(
                MapUtils.entry(brokenFileName, "namespace testFoo; string_ MyId"),
                MapUtils.entry(goodFileName, "namespace testBla"));

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), files)) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            StubClient client = new StubClient();
            tds.createProject(hs.getConfig(), hs.getRoot());
            tds.setClient(client);

            File broken = hs.file(brokenFileName);
            File good = hs.file(goodFileName);

            // OPEN

            tds.didOpen(new DidOpenTextDocumentParams(textDocumentItem(broken, files.get(brokenFileName))));

            // broken file has a diganostic published against it
            assertEquals(1, fileDiagnostics(broken, client.diagnostics).size());
            assertEquals(ListUtils.of(DiagnosticSeverity.Error), getSeverities(broken, client.diagnostics));
            // To clear diagnostics correctly, we must *explicitly* publish an empty
            // list of diagnostics against files with no errors

            assertEquals(1, fileDiagnostics(good, client.diagnostics).size());
            assertEquals(ListUtils.of(), fileDiagnostics(good, client.diagnostics).get(0).getDiagnostics());

            client.clear();

            // SAVE

            tds.didSave(new DidSaveTextDocumentParams(new TextDocumentIdentifier(uri(broken))));

            // broken file has a diganostic published against it
            assertEquals(1, fileDiagnostics(broken, client.diagnostics).size());
            assertEquals(ListUtils.of(DiagnosticSeverity.Error), getSeverities(broken, client.diagnostics));
            // To clear diagnostics correctly, we must *explicitly* publish an empty
            // list of diagnostics against files with no errors
            assertEquals(1, fileDiagnostics(good, client.diagnostics).size());
            assertEquals(ListUtils.of(), fileDiagnostics(good, client.diagnostics).get(0).getDiagnostics());

        }

    }

    @Test
    public void handlingChanges() throws Exception {
        String fileName1 = "foo/bla.smithy";
        String fileName2 = "good.smithy";

        Map<String, String> files = MapUtils.ofEntries(MapUtils.entry(fileName1, "namespace testFoo\n string MyId"),
                MapUtils.entry(fileName2, "namespace testBla"));

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), files)) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            StubClient client = new StubClient();
            tds.createProject(hs.getConfig(), hs.getRoot());
            tds.setClient(client);

            File file1 = hs.file(fileName1);
            File file2 = hs.file(fileName2);

            // OPEN

            tds.didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(uri(file1), 1),
                    ListUtils.of(new TextDocumentContentChangeEvent("nmspect broken"))));

            // Only diagnostics for existing files are reported
            assertEquals(SetUtils.of(uri(file1), uri(file2)), SetUtils.copyOf(getUris(client.diagnostics)));

        }

    }

    private class StubClient implements LanguageClient {
        public List<PublishDiagnosticsParams> diagnostics = new ArrayList<>();
        public List<MessageParams> shown = new ArrayList<>();
        public List<MessageParams> logged = new ArrayList<>();

        public StubClient() {
        }

        public void clear() {
            this.diagnostics.clear();
            this.shown.clear();
            this.logged.clear();
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
            this.diagnostics.add(diagnostics);
        }

        @Override
        public void telemetryEvent(Object object) {
            // TODO Auto-generated method stub

        }

        @Override
        public void logMessage(MessageParams message) {
            this.logged.add(message);
        }

        @Override
        public void showMessage(MessageParams messageParams) {
            this.shown.add(messageParams);
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
            // TODO Auto-generated method stub
            return null;
        }
    }

    private Set<String> getUris(Collection<PublishDiagnosticsParams> diagnostics) {
        return diagnostics.stream().map(uri -> uri.getUri()).collect(Collectors.toSet());
    }

    private List<PublishDiagnosticsParams> fileDiagnostics(File f, List<PublishDiagnosticsParams> diags) {
        return diags.stream().filter(pds -> pds.getUri().equals(uri(f))).collect(Collectors.toList());
    }

    private List<DiagnosticSeverity> getSeverities(File f, List<PublishDiagnosticsParams> diags) {
        return fileDiagnostics(f, diags).stream()
                .flatMap(pds -> pds.getDiagnostics().stream().map(d -> d.getSeverity())).collect(Collectors.toList());
    }

    private TextDocumentItem textDocumentItem(File f, String text) {
        return new TextDocumentItem(uri(f), "smithy", 1, text);
    }

    private String uri(File f) {
        return f.toURI().toString();
    }
}
