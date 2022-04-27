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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.Test;
import software.amazon.smithy.lsp.SmithyTextDocumentService;
import software.amazon.smithy.lsp.ext.model.SmithyBuildExtensions;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SetUtils;

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

    @Test
    public void definitions() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models").toURI());
        String modelFilename = "main.smithy";
        Path modelMain = baseDir.resolve(modelFilename);
        List<Path> modelFiles = ImmutableList.of(modelMain);

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), modelFiles)) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            StubClient client = new StubClient();
            tds.createProject(hs.getConfig(), hs.getRoot());
            tds.setClient(client);
            TextDocumentIdentifier mainTdi = new TextDocumentIdentifier("file:" + hs.file(modelFilename));

            // Resolves via token => shape name.
            DefinitionParams commentParams = definitionParams(mainTdi, 43, 37);
            Location commentLocation = tds.definition(commentParams).get().getLeft().get(0);

            // Resolves via shape target location in model.
            DefinitionParams memberParams = definitionParams(mainTdi, 12, 18);
            Location memberTargetLocation = tds.definition(memberParams).get().getLeft().get(0);

            // Resolves via member shape target location in prelude.
            DefinitionParams preludeTargetParams = definitionParams(mainTdi, 36, 12);
            Location preludeTargetLocation = tds.definition(preludeTargetParams).get().getLeft().get(0);

            // Resolves to current location.
            DefinitionParams selfParams = definitionParams(mainTdi, 36, 0);
            Location selfLocation = tds.definition(selfParams).get().getLeft().get(0);

            // Resolves via operation input.
            DefinitionParams inputParams = definitionParams(mainTdi, 52, 16);
            Location inputLocation = tds.definition(inputParams).get().getLeft().get(0);

            // Resolves via operation output.
            DefinitionParams outputParams = definitionParams(mainTdi, 53, 17);
            Location outputLocation = tds.definition(outputParams).get().getLeft().get(0);

            // Resolves via operation error.
            DefinitionParams errorParams = definitionParams(mainTdi, 54, 14);
            Location errorLocation = tds.definition(errorParams).get().getLeft().get(0);

            // Resolves via resource ids.
            DefinitionParams idParams = definitionParams(mainTdi, 75, 29);
            Location idLocation = tds.definition(idParams).get().getLeft().get(0);

            // Resolves via resource read.
            DefinitionParams readParams = definitionParams(mainTdi, 76, 12);
            Location readLocation = tds.definition(readParams).get().getLeft().get(0);

            // Does not correspond to shape.
            DefinitionParams noMatchParams = definitionParams(mainTdi, 0, 0);
            List<Location> noMatchLocationList = (List<Location>) tds.definition(noMatchParams).get().getLeft();

            correctLocation(commentLocation, modelFilename, 20, 0, 21, 14);
            correctLocation(memberTargetLocation, modelFilename, 4, 0, 4, 23);
            correctLocation(selfLocation, modelFilename, 35, 0, 37, 1);
            correctLocation(inputLocation, modelFilename, 57, 0, 61, 1);
            correctLocation(outputLocation, modelFilename, 63, 0, 66, 1);
            correctLocation(errorLocation, modelFilename, 69, 0, 72, 1);
            correctLocation(idLocation, modelFilename, 79, 0, 79, 11);
            correctLocation(readLocation, modelFilename, 51, 0, 55, 1);
            assertTrue(preludeTargetLocation.getUri().endsWith("prelude.smithy"));
            assertTrue(noMatchLocationList.isEmpty());
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

    private DefinitionParams definitionParams(TextDocumentIdentifier tdi, int line, int character) {
        return new DefinitionParams(tdi, new Position(line, character));
    }

    private void correctLocation(Location location, String uri, int startLine, int startCol, int endLine, int endCol) {
        assertEquals(startLine, location.getRange().getStart().getLine());
        assertEquals(startCol, location.getRange().getStart().getCharacter());
        assertEquals(endLine, location.getRange().getEnd().getLine());
        assertEquals(endCol, location.getRange().getEnd().getCharacter());
        assertTrue(location.getUri().endsWith(uri));
    }
}
