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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.Test;
import software.amazon.smithy.lsp.ext.Harness;
import software.amazon.smithy.lsp.ext.SmithyProjectTest;
import software.amazon.smithy.lsp.ext.model.SmithyBuildExtensions;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SetUtils;

public class SmithyTextDocumentServiceTest {

    // All successful hover responses are wrapped between these strings
    private static final String HOVER_DEFAULT_PREFIX = "```smithy\n$version: \"2.0\"\n\n";
    private static final String HOVER_DEFAULT_SUFFIX = "\n```";

    @Test
    public void correctlyAttributingDiagnostics() throws Exception {
        String brokenFileName = "foo/broken.smithy";
        String goodFileName = "good.smithy";

        Map<String, String> files = MapUtils.ofEntries(
                MapUtils.entry(brokenFileName, "$version: \"2\"\nnamespace testFoo\n string_ MyId"),
                MapUtils.entry(goodFileName, "$version: \"2\"\nnamespace testBla"));

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), files)) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            tds.createProject(hs.getConfig(), hs.getRoot());

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
    public void sendingDiagnosticsToTheClient() throws Exception {
        String brokenFileName = "foo/broken.smithy";
        String goodFileName = "good.smithy";

        Map<String, String> files = MapUtils.ofEntries(
                MapUtils.entry(brokenFileName, "$version: \"2\"\nnamespace testFoo; string_ MyId"),
                MapUtils.entry(goodFileName, "$version: \"2\"\nnamespace testBla"));

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), files)) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            StubClient client = new StubClient();
            tds.createProject(hs.getConfig(), hs.getRoot());
            tds.setClient(client);

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
        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), ListUtils.of(modelFilePath))) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            StubClient client = new StubClient();
            tds.createProject(hs.getConfig(), hs.getRoot());
            tds.setClient(client);

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
    public void allowsDefinitionWhenThereAreUnknownTraits() throws Exception {
        Path baseDir = Paths.get(getClass().getResource("ext/models").toURI());
        String modelFilename = "unknown-trait.smithy";
        Path modelFilePath = baseDir.resolve(modelFilename);
        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), ListUtils.of(modelFilePath))) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            StubClient client = new StubClient();
            tds.createProject(hs.getConfig(), hs.getRoot());
            tds.setClient(client);

            // We should still be able to respond with a location when there are unknown traits in the model
            TextDocumentIdentifier tdi = new TextDocumentIdentifier(hs.file(modelFilename).toString());
            int locationCount = tds.definition(definitionParams(tdi, 10, 13)).get().getLeft().size();
            assertEquals(locationCount, 1);
        }
    }

    @Test
    public void allowsHoverWhenThereAreUnknownTraits() throws Exception {
        Path baseDir = Paths.get(getClass().getResource("ext/models").toURI());
        String modelFilename = "unknown-trait.smithy";
        Path modelFilePath = baseDir.resolve(modelFilename);
        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), ListUtils.of(modelFilePath))) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            StubClient client = new StubClient();
            tds.createProject(hs.getConfig(), hs.getRoot());
            tds.setClient(client);

            // We should still be able to respond with hover content when there are unknown traits in the model
            TextDocumentIdentifier tdi = new TextDocumentIdentifier(hs.file(modelFilename).toString());
            Hover hover = tds.hover(hoverParams(tdi, 14, 13)).get();
            correctHover("namespace com.foo\n\n", "structure Bar {\n    member: Foo\n}", hover);
        }
    }

    @Test
    public void hoverOnBrokenShapeAppendsValidations() throws Exception {
        Path baseDir = Paths.get(getClass().getResource("ext/models").toURI());
        String modelFilename = "unknown-trait.smithy";
        Path modelFilePath = baseDir.resolve(modelFilename);
        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), ListUtils.of(modelFilePath))) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            StubClient client = new StubClient();
            tds.createProject(hs.getConfig(), hs.getRoot());
            tds.setClient(client);

            TextDocumentIdentifier tdi = new TextDocumentIdentifier(hs.file(modelFilename).toString());
            Hover hover = tds.hover(hoverParams(tdi, 10, 13)).get();
            MarkupContent hoverContent = hover.getContents().getRight();
            assertEquals(hoverContent.getKind(),"markdown");
            assertTrue(hoverContent.getValue().startsWith("```smithy"));
            assertTrue(hoverContent.getValue().contains("structure Foo {}"));
            assertTrue(hoverContent.getValue().contains("WARNING: Unable to resolve trait `com.external#unknownTrait`"));
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
                    ListUtils.of(new TextDocumentContentChangeEvent("inspect broken"))));

            // Only diagnostics for existing files are reported
            assertEquals(SetUtils.of(uri(file1), uri(file2)), SetUtils.copyOf(getUris(client.diagnostics)));

        }

    }

    @Test
    public void definitionsV1() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models/v1").toURI());
        String modelFilename = "main.smithy";
        Path modelMain = baseDir.resolve(modelFilename);
        List<Path> modelFiles = ListUtils.of(modelMain);

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), modelFiles)) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            StubClient client = new StubClient();
            tds.createProject(hs.getConfig(), hs.getRoot());
            tds.setClient(client);
            TextDocumentIdentifier mainTdi = new TextDocumentIdentifier(hs.file(modelFilename).toString());

            // Resolves via token => shape name.
            DefinitionParams commentParams = definitionParams(mainTdi, 43, 37);
            Location commentLocation = tds.definition(commentParams).get().getLeft().get(0);

            // Resolves via shape target location in model.
            DefinitionParams memberParams = definitionParams(mainTdi, 12, 18);
            Location memberTargetLocation = tds.definition(memberParams).get().getLeft().get(0);

            // Resolves via member shape target location in prelude.
            DefinitionParams preludeTargetParams = definitionParams(mainTdi, 36, 12);
            Location preludeTargetLocation = tds.definition(preludeTargetParams).get().getLeft().get(0);

            // Resolves via top-level trait location in prelude.
            DefinitionParams preludeTraitParams = definitionParams(mainTdi, 25, 3);
            Location preludeTraitLocation = tds.definition(preludeTraitParams).get().getLeft().get(0);

            // Resolves via member-applied trait location in prelude.
            DefinitionParams preludeMemberTraitParams = definitionParams(mainTdi, 59, 10);
            Location preludeMemberTraitLocation = tds.definition(preludeMemberTraitParams).get().getLeft().get(0);

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
            assertTrue(preludeTraitLocation.getUri().endsWith("prelude.smithy"));
            assertTrue(preludeMemberTraitLocation.getUri().endsWith("prelude.smithy"));
            assertTrue(noMatchLocationList.isEmpty());
        }
    }

    @Test
    public void definitionsV2() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models/v2").toURI());
        String modelFilename = "main.smithy";
        Path modelMain = baseDir.resolve(modelFilename);
        List<Path> modelFiles = ListUtils.of(modelMain);

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), modelFiles)) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            StubClient client = new StubClient();
            tds.createProject(hs.getConfig(), hs.getRoot());
            tds.setClient(client);
            TextDocumentIdentifier mainTdi = new TextDocumentIdentifier(hs.file(modelFilename).toString());

            // Resolves via token => shape name.
            DefinitionParams commentParams = definitionParams(mainTdi, 45, 37);
            Location commentLocation = tds.definition(commentParams).get().getLeft().get(0);

            // Resolves via shape target location in model.
            DefinitionParams memberParams = definitionParams(mainTdi, 14, 18);
            Location memberTargetLocation = tds.definition(memberParams).get().getLeft().get(0);

            // Resolves via member shape target location in prelude.
            DefinitionParams preludeTargetParams = definitionParams(mainTdi, 38, 12);
            Location preludeTargetLocation = tds.definition(preludeTargetParams).get().getLeft().get(0);

            // Resolves via top-level trait location in prelude.
            DefinitionParams preludeTraitParams = definitionParams(mainTdi, 27, 3);
            Location preludeTraitLocation = tds.definition(preludeTraitParams).get().getLeft().get(0);

            // Resolves via member-applied trait location in prelude.
            DefinitionParams preludeMemberTraitParams = definitionParams(mainTdi, 61, 10);
            Location preludeMemberTraitLocation = tds.definition(preludeMemberTraitParams).get().getLeft().get(0);

            // Resolves to current location.
            DefinitionParams selfParams = definitionParams(mainTdi, 38, 0);
            Location selfLocation = tds.definition(selfParams).get().getLeft().get(0);

            // Resolves via operation input.
            DefinitionParams inputParams = definitionParams(mainTdi, 54, 16);
            Location inputLocation = tds.definition(inputParams).get().getLeft().get(0);

            // Resolves via operation output.
            DefinitionParams outputParams = definitionParams(mainTdi, 55, 17);
            Location outputLocation = tds.definition(outputParams).get().getLeft().get(0);

            // Resolves via operation error.
            DefinitionParams errorParams = definitionParams(mainTdi, 56, 14);
            Location errorLocation = tds.definition(errorParams).get().getLeft().get(0);

            // Resolves via resource ids.
            DefinitionParams idParams = definitionParams(mainTdi, 77, 29);
            Location idLocation = tds.definition(idParams).get().getLeft().get(0);

            // Resolves via resource read.
            DefinitionParams readParams = definitionParams(mainTdi, 78, 12);
            Location readLocation = tds.definition(readParams).get().getLeft().get(0);

            // Does not correspond to shape.
            DefinitionParams noMatchParams = definitionParams(mainTdi, 0, 0);
            List<Location> noMatchLocationList = (List<Location>) tds.definition(noMatchParams).get().getLeft();

            // Resolves via mixin target on operation input.
            DefinitionParams mixinInputParams = definitionParams(mainTdi, 143, 24);
            Location mixinInputLocation = tds.definition(mixinInputParams).get().getLeft().get(0);

            // Resolves via mixin target on operation output.
            DefinitionParams mixinOutputParams = definitionParams(mainTdi, 149, 36);
            Location mixinOutputLocation = tds.definition(mixinOutputParams).get().getLeft().get(0);

            // Resolves via mixin target on structure.
            DefinitionParams mixinStructureParams = definitionParams(mainTdi, 134, 36);
            Location mixinStructureLocation = tds.definition(mixinStructureParams).get().getLeft().get(0);

            correctLocation(commentLocation, modelFilename, 22, 0, 23, 14);
            correctLocation(memberTargetLocation, modelFilename, 6, 0, 6, 23);
            correctLocation(selfLocation, modelFilename, 37, 0, 39, 1);
            correctLocation(inputLocation, modelFilename, 59, 0, 63, 1);
            correctLocation(outputLocation, modelFilename, 65, 0, 68, 1);
            correctLocation(errorLocation, modelFilename, 71, 0, 74, 1);
            correctLocation(idLocation, modelFilename, 81, 0, 81, 11);
            correctLocation(readLocation, modelFilename, 53, 0, 57, 1);
            correctLocation(mixinInputLocation, modelFilename, 112, 0, 118, 1);
            correctLocation(mixinOutputLocation, modelFilename, 121, 0, 123, 1);
            correctLocation(mixinStructureLocation, modelFilename, 112, 0, 118, 1);
            assertTrue(preludeTargetLocation.getUri().endsWith("prelude.smithy"));
            assertTrue(preludeTraitLocation.getUri().endsWith("prelude.smithy"));
            assertTrue(preludeMemberTraitLocation.getUri().endsWith("prelude.smithy"));
            assertTrue(noMatchLocationList.isEmpty());
        }
    }

    @Test
    public void completionsV1() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models/v1").toURI());
        String modelFilename = "main.smithy";
        Path modelMain = baseDir.resolve(modelFilename);
        List<Path> modelFiles = ListUtils.of(modelMain);
        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), modelFiles)) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            StubClient client = new StubClient();
            tds.createProject(hs.getConfig(), hs.getRoot());
            tds.setClient(client);
            TextDocumentIdentifier mainTdi = new TextDocumentIdentifier(hs.file(modelFilename).toString());

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
    public void hoverV1() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models/v1").toURI());
        String modelFilename = "main.smithy";
        Path modelMain = baseDir.resolve(modelFilename);
        String testFilename = "test.smithy";
        Path modelTest = baseDir.resolve(testFilename);
        String clutteredPreambleFilename = "cluttered-preamble.smithy";
        Path modelClutteredPreamble = baseDir.resolve(clutteredPreambleFilename);
        String extrasToImportFilename = "extras-to-import.smithy";
        Path modelExtras = baseDir.resolve(extrasToImportFilename);
        List<Path> modelFiles = ListUtils.of(modelMain, modelTest, modelClutteredPreamble, modelExtras);
        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), modelFiles)) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            StubClient client = new StubClient();
            tds.createProject(hs.getConfig(), hs.getRoot());
            tds.setClient(client);
            TextDocumentIdentifier mainTdi = new TextDocumentIdentifier(hs.file(modelFilename).toString());
            TextDocumentIdentifier testTdi = new TextDocumentIdentifier(hs.file(testFilename).toString());
            TextDocumentIdentifier clutteredTdi = new TextDocumentIdentifier(hs.file(clutteredPreambleFilename).toString());

            // Namespace and use statements in hover response
            String preludeHoverPrefix = "namespace smithy.api\n\n";
            String mainHoverPrefix = "namespace com.foo\n\n";
            String testHoverPrefix = "namespace com.example\n\nuse com.foo#emptyTraitStruct\n\n";
            String clutteredHoverWithDependenciesPrefix = "namespace com.clutter\n\nuse " +
                    "com.example#OtherStructure\nuse com.extras#Extra\n\n";
            String clutteredHoverWithNoDependenciesPrefix = "namespace com.clutter\n\n";

            // Resolves via top-level trait location in prelude.
            Hover preludeTraitHover = tds.hover(hoverParams(mainTdi, 25, 3)).get();
            MarkupContent preludeTraitHoverContents = preludeTraitHover.getContents().getRight();
            assertEquals(preludeTraitHoverContents.getKind(), "markdown");
            assertTrue(preludeTraitHoverContents.getValue().startsWith(HOVER_DEFAULT_PREFIX + preludeHoverPrefix +
                    "/// Specializes a structure for use only as the input"));
            assertTrue(preludeTraitHoverContents.getValue().endsWith("structure input {}" + HOVER_DEFAULT_SUFFIX));

            // Resolves via member shape target location in prelude.
            Hover preludeMemberTraitHover = tds.hover(hoverParams(mainTdi, 59, 10)).get();
            MarkupContent preludeMemberTraitHoverContents = preludeMemberTraitHover.getContents().getRight();
            assertEquals(preludeMemberTraitHoverContents.getKind(), "markdown");
            assertTrue(preludeMemberTraitHoverContents.getValue().startsWith(HOVER_DEFAULT_PREFIX + preludeHoverPrefix +
                    "/// Marks a structure member as required"));
            assertTrue(preludeMemberTraitHoverContents.getValue().endsWith("structure required {}" + HOVER_DEFAULT_SUFFIX));

            // Resolves via member shape target location in prelude.
            Hover preludeTargetHover = tds.hover(hoverParams(mainTdi, 36, 12)).get();
            correctHover(preludeHoverPrefix , "string String", preludeTargetHover);

            // Resolves via token => shape name.
            Hover commentHover = tds.hover(hoverParams(mainTdi, 43, 37)).get();
            correctHover(mainHoverPrefix, "@input\n@tags([\n    \"foo\"\n])\nstructure MultiTrait {\n    a: String\n}", commentHover);

            // Resolves via shape target location in model.
            Hover memberTargetHover = tds.hover(hoverParams(mainTdi, 12, 18)).get();
            correctHover(mainHoverPrefix, "structure SingleLine {}", memberTargetHover);

            // Resolves from member key to shape target location in model.
            Hover memberIdentifierHover = tds.hover(hoverParams(mainTdi, 64, 7)).get();
            correctHover(preludeHoverPrefix, "string String", memberIdentifierHover);

            // Resolves to current location.
            Hover selfHover = tds.hover(hoverParams(mainTdi, 36, 0)).get();
            correctHover(mainHoverPrefix, "@input\n@tags([\n    \"a\"\n    \"b\"\n    \"c\"\n    \"d\"\n    \"e\"\n    \"f\"\n"
                    + "])\nstructure MultiTraitAndLineComments {\n    a: String\n}", selfHover);

            // Resolves via operation input.
            Hover inputHover = tds.hover(hoverParams(mainTdi, 52, 16)).get();
            correctHover(mainHoverPrefix, "structure MyOperationInput {\n    foo: String\n    @required\n    myId: MyId\n}",
                    inputHover);

            // Resolves via operation output.
            Hover outputHover = tds.hover(hoverParams(mainTdi, 53, 17)).get();
            correctHover(mainHoverPrefix, "structure MyOperationOutput {\n    corge: String\n    qux: String\n}", outputHover);

            // Resolves via operation error.
            Hover errorHover = tds.hover(hoverParams(mainTdi, 54, 14)).get();
            correctHover(mainHoverPrefix, "@error(\"client\")\nstructure MyError {\n    blah: String\n    blahhhh: Integer\n}",
                    errorHover);

            // Resolves via resource ids.
            Hover idHover = tds.hover(hoverParams(mainTdi, 75, 29)).get();
            correctHover(mainHoverPrefix, "string MyId", idHover);

            // Resolves via resource read.
            Hover readHover = tds.hover(hoverParams(mainTdi, 76, 12)).get();
            assertTrue(readHover.getContents().getRight().getValue().contains("@http(\n    method: \"PUT\"\n    "
                    + "uri: \"/bar\"\n    code: 200\n)\n@readonly\noperation MyOperation {\n    input: "
                    + "MyOperationInput\n    output: MyOperationOutput\n    errors: [\n        MyError\n    ]\n}"));

            // Does not correspond to shape.
            Hover noMatchHover = tds.hover(hoverParams(mainTdi, 0, 0)).get();
            assertNull(noMatchHover.getContents().getRight().getValue());

            // Resolves between multiple model files.
            Hover multiFileHover = tds.hover(hoverParams(testTdi, 7, 15)).get();
            correctHover(testHoverPrefix, "@emptyTraitStruct\nstructure OtherStructure {\n    foo: String\n    bar: String\n"
                    + "    baz: Integer\n}", multiFileHover);

            // Resolves a shape including its dependencies in the preamble
            Hover clutteredWithDependenciesHover = tds.hover(hoverParams(clutteredTdi, 25, 17)).get();
            correctHover(clutteredHoverWithDependenciesPrefix, "/// With doc comment\n"
                    + "structure StructureWithDependencies {\n"
                    + "    extra: Extra\n    example: OtherStructure\n}", clutteredWithDependenciesHover);

            // Resolves shape with no dependencies, but doesn't include cluttered preamble
            Hover clutteredWithNoDependenciesHover = tds.hover(hoverParams(clutteredTdi, 30, 17)).get();
            correctHover(clutteredHoverWithNoDependenciesPrefix, "structure StructureWithNoDependencies {\n"
                    + "    member: String\n}", clutteredWithNoDependenciesHover);

        }
    }

    @Test
    public void hoverV2() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models/v2").toURI());
        String modelFilename = "main.smithy";
        Path modelMain = baseDir.resolve(modelFilename);
        String testFilename = "test.smithy";
        Path modelTest = baseDir.resolve(testFilename);
        String clutteredPreambleFilename = "cluttered-preamble.smithy";
        Path modelClutteredPreamble = baseDir.resolve(clutteredPreambleFilename);
        String extrasToImportFilename = "extras-to-import.smithy";
        Path modelExtras = baseDir.resolve(extrasToImportFilename);
        List<Path> modelFiles = ListUtils.of(modelMain, modelTest, modelClutteredPreamble, modelExtras);
        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), modelFiles)) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            StubClient client = new StubClient();
            tds.createProject(hs.getConfig(), hs.getRoot());
            tds.setClient(client);
            TextDocumentIdentifier mainTdi = new TextDocumentIdentifier(hs.file(modelFilename).toString());
            TextDocumentIdentifier testTdi = new TextDocumentIdentifier(hs.file(testFilename).toString());
            TextDocumentIdentifier clutteredTdi = new TextDocumentIdentifier(hs.file(clutteredPreambleFilename).toString());

            // Namespace and use statements in hover response
            String preludeHoverPrefix = "namespace smithy.api\n\n";
            String mainHoverPrefix = "namespace com.foo\n\n";
            String testHoverPrefix = "namespace com.example\n\nuse com.foo#emptyTraitStruct\n\n";
            String clutteredHoverWithDependenciesPrefix = "namespace com.clutter\n\nuse " +
                    "com.example#OtherStructure\nuse com.extras#Extra\n\n";
            String clutteredHoverInlineOpPrefix = "namespace com.clutter\n\n";

            // Resolves via top-level trait location in prelude.
            Hover preludeTraitHover = tds.hover(hoverParams(mainTdi, 27, 3)).get();
            MarkupContent preludeTraitHoverContents = preludeTraitHover.getContents().getRight();
            assertEquals(preludeTraitHoverContents.getKind(), "markdown");
            assertTrue(preludeTraitHoverContents.getValue().startsWith(HOVER_DEFAULT_PREFIX + preludeHoverPrefix
                    + "/// Specializes a structure for use only as the" + " input"));
            assertTrue(preludeTraitHoverContents.getValue().endsWith("structure input {}" + HOVER_DEFAULT_SUFFIX));

            // Resolves via member shape target location in prelude.
            Hover preludeMemberTraitHover = tds.hover(hoverParams(mainTdi, 61, 10)).get();
            MarkupContent preludeMemberTraitHoverContents = preludeMemberTraitHover.getContents().getRight();
            assertEquals(preludeMemberTraitHoverContents.getKind(), "markdown");
            assertTrue(preludeMemberTraitHoverContents.getValue().startsWith(HOVER_DEFAULT_PREFIX + preludeHoverPrefix
                    + "/// Marks a structure member as required"));
            assertTrue(preludeMemberTraitHoverContents.getValue().endsWith("structure required {}" + HOVER_DEFAULT_SUFFIX));

            // Resolves via member shape target location in prelude.
            Hover preludeTargetHover = tds.hover(hoverParams(mainTdi, 38, 12)).get();
            correctHover(preludeHoverPrefix, "string String", preludeTargetHover);

            // Resolves via token => shape name.
            Hover commentHover = tds.hover(hoverParams(mainTdi, 45, 37)).get();
            correctHover(mainHoverPrefix, "@input\n@tags([\n    \"foo\"\n])\nstructure MultiTrait {\n    a: String\n}", commentHover);

            // Resolves via shape target location in model.
            Hover memberTargetHover = tds.hover(hoverParams(mainTdi, 14, 18)).get();
            correctHover(mainHoverPrefix, "structure SingleLine {}", memberTargetHover);

            // Resolves from member key to shape target location in model.
            Hover memberIdentifierHover = tds.hover(hoverParams(mainTdi, 66, 7)).get();
            correctHover(preludeHoverPrefix, "string String", memberIdentifierHover);

            // Resolves to current location.
            Hover selfHover = tds.hover(hoverParams(mainTdi, 38, 0)).get();
            correctHover(mainHoverPrefix, "@input\n@tags([\n    \"a\"\n    \"b\"\n    \"c\"\n    \"d\"\n    \"e\"\n    \"f\"\n"
                    + "])\nstructure MultiTraitAndLineComments {\n    a: String\n}", selfHover);

            // Resolves via operation input.
            Hover inputHover = tds.hover(hoverParams(mainTdi, 54, 16)).get();
            correctHover(mainHoverPrefix, "structure MyOperationInput {\n    foo: String\n    @required\n    myId: MyId\n}",
                    inputHover);

            // Resolves via operation output.
            Hover outputHover = tds.hover(hoverParams(mainTdi, 55, 17)).get();
            correctHover(mainHoverPrefix, "structure MyOperationOutput {\n    corge: String\n    qux: String\n}", outputHover);

            // Resolves via operation error.
            Hover errorHover = tds.hover(hoverParams(mainTdi, 56, 14)).get();
            correctHover(mainHoverPrefix, "@error(\"client\")\nstructure MyError {\n    blah: String\n    blahhhh: Integer\n}",
                    errorHover);

            // Resolves via resource ids.
            Hover idHover = tds.hover(hoverParams(mainTdi, 77, 29)).get();
            correctHover(mainHoverPrefix, "string MyId", idHover);

            // Resolves via resource read.
            Hover readHover = tds.hover(hoverParams(mainTdi, 78, 12)).get();
            assertTrue(readHover.getContents().getRight().getValue().contains("@http(\n    method: \"PUT\"\n    "
                    + "uri: \"/bar\"\n    code: 200\n)\n@readonly\noperation MyOperation {\n    input: "
                    + "MyOperationInput\n    output: MyOperationOutput\n    errors: [\n        MyError\n    ]\n}"));

            // Does not correspond to shape.
            Hover noMatchHover = tds.hover(hoverParams(mainTdi, 0, 0)).get();
            assertNull(noMatchHover.getContents().getRight().getValue());

            // Resolves between multiple model files.
            Hover multiFileHover = tds.hover(hoverParams(testTdi, 7, 15)).get();
            correctHover(testHoverPrefix, "@emptyTraitStruct\nstructure OtherStructure {\n    foo: String\n    bar: String\n"
                    + "    baz: Integer\n}", multiFileHover);

            // Resolves mixin used within an inlined input/output in an operation shape
            Hover operationInlineMixinHover = tds.hover(hoverParams(mainTdi, 143, 36)).get();
            correctHover(mainHoverPrefix, "@mixin\nstructure UserDetails {\n    status: String\n}", operationInlineMixinHover);

            // Resolves mixin used on a structure
            Hover structureMixinHover = tds.hover(hoverParams(mainTdi, 134, 45)).get();
            correctHover(mainHoverPrefix, "@mixin\nstructure UserDetails {\n    status: String\n}", structureMixinHover);

            // Resolves shape with a name that matches operation input/output suffix but is not inlined
            Hover falseOperationInlineHover = tds.hover(hoverParams(mainTdi, 176, 18)).get();
            correctHover(mainHoverPrefix, "structure FalseInlinedFooInput {\n    a: String\n}", falseOperationInlineHover);

            // Resolves a shape including its dependencies in the preamble
            Hover clutteredWithDependenciesHover = tds.hover(hoverParams(clutteredTdi, 26, 17)).get();
            correctHover(clutteredHoverWithDependenciesPrefix, "/// With doc comment\n@mixin\n"
                    + "structure StructureWithDependencies {\n"
                    + "    extra: Extra\n    example: OtherStructure\n}", clutteredWithDependenciesHover);

            // Resolves operation with inlined input/output, but doesn't include cluttered preamble
            Hover clutteredInlineOpHover = tds.hover(hoverParams(clutteredTdi, 31, 17)).get();
            correctHover(clutteredHoverInlineOpPrefix, "operation ClutteredInlineOperation {\n"
                    + "    input: ClutteredInlineOperationIn\n"
                    + "    output: ClutteredInlineOperationOut\n}", clutteredInlineOpHover);
        }
    }

    @Test
    public void completionsV2() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models/v2").toURI());
        String modelFilename = "main.smithy";
        Path modelMain = baseDir.resolve(modelFilename);
        List<Path> modelFiles = ListUtils.of(modelMain);
        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), modelFiles)) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            StubClient client = new StubClient();
            tds.createProject(hs.getConfig(), hs.getRoot());
            tds.setClient(client);
            TextDocumentIdentifier mainTdi = new TextDocumentIdentifier(hs.file(modelFilename).toString());

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
    public void runSelectorV1() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models/v1").toURI());
        String modelFilename = "main.smithy";
        Path modelMain = baseDir.resolve(modelFilename);
        List<Path> modelFiles = ListUtils.of(modelMain);

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), modelFiles)) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            StubClient client = new StubClient();
            tds.createProject(hs.getConfig(), hs.getRoot());
            tds.setClient(client);

            Either<Exception, List<Location>> result = tds.runSelector("[id|namespace=com.foo]");

            assertTrue(result.isRight());
            assertFalse(result.getRight().isEmpty());

            Optional<Location> location = result.getRight().stream()
                    .filter(location1 -> location1.getRange().getStart().getLine() == 20)
                    .findFirst();

            assertTrue(location.isPresent());
            correctLocation(location.get(), modelFilename, 20, 0, 21, 14);
        }
    }

    @Test
    public void runSelectorV2() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models/v2").toURI());
        String modelFilename = "main.smithy";
        Path modelMain = baseDir.resolve(modelFilename);
        List<Path> modelFiles = ListUtils.of(modelMain);

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), modelFiles)) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            StubClient client = new StubClient();
            tds.createProject(hs.getConfig(), hs.getRoot());
            tds.setClient(client);

            Either<Exception, List<Location>> result = tds.runSelector("[id|namespace=com.foo]");

            assertTrue(result.isRight());
            assertFalse(result.getRight().isEmpty());

            Optional<Location> location = result.getRight().stream()
                    .filter(location1 -> location1.getRange().getStart().getLine() == 22)
                    .findFirst();

            assertTrue(location.isPresent());
            correctLocation(location.get(), modelFilename, 22, 0, 23, 14);
        }
    }

    @Test
    public void runSelectorAgainstModelWithErrorsV1() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models/v1").toURI());
        Path broken = baseDir.resolve("broken.smithy");
        List<Path> modelFiles = ListUtils.of(broken);
        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), modelFiles)) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            StubClient client = new StubClient();
            tds.createProject(hs.getConfig(), hs.getRoot());
            tds.setClient(client);

            Either<Exception, List<Location>> result = tds.runSelector("[id|namespace=com.foo]");

            assertTrue(result.isLeft());
            assertTrue(result.getLeft().getMessage().contains("Result contained ERROR severity validation events:"));
        }
    }

    @Test
    public void runSelectorAgainstModelWithErrorsV2() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models/v2").toURI());
        Path broken = baseDir.resolve("broken.smithy");
        List<Path> modelFiles = ListUtils.of(broken);
        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), modelFiles)) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            StubClient client = new StubClient();
            tds.createProject(hs.getConfig(), hs.getRoot());
            tds.setClient(client);

            Either<Exception, List<Location>> result = tds.runSelector("[id|namespace=com.foo]");

            assertTrue(result.isLeft());
            assertTrue(result.getLeft().getMessage().contains("Result contained ERROR severity validation events:"));
        }
    }

    @Test
    public void ensureVersionDiagnostic() throws Exception {
        String fileName1 = "no-version.smithy";
        String fileName2 = "old-version.smithy";
        String fileName3 = "good-version.smithy";

        Map<String, String> files = MapUtils.ofEntries(
                MapUtils.entry(fileName1, "namespace test"),
                MapUtils.entry(fileName2, "$version: \"1\"\nnamespace test2"),
                MapUtils.entry(fileName3, "$version: \"2\"\nnamespace test3")
        );

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), files)) {
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
        String anotherFile = "another.smithy";

        List<Path> files = ListUtils.of(baseDir.resolve(currentFile),baseDir.resolve(anotherFile));

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), files)) {
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.empty(), hs.getTempFolder());
            tds.createProject(hs.getConfig(), hs.getRoot());

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

    private static class StubClient implements LanguageClient {
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

    private DefinitionParams definitionParams(TextDocumentIdentifier tdi, int line, int character) {
        return new DefinitionParams(tdi, new Position(line, character));
    }

    private HoverParams hoverParams(TextDocumentIdentifier tdi, int line, int character) {
        return new HoverParams(tdi, new Position(line, character));
    }

    private void correctHover(String expectedPrefix, String expectedBody, Hover hover) {
        MarkupContent content = hover.getContents().getRight();
        assertEquals("markdown", content.getKind());
        assertEquals(HOVER_DEFAULT_PREFIX + expectedPrefix + expectedBody + HOVER_DEFAULT_SUFFIX, content.getValue());
    }

    private void correctLocation(Location location, String uri, int startLine, int startCol, int endLine, int endCol) {
        assertEquals(startLine, location.getRange().getStart().getLine());
        assertEquals(startCol, location.getRange().getStart().getCharacter());
        assertEquals(endLine, location.getRange().getEnd().getLine());
        assertEquals(endCol, location.getRange().getEnd().getCharacter());
        assertTrue(location.getUri().endsWith(uri));
    }

    private CompletionParams completionParams(TextDocumentIdentifier tdi, int line, int character) {
        return new CompletionParams(tdi, new Position(line, character));
    }

    private Set<String> completionLabels(List<CompletionItem> completionItems) {
        return completionItems.stream().map(item -> item.getLabel()).collect(Collectors.toSet());
    }
}
