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

package software.amazon.smithy.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static software.amazon.smithy.lsp.TestUtils.MAIN_MODEL_FILENAME;
import static software.amazon.smithy.lsp.TestUtils.assertLocationEquals;
import static software.amazon.smithy.lsp.TestUtils.getV1Dir;
import static software.amazon.smithy.lsp.TestUtils.getV2Dir;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.Test;
import software.amazon.smithy.lsp.ext.Harness;
import software.amazon.smithy.lsp.ext.SmithyProjectTest;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SetUtils;

public class SmithyTextDocumentServiceTest {

    @Test
    public void correctlyAttributingDiagnostics() {
        String brokenFileName = "foo/broken.smithy";
        String goodFileName = "good.smithy";

        Map<String, String> files = MapUtils.ofEntries(
                MapUtils.entry(brokenFileName, "$version: \"2\"\nnamespace testFoo\n string_ MyId"),
                MapUtils.entry(goodFileName, "$version: \"2\"\nnamespace testBla"));

        try (Harness hs = Harness.builder().files(files).build()) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            tds.setProject(hs.getProject());

            File broken = hs.file(brokenFileName);
            File good = hs.file(goodFileName);

            // When compiling broken file
            Set<String> filesWithDiagnostics = tds.recompile(broken, Optional.empty()).getRight().stream()
                    .filter(pds -> (pds.getDiagnostics().size() > 0)).map(PublishDiagnosticsParams::getUri)
                    .collect(Collectors.toSet());
            assertEquals(SetUtils.of(uri(broken)), filesWithDiagnostics);

            // When compiling good file
            filesWithDiagnostics = tds.recompile(good, Optional.empty()).getRight().stream()
                    .filter(pds -> (pds.getDiagnostics().size() > 0)).map(PublishDiagnosticsParams::getUri)
                    .collect(Collectors.toSet());
            assertEquals(SetUtils.of(uri(broken)), filesWithDiagnostics);
        }
    }

    @Test
    public void sendingDiagnosticsToTheClient() {
        String brokenFileName = "foo/broken.smithy";
        String goodFileName = "good.smithy";

        Map<String, String> files = MapUtils.ofEntries(
                MapUtils.entry(brokenFileName, "$version: \"2\"\nnamespace testFoo; string_ MyId"),
                MapUtils.entry(goodFileName, "$version: \"2\"\nnamespace testBla"));

        try (Harness hs = Harness.builder().files(files).build()) {
            StubClient client = new StubClient();
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.of(client), hs.getTempFolder());
            tds.setProject(hs.getProject());

            File broken = hs.file(brokenFileName);
            File good = hs.file(goodFileName);

            // OPEN

            tds.didOpen(new DidOpenTextDocumentParams(textDocumentItem(broken, files.get(brokenFileName))));

            // broken file has a diagnostic published against it
            assertEquals(1, filePublishedDiagnostics(broken, client.diagnostics).size());
            assertEquals(ListUtils.of(DiagnosticSeverity.Error), getSeverities(broken, client.diagnostics));
            // To clear diagnostics correctly, we must *explicitly* publish an empty
            // list of diagnostics against files with no errors

            assertEquals(1, filePublishedDiagnostics(good, client.diagnostics).size());
            assertEquals(ListUtils.of(), filePublishedDiagnostics(good, client.diagnostics).get(0).getDiagnostics());

            client.clear();

            // SAVE

            tds.didSave(new DidSaveTextDocumentParams(new TextDocumentIdentifier(uri(broken))));

            // broken file has a diagnostic published against it
            assertEquals(1, filePublishedDiagnostics(broken, client.diagnostics).size());
            assertEquals(ListUtils.of(DiagnosticSeverity.Error), getSeverities(broken, client.diagnostics));
            // To clear diagnostics correctly, we must *explicitly* publish an empty
            // list of diagnostics against files with no errors
            assertEquals(1, filePublishedDiagnostics(good, client.diagnostics).size());
            assertEquals(ListUtils.of(), filePublishedDiagnostics(good, client.diagnostics).get(0).getDiagnostics());
        }
    }

    @Test
    public void attributesDiagnosticsForUnknownTraits() throws Exception {
        String modelFilename = "ext/models/unknown-trait.smithy";
        Path modelFilePath = Paths.get(getClass().getResource(modelFilename).toURI());
        try (Harness hs = Harness.builder().paths(modelFilePath).build()) {
            StubClient client = new StubClient();
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.of(client), hs.getTempFolder());
            tds.setProject(hs.getProject());

            File modelFile = hs.file(modelFilename);

            // There must be one warning diagnostic at the unknown trait's location
            Range unknownTraitRange = new Range(new Position(6, 0), new Position(6, 0));
            long matchingDiagnostics = tds.recompile(modelFile, Optional.empty()).getRight().stream()
                    .flatMap(params -> params.getDiagnostics().stream())
                    .filter(diagnostic -> diagnostic.getSeverity().equals(DiagnosticSeverity.Warning))
                    .filter(diagnostic -> diagnostic.getRange().equals(unknownTraitRange))
                    .count();
            assertEquals(1, matchingDiagnostics);
        }
    }

    @Test
    public void handlingChanges() {
        String fileName1 = "foo/bla.smithy";
        String fileName2 = "good.smithy";

        Map<String, String> files = MapUtils.ofEntries(MapUtils.entry(fileName1, "namespace testFoo\n string MyId"),
                MapUtils.entry(fileName2, "namespace testBla"));

        try (Harness hs = Harness.builder().files(files).build()) {
            StubClient client = new StubClient();
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.of(client), hs.getTempFolder());
            tds.setProject(hs.getProject());

            File file1 = hs.file(fileName1);
            File file2 = hs.file(fileName2);

            // OPEN

            tds.didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(uri(file1), 1),
                    ListUtils.of(new TextDocumentContentChangeEvent("inspect broken"))));

            // Only diagnostics for existing files are reported
            assertEquals(SetUtils.of(uri(file1), uri(file2)), SetUtils.copyOf(getUris(client.diagnostics)));
        }
    }

    @Test
    public void completionsV1() throws Exception {
        Path baseDir = getV1Dir();
        Path modelMain = baseDir.resolve(MAIN_MODEL_FILENAME);
        try (Harness hs = Harness.builder().paths(modelMain).build()) {
            StubClient client = new StubClient();
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.of(client), hs.getTempFolder());
            tds.setProject(hs.getProject());

            TextDocumentIdentifier mainTdi = new TextDocumentIdentifier(hs.file(MAIN_MODEL_FILENAME).toString());

            CompletionParams traitParams = completionParams(mainTdi, 85, 10);
            List<CompletionItem> traitCompletionItems = tds.completion(traitParams).get().getLeft();

            CompletionParams shapeParams = completionParams(mainTdi, 51,16);
            List<CompletionItem> shapeCompletionItems = tds.completion(shapeParams).get().getLeft();

            CompletionParams applyStatementParams = completionParams(mainTdi,83, 23);
            List<CompletionItem> applyStatementCompletionItems = tds.completion(applyStatementParams).get().getLeft();

            CompletionParams whiteSpaceParams = completionParams(mainTdi, 0, 0);
            List<CompletionItem> whiteSpaceCompletionItems = tds.completion(whiteSpaceParams).get().getLeft();

            assertEquals(SetUtils.of("MyOperation", "MyOperationInput", "MyOperationOutput"),
                    completionLabels(shapeCompletionItems));

            assertEquals(SetUtils.of("http(method: \"\", uri: \"\")", "http()", "httpChecksumRequired"),
                    completionLabels(applyStatementCompletionItems));

            assertEquals(SetUtils.of("http(method: \"\", uri: \"\")", "http()", "httpChecksumRequired"),
                    completionLabels(traitCompletionItems));

            assertTrue(whiteSpaceCompletionItems.isEmpty());
        }
    }

    @Test
    public void completionsV2() throws Exception {
        Path baseDir = getV2Dir();
        Path modelMain = baseDir.resolve(MAIN_MODEL_FILENAME);
        try (Harness hs = Harness.builder().paths(modelMain).build()) {
            StubClient client = new StubClient();
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.of(client), hs.getTempFolder());
            tds.setProject(hs.getProject());

            TextDocumentIdentifier mainTdi = new TextDocumentIdentifier(hs.file(MAIN_MODEL_FILENAME).toString());

            CompletionParams traitParams = completionParams(mainTdi, 87, 10);
            List<CompletionItem> traitCompletionItems = tds.completion(traitParams).get().getLeft();

            CompletionParams shapeParams = completionParams(mainTdi, 53, 16);
            List<CompletionItem> shapeCompletionItems = tds.completion(shapeParams).get().getLeft();

            CompletionParams applyStatementParams = completionParams(mainTdi, 85, 23);
            List<CompletionItem> applyStatementCompletionItems = tds.completion(applyStatementParams).get().getLeft();

            CompletionParams whiteSpaceParams = completionParams(mainTdi, 0,0);
            List<CompletionItem> whiteSpaceCompletionItems = tds.completion(whiteSpaceParams).get().getLeft();

            assertEquals(SetUtils.of("MyOperation", "MyOperationInput", "MyOperationOutput"),
                    completionLabels(shapeCompletionItems));

            assertEquals(SetUtils.of("http(method: \"\", uri: \"\")", "http()", "httpChecksumRequired"),
                    completionLabels(applyStatementCompletionItems));

            assertEquals(SetUtils.of("http(method: \"\", uri: \"\")", "http()", "httpChecksumRequired"),
                    completionLabels(traitCompletionItems));

            assertTrue(whiteSpaceCompletionItems.isEmpty());
        }
    }

    @Test
    public void runSelectorV1() {
        Path baseDir = getV1Dir();
        Path modelMain = baseDir.resolve(MAIN_MODEL_FILENAME);

        try (Harness hs = Harness.builder().paths(modelMain).build()) {
            StubClient client = new StubClient();
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.of(client), hs.getTempFolder());
            tds.setProject(hs.getProject());

            Either<Exception, List<Location>> result = tds.runSelector("[id|namespace=com.foo]");

            assertTrue(result.isRight());
            assertFalse(result.getRight().isEmpty());

            Optional<Location> location = result.getRight().stream()
                    .filter(location1 -> location1.getRange().getStart().getLine() == 20)
                    .findFirst();

            assertTrue(location.isPresent());
            assertLocationEquals(location.get(), MAIN_MODEL_FILENAME, 20, 0, 21, 14);
        }
    }

    @Test
    public void runSelectorV2() {
        Path baseDir = getV2Dir();
        Path modelMain = baseDir.resolve(MAIN_MODEL_FILENAME);

        try (Harness hs = Harness.builder().paths(modelMain).build()) {
            StubClient client = new StubClient();
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.of(client), hs.getTempFolder());
            tds.setProject(hs.getProject());
            tds.setClient(client);

            Either<Exception, List<Location>> result = tds.runSelector("[id|namespace=com.foo]");

            assertTrue(result.isRight());
            assertFalse(result.getRight().isEmpty());

            Optional<Location> location = result.getRight().stream()
                    .filter(location1 -> location1.getRange().getStart().getLine() == 22)
                    .findFirst();

            assertTrue(location.isPresent());
            assertLocationEquals(location.get(), MAIN_MODEL_FILENAME, 22, 0, 23, 14);
        }
    }

    @Test
    public void runSelectorAgainstModelWithErrorsV1() {
        Path baseDir = getV1Dir();
        Path broken = baseDir.resolve("broken.smithy");
        try (Harness hs = Harness.builder().paths(broken).build()) {
            StubClient client = new StubClient();
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.of(client), hs.getTempFolder());
            tds.setProject(hs.getProject());

            Either<Exception, List<Location>> result = tds.runSelector("[id|namespace=com.foo]");

            assertTrue(result.isLeft());
            assertTrue(result.getLeft().getMessage().contains("Result contained ERROR severity validation events:"));
        }
    }

    @Test
    public void runSelectorAgainstModelWithErrorsV2() {
        Path baseDir = getV2Dir();
        Path broken = baseDir.resolve("broken.smithy");
        try (Harness hs = Harness.builder().paths(broken).build()) {
            StubClient client = new StubClient();
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.of(client), hs.getTempFolder());
            tds.setProject(hs.getProject());

            Either<Exception, List<Location>> result = tds.runSelector("[id|namespace=com.foo]");

            assertTrue(result.isLeft());
            assertTrue(result.getLeft().getMessage().contains("Result contained ERROR severity validation events:"));
        }
    }

    @Test
    public void ensureVersionDiagnostic() {
        String fileName1 = "no-version.smithy";
        String fileName2 = "old-version.smithy";
        String fileName3 = "good-version.smithy";

        Map<String, String> files = MapUtils.ofEntries(
                MapUtils.entry(fileName1, "namespace test"),
                MapUtils.entry(fileName2, "$version: \"1\"\nnamespace test2"),
                MapUtils.entry(fileName3, "$version: \"2\"\nnamespace test3")
        );

        try (Harness hs = Harness.builder().files(files).build()) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            StubClient client = new StubClient();
            tds.createProject(hs.getConfig(), hs.getRoot());
            tds.setClient(client);

            tds.didOpen(new DidOpenTextDocumentParams(textDocumentItem(hs.file(fileName1), files.get(fileName1))));
            assertEquals(1, fileDiagnostics(hs.file(fileName1), client.diagnostics).size());

            client.clear();

            tds.didOpen(new DidOpenTextDocumentParams(textDocumentItem(hs.file(fileName2), files.get(fileName2))));
            assertEquals(1, fileDiagnostics(hs.file(fileName2), client.diagnostics).size());

            client.clear();

            tds.didOpen(new DidOpenTextDocumentParams(textDocumentItem(hs.file(fileName3), files.get(fileName3))));
            assertEquals(0, fileDiagnostics(hs.file(fileName3), client.diagnostics).size());
        }

    }

    @Test
    public void documentSymbols() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models/document-symbols").toURI());

        String currentFile = "current.smithy";
        Path currentFilePath = baseDir.resolve(currentFile);
        String anotherFile = "another.smithy";
        Path anotherFilePath = baseDir.resolve(anotherFile);

        try (Harness hs = Harness.builder().paths(currentFilePath, anotherFilePath).build()) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            tds.setProject(hs.getProject());

            TextDocumentIdentifier currentDocumentIdent = new TextDocumentIdentifier(uri(hs.file(currentFile)));

            List<Either<SymbolInformation, DocumentSymbol>> symbols =
                    tds.documentSymbol(new DocumentSymbolParams(currentDocumentIdent)).get();

            assertEquals(2, symbols.size());

            assertEquals("city", symbols.get(0).getRight().getName());
            assertEquals(SymbolKind.Field, symbols.get(0).getRight().getKind());

            assertEquals("Weather", symbols.get(1).getRight().getName());
            assertEquals(SymbolKind.Struct, symbols.get(1).getRight().getKind());
        }

    }

    private Set<String> getUris(Collection<PublishDiagnosticsParams> diagnostics) {
        return diagnostics.stream().map(PublishDiagnosticsParams::getUri).collect(Collectors.toSet());
    }

    private List<PublishDiagnosticsParams> filePublishedDiagnostics(File f, List<PublishDiagnosticsParams> diags) {
        return diags.stream().filter(pds -> pds.getUri().equals(uri(f))).collect(Collectors.toList());
    }

    private List<Diagnostic> fileDiagnostics(File f, List<PublishDiagnosticsParams> diags) {
        return diags.stream().filter(pds -> pds.getUri().equals(uri(f))).flatMap(pd -> pd.getDiagnostics().stream())
                .collect(Collectors.toList());
    }

    private List<DiagnosticSeverity> getSeverities(File f, List<PublishDiagnosticsParams> diags) {
        return filePublishedDiagnostics(f, diags).stream()
                .flatMap(pds -> pds.getDiagnostics().stream().map(Diagnostic::getSeverity)).collect(Collectors.toList());
    }

    private TextDocumentItem textDocumentItem(File f, String text) {
        return new TextDocumentItem(uri(f), "smithy", 1, text);
    }

    private String uri(File f) {
        return f.toURI().toString();
    }

    private CompletionParams completionParams(TextDocumentIdentifier tdi, int line, int character) {
        return new CompletionParams(tdi, new Position(line, character));
    }

    private Set<String> completionLabels(List<CompletionItem> completionItems) {
        return completionItems.stream().map(item -> item.getLabel()).collect(Collectors.toSet());
    }
}
