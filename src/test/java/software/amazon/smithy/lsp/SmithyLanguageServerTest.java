package software.amazon.smithy.lsp;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static software.amazon.smithy.lsp.LspMatchers.diagnosticWithMessage;
import static software.amazon.smithy.lsp.LspMatchers.hasLabel;
import static software.amazon.smithy.lsp.LspMatchers.hasText;
import static software.amazon.smithy.lsp.LspMatchers.makesEditedDocument;
import static software.amazon.smithy.lsp.SmithyMatchers.eventWithMessage;
import static software.amazon.smithy.lsp.SmithyMatchers.hasShapeWithId;
import static software.amazon.smithy.lsp.SmithyMatchers.hasValue;
import static software.amazon.smithy.lsp.UtilMatchers.anOptionalOf;
import static software.amazon.smithy.lsp.document.DocumentTest.safeString;
import static software.amazon.smithy.lsp.project.ProjectTest.toPath;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.build.model.MavenConfig;
import software.amazon.smithy.build.model.SmithyBuildConfig;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.ext.SelectorParams;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.lsp.protocol.RangeBuilder;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.LengthTrait;

public class SmithyLanguageServerTest {
    @Test
    public void runsSelector() throws Exception {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                string Foo
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        SelectorParams params = new SelectorParams("string");
        List<? extends Location> locations = server.selectorCommand(params).get();

        assertThat(locations, not(empty()));
    }

    @Test
    public void completion() throws Exception {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                structure Foo {
                    bar: String
                }

                @default(0)
                integer Bar
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("main.smithy");

        // String
        CompletionParams memberTargetParams = new RequestBuilders.PositionRequest()
                .uri(uri)
                .line(4)
                .character(10)
                .buildCompletion();
        // @default
        CompletionParams traitParams = new RequestBuilders.PositionRequest()
                .uri(uri)
                .line(7)
                .character(2)
                .buildCompletion();
        CompletionParams wsParams = new RequestBuilders.PositionRequest()
                .uri(uri)
                .line(2)
                .character(1)
                .buildCompletion();

        List<CompletionItem> memberTargetCompletions = server.completion(memberTargetParams).get().getLeft();
        List<CompletionItem> traitCompletions = server.completion(traitParams).get().getLeft();
        List<CompletionItem> wsCompletions = server.completion(wsParams).get().getLeft();

        assertThat(memberTargetCompletions, containsInAnyOrder(hasLabel("String")));
        assertThat(traitCompletions, containsInAnyOrder(hasLabel("default")));
        assertThat(wsCompletions, empty());
    }

    @Test
    public void completionImports() throws Exception {
        String model1 = safeString("""
                $version: "2"
                namespace com.foo

                structure Foo {
                }
                """);
        String model2 = safeString("""
                $version: "2"
                namespace com.bar

                string Bar
                """);
        TestWorkspace workspace = TestWorkspace.multipleModels(model1, model2);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("model-0.smithy");

        DidOpenTextDocumentParams openParams = new RequestBuilders.DidOpen()
                .uri(uri)
                .text(model1)
                .build();
        server.didOpen(openParams);

        DidChangeTextDocumentParams changeParams = new RequestBuilders.DidChange()
                .uri(uri)
                .version(2)
                .range(new RangeBuilder()
                        .startLine(3)
                        .startCharacter(15)
                        .endLine(3)
                        .endCharacter(15)
                        .build())
                .text(safeString("\n    bar: Ba"))
                .build();
        server.didChange(changeParams);

        // bar: Ba
        CompletionParams completionParams = new RequestBuilders.PositionRequest()
                .uri(uri)
                .line(4)
                .character(10)
                .buildCompletion();
        List<CompletionItem> completions = server.completion(completionParams).get().getLeft();

        assertThat(completions, containsInAnyOrder(hasLabel("Bar")));

        Document document = server.getFirstProject().getDocument(uri);
        // TODO: The server puts the 'use' on the wrong line
        assertThat(completions.get(0).getAdditionalTextEdits(), containsInAnyOrder(makesEditedDocument(document, safeString("""
                $version: "2"
                namespace com.foo
                use com.bar#Bar

                structure Foo {
                    bar: Ba
                }
                """))));
    }

    @Test
    public void definition() throws Exception {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                @trait
                string myTrait

                structure Foo {
                    bar: Baz
                }

                @myTrait("")
                string Baz
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("main.smithy");

        // bar: Baz
        DefinitionParams memberTargetParams = new RequestBuilders.PositionRequest()
                .uri(uri)
                .line(7)
                .character(9)
                .buildDefinition();
        // @myTrait
        DefinitionParams traitParams = new RequestBuilders.PositionRequest()
                .uri(uri)
                .line(10)
                .character(1)
                .buildDefinition();
        DefinitionParams wsParams = new RequestBuilders.PositionRequest()
                .uri(uri)
                .line(2)
                .character(0)
                .buildDefinition();

        List<? extends Location> memberTargetLocations = server.definition(memberTargetParams).get().getLeft();
        List<? extends Location> traitLocations = server.definition(traitParams).get().getLeft();
        List<? extends Location> wsLocations = server.definition(wsParams).get().getLeft();

        Document document = server.getFirstProject().getDocument(uri);
        assertNotNull(document);

        assertThat(memberTargetLocations, hasSize(1));
        Location memberTargetLocation = memberTargetLocations.get(0);
        assertThat(memberTargetLocation.getUri(), equalTo(uri));
        assertThat(memberTargetLocation.getRange().getStart(), equalTo(new Position(11, 0)));
        // TODO
        // assertThat(document.borrowRange(memberTargetLocation.getRange()), equalTo(""));

        assertThat(traitLocations, hasSize(1));
        Location traitLocation = traitLocations.get(0);
        assertThat(traitLocation.getUri(), equalTo(uri));
        assertThat(traitLocation.getRange().getStart(), equalTo(new Position(4, 0)));

        assertThat(wsLocations, empty());
    }

    @Test
    public void hover() throws Exception {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                @trait
                string myTrait

                structure Foo {
                    bar: Bar
                }

                @myTrait("")
                structure Bar {
                    baz: String
                }
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);
        String uri = workspace.getUri("main.smithy");

        // bar: Bar
        HoverParams memberParams = new RequestBuilders.PositionRequest()
                .uri(uri)
                .line(7)
                .character(9)
                .buildHover();
        // @myTrait("")
        HoverParams traitParams = new RequestBuilders.PositionRequest()
                .uri(uri)
                .line(10)
                .character(1)
                .buildHover();
        HoverParams wsParams = new RequestBuilders.PositionRequest()
                .uri(uri)
                .line(2)
                .character(0)
                .buildHover();

        Hover memberHover = server.hover(memberParams).get();
        Hover traitHover = server.hover(traitParams).get();
        Hover wsHover = server.hover(wsParams).get();

        assertThat(memberHover.getContents().getRight().getValue(), containsString("structure Bar"));
        assertThat(traitHover.getContents().getRight().getValue(), containsString("string myTrait"));
        assertThat(wsHover.getContents().getRight().getValue(), equalTo(""));
    }

    @Test
    public void hoverWithBrokenModel() throws Exception {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                structure Foo {
                    bar: Bar
                    baz: String
                }
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("main.smithy");

        // baz: String
        HoverParams params = new RequestBuilders.PositionRequest()
                        .uri(uri)
                        .line(5)
                        .character(9)
                        .buildHover();
        Hover hover = server.hover(params).get();

        assertThat(hover.getContents().getRight().getValue(), containsString("string String"));
    }

    @Test
    public void documentSymbol() throws Exception {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                @trait
                string myTrait

                structure Foo {
                    @required
                    bar: Bar
                }

                structure Bar {
                    @myTrait("foo")
                    baz: Baz
                }

                @myTrait("abc")
                integer Baz
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("main.smithy");

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .build());

        server.getLifecycleManager().waitForAllTasks();

        DocumentSymbolParams params = new DocumentSymbolParams(new TextDocumentIdentifier(uri));
        List<Either<SymbolInformation, DocumentSymbol>> response = server.documentSymbol(params).get();
        List<DocumentSymbol> documentSymbols = response.stream().map(Either::getRight).toList();
        List<String> names = documentSymbols.stream().map(DocumentSymbol::getName).collect(Collectors.toList());

        assertThat(names, hasItem("myTrait"));
        assertThat(names, hasItem("Foo"));
        assertThat(names, hasItem("bar"));
        assertThat(names, hasItem("Bar"));
        assertThat(names, hasItem("baz"));
        assertThat(names, hasItem("Baz"));
    }

    @Test
    public void formatting() throws Exception {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                structure Foo{
                bar:    Baz}

                @tags(
                ["a",
                    "b"])
                string Baz
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("main.smithy");

        TextDocumentIdentifier id = new TextDocumentIdentifier(uri);
        DocumentFormattingParams params = new DocumentFormattingParams(id, new FormattingOptions());
        List<? extends TextEdit> edits = server.formatting(params).get();
        Document document = server.getFirstProject().getDocument(uri);

        assertThat(edits, containsInAnyOrder(makesEditedDocument(document, safeString("""
                $version: "2"

                namespace com.foo

                structure Foo {
                    bar: Baz
                }

                @tags(["a", "b"])
                string Baz
                """))));
    }

    @Test
    public void didChange() throws Exception {
        String model = safeString("""
                $version: "2"

                namespace com.foo

                structure GetFooInput {
                }

                operation GetFoo {
                }
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("main.smithy");

        DidOpenTextDocumentParams openParams = new RequestBuilders.DidOpen()
                .uri(uri)
                .text(model)
                .build();
        server.didOpen(openParams);

        RangeBuilder rangeBuilder = new RangeBuilder()
                .startLine(7)
                .startCharacter(18)
                .endLine(7)
                .endCharacter(18);
        RequestBuilders.DidChange changeBuilder = new RequestBuilders.DidChange().uri(uri);

        // Add new line and leading spaces
        server.didChange(changeBuilder.range(rangeBuilder.build()).text(safeString("\n    ")).build());
        // add 'input: G'
        server.didChange(changeBuilder.range(rangeBuilder.shiftNewLine().shiftRight(4).build()).text("i").build());
        server.didChange(changeBuilder.range(rangeBuilder.shiftRight().build()).text("n").build());
        server.didChange(changeBuilder.range(rangeBuilder.shiftRight().build()).text("p").build());
        server.didChange(changeBuilder.range(rangeBuilder.shiftRight().build()).text("u").build());
        server.didChange(changeBuilder.range(rangeBuilder.shiftRight().build()).text("t").build());
        server.didChange(changeBuilder.range(rangeBuilder.shiftRight().build()).text(":").build());
        server.didChange(changeBuilder.range(rangeBuilder.shiftRight().build()).text(" ").build());
        server.didChange(changeBuilder.range(rangeBuilder.shiftRight().build()).text("G").build());

        server.getLifecycleManager().waitForAllTasks();

        // mostly so you can see what it looks like
        assertThat(server.getFirstProject().getDocument(uri).copyText(), equalTo(safeString("""
                $version: "2"

                namespace com.foo

                structure GetFooInput {
                }

                operation GetFoo {
                    input: G
                }
                """)));

        // input: G
        CompletionParams completionParams = new RequestBuilders.PositionRequest()
                .uri(uri)
                .position(rangeBuilder.shiftRight().build().getStart())
                .buildCompletion();
        List<CompletionItem> completions = server.completion(completionParams).get().getLeft();

        assertThat(completions, hasItem(hasLabel("GetFooInput")));
    }

    @Test
    public void didChangeReloadsModel() throws Exception {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                operation Foo {}
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("main.smithy");

        DidOpenTextDocumentParams openParams = new RequestBuilders.DidOpen()
                .uri(uri)
                .text(model)
                .build();
        server.didOpen(openParams);
        assertThat(server.getFirstProject().modelResult().getValidationEvents(), empty());

        DidChangeTextDocumentParams didChangeParams = new RequestBuilders.DidChange()
                .uri(uri)
                .text("@http(method:\"\", uri: \"\")\n")
                .range(LspAdapter.point(3, 0))
                .build();
        server.didChange(didChangeParams);

        server.getLifecycleManager().getTask(uri).get();

        assertThat(server.getFirstProject().modelResult().getValidationEvents(),
                containsInAnyOrder(eventWithMessage(containsString("Error creating trait"))));

        DidSaveTextDocumentParams didSaveParams = new RequestBuilders.DidSave().uri(uri).build();
        server.didSave(didSaveParams);

        assertThat(server.getFirstProject().modelResult().getValidationEvents(),
                containsInAnyOrder(eventWithMessage(containsString("Error creating trait"))));
    }

    @Test
    public void didChangeThenDefinition() throws Exception {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                structure Foo {
                    bar: Bar
                }

                string Bar
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);
        String uri = workspace.getUri("main.smithy");

        server.didOpen(new RequestBuilders.DidOpen()
                .uri(uri)
                .text(model)
                .build());
        DefinitionParams definitionParams = new RequestBuilders.PositionRequest()
                .uri(uri)
                .line(4)
                .character(9)
                .buildDefinition();
        Location initialLocation = server.definition(definitionParams).get().getLeft().get(0);
        assertThat(initialLocation.getUri(), equalTo(uri));
        assertThat(initialLocation.getRange().getStart(), equalTo(new Position(7, 0)));

        RangeBuilder range = new RangeBuilder()
                .startLine(5)
                .startCharacter(1)
                .endLine(5)
                .endCharacter(1);
        RequestBuilders.DidChange change = new RequestBuilders.DidChange().uri(uri);
        server.didChange(change.range(range.build()).text(safeString("\n\n")).build());
        server.didChange(change.range(range.shiftNewLine().shiftNewLine().build()).text("s").build());
        server.didChange(change.range(range.shiftRight().build()).text("t").build());
        server.didChange(change.range(range.shiftRight().build()).text("r").build());
        server.didChange(change.range(range.shiftRight().build()).text("i").build());
        server.didChange(change.range(range.shiftRight().build()).text("n").build());
        server.didChange(change.range(range.shiftRight().build()).text("g").build());
        server.didChange(change.range(range.shiftRight().build()).text(" ").build());
        server.didChange(change.range(range.shiftRight().build()).text("B").build());
        server.didChange(change.range(range.shiftRight().build()).text("a").build());
        server.didChange(change.range(range.shiftRight().build()).text("z").build());

        server.getLifecycleManager().getTask(uri).get();

        assertThat(server.getFirstProject().getDocument(uri).copyText(), equalTo(safeString("""
                $version: "2"
                namespace com.foo

                structure Foo {
                    bar: Bar
                }

                string Baz

                string Bar
                """)));

        Location afterChanges = server.definition(definitionParams).get().getLeft().get(0);
        assertThat(afterChanges.getUri(), equalTo(uri));
        assertThat(afterChanges.getRange().getStart(), equalTo(new Position(9, 0)));
    }

    @Test
    public void definitionWithApply() throws Exception {
        Path root = toPath(getClass().getResource("project/apply"));
        SmithyLanguageServer server = initFromRoot(root);
        String foo = root.resolve("model/foo.smithy").toUri().toString();
        String bar = root.resolve("model/bar.smithy").toUri().toString();

        server.didOpen(new RequestBuilders.DidOpen()
                .uri(foo)
                .build());

        // on 'apply >MyOpInput'
        RequestBuilders.PositionRequest myOpInputRequest = new RequestBuilders.PositionRequest()
                .uri(foo)
                .line(5)
                .character(6);

        Location myOpInputLocation = server.definition(myOpInputRequest.buildDefinition()).get().getLeft().get(0);
        assertThat(myOpInputLocation.getUri(), equalTo(foo));
        assertThat(myOpInputLocation.getRange().getStart(), equalTo(new Position(9, 0)));

        Hover myOpInputHover = server.hover(myOpInputRequest.buildHover()).get();
        String myOpInputHoverContent = myOpInputHover.getContents().getRight().getValue();
        assertThat(myOpInputHoverContent, containsString("@tags"));
        assertThat(myOpInputHoverContent, containsString("structure MyOpInput with [HasMyBool]"));
        assertThat(myOpInputHoverContent, containsString("/// even more docs"));
        assertThat(myOpInputHoverContent, containsString("apply MyOpInput$myBool"));

        // on 'with [>HasMyBool]'
        RequestBuilders.PositionRequest hasMyBoolRequest = new RequestBuilders.PositionRequest()
                .uri(foo)
                .line(9)
                .character(26);

        Location hasMyBoolLocation = server.definition(hasMyBoolRequest.buildDefinition()).get().getLeft().get(0);
        assertThat(hasMyBoolLocation.getUri(), equalTo(bar));
        assertThat(hasMyBoolLocation.getRange().getStart(), equalTo(new Position(6, 0)));

        Hover hasMyBoolHover = server.hover(hasMyBoolRequest.buildHover()).get();
        String hasMyBoolHoverContent = hasMyBoolHover.getContents().getRight().getValue();
        assertThat(hasMyBoolHoverContent, containsString("@mixin"));
        assertThat(hasMyBoolHoverContent, containsString("@tags"));
        assertThat(hasMyBoolHoverContent, containsString("structure HasMyBool"));
        assertThat(hasMyBoolHoverContent, not(containsString("///")));
        assertThat(hasMyBoolHoverContent, not(containsString("@documentation")));
    }

    @Test
    public void newShapeMixinCompletion() throws Exception {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                @mixin
                structure Foo {}

                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);
        String uri = workspace.getUri("main.smithy");

        server.didOpen(new RequestBuilders.DidOpen()
                .uri(uri)
                .text(model)
                .build());

        RangeBuilder range = new RangeBuilder()
                .startLine(6)
                .startCharacter(0)
                .endLine(6)
                .endCharacter(0);
        RequestBuilders.DidChange change = new RequestBuilders.DidChange().uri(uri);
        server.didChange(change.range(range.build()).text("s").build());
        server.didChange(change.range(range.shiftRight().build()).text("t").build());
        server.didChange(change.range(range.shiftRight().build()).text("r").build());
        server.didChange(change.range(range.shiftRight().build()).text("u").build());
        server.didChange(change.range(range.shiftRight().build()).text("c").build());
        server.didChange(change.range(range.shiftRight().build()).text("t").build());
        server.didChange(change.range(range.shiftRight().build()).text("u").build());
        server.didChange(change.range(range.shiftRight().build()).text("r").build());
        server.didChange(change.range(range.shiftRight().build()).text("e").build());
        server.didChange(change.range(range.shiftRight().build()).text(" ").build());
        server.didChange(change.range(range.shiftRight().build()).text("B").build());
        server.didChange(change.range(range.shiftRight().build()).text("a").build());
        server.didChange(change.range(range.shiftRight().build()).text("r").build());
        server.didChange(change.range(range.shiftRight().build()).text(" ").build());
        server.didChange(change.range(range.shiftRight().build()).text("w").build());
        server.didChange(change.range(range.shiftRight().build()).text("i").build());
        server.didChange(change.range(range.shiftRight().build()).text("t").build());
        server.didChange(change.range(range.shiftRight().build()).text("h").build());
        server.didChange(change.range(range.shiftRight().build()).text(" ").build());
        server.didChange(change.range(range.shiftRight().build()).text("[]").build());
        server.didChange(change.range(range.shiftRight().build()).text("F").build());

        server.getLifecycleManager().getTask(uri).get();

        assertThat(server.getFirstProject().getDocument(uri).copyText(), equalTo(safeString("""
                $version: "2"
                namespace com.foo

                @mixin
                structure Foo {}

                structure Bar with [F]""")));

        Position currentPosition = range.build().getStart();
        CompletionParams completionParams = new RequestBuilders.PositionRequest()
                .uri(uri)
                .position(range.shiftRight().build().getStart())
                .buildCompletion();

        assertThat(server.getFirstProject().getDocument(uri).copyToken(currentPosition), equalTo("F"));

        List<CompletionItem> completions = server.completion(completionParams).get().getLeft();

         assertThat(completions, containsInAnyOrder(hasLabel("Foo")));
    }

    @Test
    public void existingShapeMixinCompletion() throws Exception {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                @mixin
                structure Foo {}

                structure Bar {}
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);
        String uri = workspace.getUri("main.smithy");

        server.didOpen(new RequestBuilders.DidOpen()
                .uri(uri)
                .text(model)
                .build());

        RangeBuilder range = new RangeBuilder()
                .startLine(6)
                .startCharacter(13)
                .endLine(6)
                .endCharacter(13);
        RequestBuilders.DidChange change = new RequestBuilders.DidChange().uri(uri);
        server.didChange(change.range(range.build()).text(" ").build());
        server.didChange(change.range(range.shiftRight().build()).text("w").build());
        server.didChange(change.range(range.shiftRight().build()).text("i").build());
        server.didChange(change.range(range.shiftRight().build()).text("t").build());
        server.didChange(change.range(range.shiftRight().build()).text("h").build());
        server.didChange(change.range(range.shiftRight().build()).text(" ").build());
        server.didChange(change.range(range.shiftRight().build()).text("[]").build());
        server.didChange(change.range(range.shiftRight().build()).text("F").build());

        server.getLifecycleManager().getTask(uri).get();

        assertThat(server.getFirstProject().getDocument(uri).copyText(), equalTo(safeString("""
                $version: "2"
                namespace com.foo

                @mixin
                structure Foo {}

                structure Bar with [F] {}
                """)));

        Position currentPosition = range.build().getStart();
        CompletionParams completionParams = new RequestBuilders.PositionRequest()
                .uri(uri)
                .position(range.shiftRight().build().getStart())
                .buildCompletion();

        assertThat(server.getFirstProject().getDocument(uri).copyToken(currentPosition), equalTo("F"));

        List<CompletionItem> completions = server.completion(completionParams).get().getLeft();

        assertThat(completions, containsInAnyOrder(hasLabel("Foo")));
    }

    @Test
    public void diagnosticsOnMemberTarget() {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                structure Foo {
                    bar: Bar
                }
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);
        String uri = workspace.getUri("main.smithy");

        List<Diagnostic> diagnostics = server.getFileDiagnostics(uri);

        assertThat(diagnostics, hasSize(1));
        Diagnostic diagnostic = diagnostics.get(0);
        assertThat(diagnostic.getMessage(), startsWith("Target.UnresolvedShape"));

        Document document = server.getFirstProject().getDocument(uri);
        assertThat(diagnostic.getRange(), hasText(document, equalTo("Bar")));
    }

    @Test
    public void diagnosticOnTrait() {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                structure Foo {
                    @bar
                    bar: String
                }
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);
        String uri = workspace.getUri("main.smithy");

        server.didOpen(new RequestBuilders.DidOpen()
                .uri(uri)
                .text(model)
                .build());

        List<Diagnostic> diagnostics = server.getFileDiagnostics(uri);

        assertThat(diagnostics, hasSize(1));
        Diagnostic diagnostic = diagnostics.get(0);
        assertThat(diagnostic.getMessage(), startsWith("Model.UnresolvedTrait"));

        Document document = server.getFirstProject().getDocument(uri);
        assertThat(diagnostic.getRange(), hasText(document, equalTo("@bar")));
    }

    @Test
    public void diagnosticsOnShape() throws Exception {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                list Foo {
                   \s
                }
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        StubClient client = new StubClient();
        SmithyLanguageServer server = new SmithyLanguageServer();
        server.connect(client);

        JsonObject opts = new JsonObject();
        opts.add("diagnostics.minimumSeverity", new JsonPrimitive("NOTE"));
        server.initialize(new RequestBuilders.Initialize()
                .workspaceFolder(workspace.getRoot().toUri().toString(), "test")
                .initializationOptions(opts)
                .build())
                .get();

        String uri = workspace.getUri("main.smithy");

        server.didOpen(new RequestBuilders.DidOpen()
                .uri(uri)
                .text(model)
                .build());

        server.didSave(new RequestBuilders.DidSave()
                .uri(uri)
                .build());

        List<Diagnostic> diagnostics = server.getFileDiagnostics(uri);

        assertThat(diagnostics, hasSize(1));
        Diagnostic diagnostic = diagnostics.get(0);
        assertThat(diagnostic.getMessage(), containsString("Missing required member"));
        // TODO: In this case, the event is attached to the shape, but the shape isn't in the model
        //  because it could not be successfully created. So we can't know the actual position of
        //  the shape, because determining it depends on where its defined in the model.
        // assertThat(diagnostic.getRange().getStart(), equalTo(new Position(3, 5)));
        // assertThat(diagnostic.getRange().getEnd(), equalTo(new Position(3, 8)));
    }

    @Test
    public void insideJar() throws Exception {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                structure Foo {
                    bar: PrimitiveInteger
                }
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("main.smithy");

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(model)
                .build());

        Location preludeLocation = server.definition(RequestBuilders.positionRequest()
                .uri(uri)
                .line(4)
                .character(9)
                .buildDefinition())
                .get()
                .getLeft()
                .get(0);

        String preludeUri = preludeLocation.getUri();
        assertThat(preludeUri, startsWith("smithyjar"));
        Logger.getLogger(getClass().getName()).severe("DOCUMENT LINES: " + server.getFirstProject().getDocument(preludeUri).fullRange());

        Hover appliedTraitInPreludeHover = server.hover(RequestBuilders.positionRequest()
                .uri(preludeUri)
                .line(preludeLocation.getRange().getStart().getLine() - 1) // trait applied above 'PrimitiveInteger'
                .character(1)
                .buildHover())
                .get();
        String content = appliedTraitInPreludeHover.getContents().getRight().getValue();
        assertThat(content, containsString("document default"));
    }

    @Test
    public void addingWatchedFile() throws Exception {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String filename = "model/main.smithy";
        String modelText = "";
        workspace.addModel(filename, modelText);
        String uri = workspace.getUri(filename);

        // The file may be opened before the client notifies the server it's been created
        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText)
                .build());

        server.documentSymbol(new DocumentSymbolParams(new TextDocumentIdentifier(uri)));
        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(uri, FileChangeType.Created)
                .build());
        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .range(LspAdapter.origin())
                .text("$")
                .build());

        // Make sure the task is running, then wait for it
        CompletableFuture<Void> future = server.getLifecycleManager().getTask(uri);
        assertThat(future, notNullValue());
        future.get();

        assertThat(server.getLifecycleManager().isManaged(uri), is(true));
        assertThat(server.getProjects().isDetached(uri), is(false));
        assertThat(server.getFirstProject().getSmithyFile(uri), notNullValue());
        assertThat(server.getProjects().getDocument(uri), notNullValue());
        assertThat(server.getProjects().getDocument(uri).copyText(), equalTo("$"));
    }

    @Test
    public void removingWatchedFile() {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        String filename = "model/main.smithy";
        String modelText = safeString("""
                $version: "2"
                namespace com.foo
                string Foo
                """);
        workspace.addModel(filename, modelText);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri(filename);

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText)
                .build());
        server.didClose(RequestBuilders.didClose()
                .uri(uri)
                .build());
        workspace.deleteModel(filename);
        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(uri, FileChangeType.Deleted)
                .build());

        assertThat(server.getLifecycleManager().isManaged(uri), is(false));
        assertThat(server.getProjects().getDocument(uri), nullValue());
    }

    @Test
    public void addingDetachedFile() {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        String filename = "main.smithy";
        String modelText = safeString("""
                $version: "2"
                namespace com.foo
                string Foo
                """);
        workspace.addModel(filename, modelText);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri(filename);

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText)
                .build());

        assertThat(server.getLifecycleManager().isManaged(uri), is(true));
        assertThat(server.getProjects().isDetached(uri), is(true));
        assertThat(server.getProjects().getProject(uri), notNullValue());

        String movedFilename = "model/main.smithy";
        workspace.moveModel(filename, movedFilename);
        String movedUri = workspace.getUri(movedFilename);
        server.didClose(RequestBuilders.didClose()
                .uri(uri)
                .build());
        server.didOpen(RequestBuilders.didOpen()
                .uri(movedUri)
                .text(modelText)
                .build());
        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(movedUri, FileChangeType.Created)
                .build());

        assertThat(server.getLifecycleManager().isManaged(uri), is(false));
        assertThat(server.getProjects().isDetached(uri), is(false));
        assertThat(server.getProjects().getProject(uri), nullValue());
        assertThat(server.getLifecycleManager().isManaged(movedUri), is(true));
        assertThat(server.getProjects().isDetached(movedUri), is(false));
        assertThat(server.getProjects().getProject(movedUri), notNullValue());
    }

    @Test
    public void removingAttachedFile() {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        String filename = "model/main.smithy";
        String modelText = safeString("""
                $version: "2"
                namespace com.foo
                string Foo
                """);
        workspace.addModel(filename, modelText);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri(filename);

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText)
                .build());

        assertThat(server.getLifecycleManager().isManaged(uri), is(true));
        assertThat(server.getProjects().isDetached(uri), is(false));
        assertThat(server.getProjects().getProject(uri), notNullValue());

        String movedFilename = "main.smithy";
        workspace.moveModel(filename, movedFilename);
        String movedUri = workspace.getUri(movedFilename);

        server.didClose(RequestBuilders.didClose()
                .uri(uri)
                .build());
        server.didOpen(RequestBuilders.didOpen()
                .uri(movedUri)
                .text(modelText)
                .build());
        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(uri, FileChangeType.Deleted)
                .build());

        assertThat(server.getLifecycleManager().isManaged(uri), is(false));
        assertThat(server.getProjects().isDetached(uri), is(false));
        assertThat(server.getProjects().getProject(uri), nullValue());
        assertThat(server.getLifecycleManager().isManaged(movedUri), is(true));
        assertThat(server.getProjects().isDetached(movedUri), is(true));
        assertThat(server.getProjects().getProject(movedUri), notNullValue());
    }

    @Test
    public void loadsProjectWithUnNormalizedSourcesDirs() {
        SmithyBuildConfig config = SmithyBuildConfig.builder()
                .version("1")
                .sources(Collections.singletonList("./././smithy"))
                .build();
        String filename = "smithy/main.smithy";
        String modelText = safeString("""
                $version: "2"
                namespace com.foo

                string Foo
                """);
        TestWorkspace workspace = TestWorkspace.builder()
                .withSourceDir(TestWorkspace.dir()
                        .withPath("./smithy")
                        .withSourceFile("main.smithy", modelText))
                .withConfig(config)
                .build();
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri(filename);

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText)
                .build());

        assertThat(server.getLifecycleManager().isManaged(uri), is(true));
        assertThat(server.getProjects().isDetached(uri), is(false));
        assertThat(server.getProjects().getProject(uri), notNullValue());
    }

    @Test
    public void reloadingProjectWithArrayMetadataValues() throws Exception {
        String modelText1 = safeString("""
                $version: "2"

                metadata foo = [1]
                metadata foo = [2]
                metadata bar = {a: [1]}

                namespace com.foo

                string Foo
                """);
        String modelText2 = safeString("""
                $version: "2"

                metadata foo = [3]

                namespace com.foo

                string Bar
                """);
        TestWorkspace workspace = TestWorkspace.multipleModels(modelText1, modelText2);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        Map<String, Node> metadataBefore = server.getFirstProject().modelResult().unwrap().getMetadata();
        assertThat(metadataBefore, hasKey("foo"));
        assertThat(metadataBefore, hasKey("bar"));
        assertThat(metadataBefore.get("foo"), instanceOf(ArrayNode.class));
        assertThat(metadataBefore.get("foo").expectArrayNode().size(), equalTo(3));

        String uri = workspace.getUri("model-0.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText1)
                .build());
        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .range(LspAdapter.lineSpan(8, 0, 0))
                .text(safeString("\nstring Baz\n"))
                .build());
        server.didSave(RequestBuilders.didSave()
                .uri(uri)
                .build());

        server.getLifecycleManager().getTask(uri).get();

        Map<String, Node> metadataAfter = server.getFirstProject().modelResult().unwrap().getMetadata();
        assertThat(metadataAfter, hasKey("foo"));
        assertThat(metadataAfter, hasKey("bar"));
        assertThat(metadataAfter.get("foo"), instanceOf(ArrayNode.class));
        assertThat(metadataAfter.get("foo").expectArrayNode().size(), equalTo(3));

        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .range(LspAdapter.of(2, 0, 3, 0)) // removing the first 'foo' metadata
                .text("")
                .build());

        server.getLifecycleManager().getTask(uri).get();

        Map<String, Node> metadataAfter2 = server.getFirstProject().modelResult().unwrap().getMetadata();
        assertThat(metadataAfter2, hasKey("foo"));
        assertThat(metadataAfter2, hasKey("bar"));
        assertThat(metadataAfter2.get("foo"), instanceOf(ArrayNode.class));
        assertThat(metadataAfter2.get("foo").expectArrayNode().size(), equalTo(2));
    }

    @Test
    public void changingWatchedFilesWithMetadata() throws Exception {
        String modelText1 = safeString("""
                $version: "2"

                metadata foo = [1]
                metadata foo = [2]
                metadata bar = {a: [1]}

                namespace com.foo

                string Foo
                """);
        String modelText2 = safeString("""
                $version: "2"

                metadata foo = [3]

                namespace com.foo

                string Bar
                """);
        TestWorkspace workspace = TestWorkspace.multipleModels(modelText1, modelText2);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        Map<String, Node> metadataBefore = server.getFirstProject().modelResult().unwrap().getMetadata();
        assertThat(metadataBefore, hasKey("foo"));
        assertThat(metadataBefore, hasKey("bar"));
        assertThat(metadataBefore.get("foo"), instanceOf(ArrayNode.class));
        assertThat(metadataBefore.get("foo").expectArrayNode().size(), equalTo(3));

        String uri = workspace.getUri("model-1.smithy");

        workspace.deleteModel("model-1.smithy");
        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(uri, FileChangeType.Deleted)
                .build());

        server.getLifecycleManager().waitForAllTasks();

        Map<String, Node> metadataAfter = server.getFirstProject().modelResult().unwrap().getMetadata();
        assertThat(metadataAfter, hasKey("foo"));
        assertThat(metadataAfter, hasKey("bar"));
        assertThat(metadataAfter.get("foo"), instanceOf(ArrayNode.class));
        assertThat(metadataAfter.get("foo").expectArrayNode().size(), equalTo(2));
    }

    // TODO: Somehow this is flaky
    @Test
    public void addingOpenedDetachedFile() throws Exception {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        String filename = "main.smithy";
        String modelText = safeString("""
                $version: "2"

                namespace com.foo

                string Foo
                """);
        workspace.addModel(filename, modelText);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("main.smithy");

        assertThat(server.getLifecycleManager().managedDocuments(), not(hasItem(uri)));
        assertThat(server.getProjects().isDetached(uri), is(false));
        assertThat(server.getProjects().getProject(uri), nullValue());

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText)
                .build());

        assertThat(server.getLifecycleManager().managedDocuments(), hasItem(uri));
        assertThat(server.getProjects().isDetached(uri), is(true));
        assertThat(server.getProjects().getProject(uri), notNullValue());

        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .range(LspAdapter.point(3, 0))
                .text(safeString("string Bar\n"))
                .build());

        // Add the already-opened file to the project
        List<String> updatedSources = new ArrayList<>(workspace.getConfig().getSources());
        updatedSources.add("main.smithy");
        workspace.updateConfig(workspace.getConfig()
                .toBuilder()
                .sources(updatedSources)
                .build());

        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(workspace.getUri("smithy-build.json"), FileChangeType.Changed)
                .build());

        server.getLifecycleManager().waitForAllTasks();

        assertThat(server.getLifecycleManager().managedDocuments(), hasItem(uri));
        assertThat(server.getProjects().isDetached(uri), is(false));
        assertThat(server.getFirstProject().getSmithyFile(uri), notNullValue());
        assertThat(server.getFirstProject().modelResult().unwrap(), hasShapeWithId("com.foo#Foo"));
        assertThat(server.getFirstProject().modelResult().unwrap(), hasShapeWithId("com.foo#Bar"));
    }

    @Test
    public void detachingOpenedFile() throws Exception {
        String modelText = safeString("""
                $version: "2"
                namespace com.foo
                string Foo
                """);
        TestWorkspace workspace = TestWorkspace.singleModel(modelText);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("main.smithy");

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText)
                .build());
        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .range(LspAdapter.point(3, 0))
                .text(safeString("string Bar\n"))
                .build());

        workspace.updateConfig(workspace.getConfig()
                .toBuilder()
                .sources(new ArrayList<>())
                .build());

        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(workspace.getUri("smithy-build.json"), FileChangeType.Changed)
                .build());

        server.getLifecycleManager().waitForAllTasks();

        assertThat(server.getLifecycleManager().managedDocuments(), hasItem(uri));
        assertThat(server.getProjects().isDetached(uri), is(true));
        assertThat(server.getProjects().getProject(uri), notNullValue());
        assertThat(server.getProjects().getProject(uri).getSmithyFile(uri), notNullValue());
        assertThat(server.getProjects().getProject(uri).modelResult(), hasValue(hasShapeWithId("com.foo#Foo")));
        assertThat(server.getProjects().getProject(uri).modelResult(), hasValue(hasShapeWithId("com.foo#Bar")));
    }

    @Test
    public void movingDetachedFile() throws Exception {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        String filename = "main.smithy";
        String modelText = safeString("""
                $version: "2"

                namespace com.foo

                string Foo
                """);
        workspace.addModel(filename, modelText);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri(filename);

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText)
                .build());

        // Moving to an also detached file - the server doesn't send DidChangeWatchedFiles
        String movedFilename = "main-2.smithy";
        workspace.moveModel(filename, movedFilename);
        String movedUri = workspace.getUri(movedFilename);

        server.didClose(RequestBuilders.didClose()
                .uri(uri)
                .build());
        server.didOpen(RequestBuilders.didOpen()
                .uri(movedUri)
                .text(modelText)
                .build());

        server.getLifecycleManager().waitForAllTasks();

        assertThat(server.getLifecycleManager().isManaged(uri), is(false));
        assertThat(server.getProjects().getProject(uri), nullValue());
        assertThat(server.getProjects().isDetached(uri), is(false));
        assertThat(server.getLifecycleManager().isManaged(movedUri), is(true));
        assertThat(server.getProjects().getProject(movedUri), notNullValue());
        assertThat(server.getProjects().isDetached(movedUri), is(true));
    }

    @Test
    public void updatesDiagnosticsAfterReload() throws Exception {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();

        String filename1 = "model/main.smithy";
        String modelText1 = safeString("""
                $version: "2"

                namespace com.foo

                // using an unknown trait
                @foo
                string Bar
                """);
        workspace.addModel(filename1, modelText1);

        StubClient client = new StubClient();
        SmithyLanguageServer server = initFromWorkspace(workspace, client);

        String uri1 = workspace.getUri(filename1);

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri1)
                .text(modelText1)
                .build());

        server.getLifecycleManager().waitForAllTasks();

        List<PublishDiagnosticsParams> publishedDiagnostics1 = client.diagnostics;
        assertThat(publishedDiagnostics1, hasSize(1));
        assertThat(publishedDiagnostics1.get(0).getUri(), equalTo(uri1));
        assertThat(publishedDiagnostics1.get(0).getDiagnostics(), containsInAnyOrder(
                diagnosticWithMessage(containsString("Model.UnresolvedTrait"))));

        String filename2 = "model/trait.smithy";
        String modelText2 = safeString("""
                $version: "2"

                namespace com.foo

                // adding the missing trait
                @trait
                structure foo {}
                """);
        workspace.addModel(filename2, modelText2);

        String uri2 = workspace.getUri(filename2);

        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(uri2, FileChangeType.Created)
                .build());

        server.getLifecycleManager().waitForAllTasks();

        List<PublishDiagnosticsParams> publishedDiagnostics2 = client.diagnostics;
        assertThat(publishedDiagnostics2, hasSize(2)); // sent more diagnostics
        assertThat(publishedDiagnostics2.get(1).getUri(), equalTo(uri1)); // sent diagnostics for opened file
        assertThat(publishedDiagnostics2.get(1).getDiagnostics(), empty()); // adding the trait cleared the event
    }

    @Test
    public void invalidSyntaxModelPartiallyLoads() {
        String modelText1 = safeString("""
                $version: "2"
                namespace com.foo
                string Foo
                """);
        String modelText2 = safeString("string Bar\n");
        TestWorkspace workspace = TestWorkspace.multipleModels(modelText1, modelText2);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("model-0.smithy");

        assertThat(server.getFirstProject().getSmithyFile(uri), notNullValue());
        assertThat(server.getFirstProject().modelResult().isBroken(), is(true));
        assertThat(server.getFirstProject().modelResult().getResult().isPresent(), is(true));
        assertThat(server.getFirstProject().modelResult().getResult().get(), hasShapeWithId("com.foo#Foo"));
    }

    @Test
    public void invalidSyntaxDetachedProjectBecomesValid() throws Exception {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String filename = "main.smithy";
        String modelText = safeString("string Foo\n");
        workspace.addModel(filename, modelText);

        String uri = workspace.getUri(filename);
        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText)
                .build());

        server.getLifecycleManager().waitForAllTasks();

        assertThat(server.getProjects().isDetached(uri), is(true));
        assertThat(server.getProjects().getProject(uri), notNullValue());
        assertThat(server.getProjects().getProject(uri).modelResult().isBroken(), is(true));
        assertThat(server.getProjects().getProject(uri).modelResult().getResult().isPresent(), is(true));
        assertThat(server.getProjects().getProject(uri).smithyFiles().keySet(), hasItem(endsWith(filename)));

        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .range(LspAdapter.origin())
                .text(safeString("""
                        $version: "2"
                        namespace com.foo
                        """))
                .build());

        server.getLifecycleManager().waitForAllTasks();

        assertThat(server.getProjects().isDetached(uri), is(true));
        assertThat(server.getProjects().getProject(uri), notNullValue());
        assertThat(server.getProjects().getProject(uri).modelResult().isBroken(), is(false));
        assertThat(server.getProjects().getProject(uri).modelResult().getResult().isPresent(), is(true));
        assertThat(server.getProjects().getProject(uri).smithyFiles().keySet(), hasItem(endsWith(filename)));
        assertThat(server.getProjects().getProject(uri).modelResult().unwrap(), hasShapeWithId("com.foo#Foo"));
    }

    // TODO: apparently flaky
    @Test
    public void addingDetachedFileWithInvalidSyntax() throws Exception {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String filename = "main.smithy";
        workspace.addModel(filename, "");

        String uri = workspace.getUri(filename);

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text("")
                .build());

        server.getLifecycleManager().waitForAllTasks();

        assertThat(server.getProjects().isDetached(uri), is(true));
        assertThat(server.getProjects().getProject(uri), notNullValue());
        assertThat(server.getProjects().getProject(uri).getSmithyFile(uri), notNullValue());

        List<String> updatedSources = new ArrayList<>(workspace.getConfig().getSources());
        updatedSources.add(filename);
        workspace.updateConfig(workspace.getConfig()
                .toBuilder()
                .sources(updatedSources)
                .build());

        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(workspace.getUri("smithy-build.json"), FileChangeType.Changed)
                .build());

        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .text(safeString("$version: \"2\"\n"))
                .range(LspAdapter.origin())
                .build());
        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .text(safeString("namespace com.foo\n"))
                .range(LspAdapter.point(1, 0))
                .build());
        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .text(safeString("string Foo\n"))
                .range(LspAdapter.point(2, 0))
                .build());

        server.getLifecycleManager().waitForAllTasks();

        assertThat(server.getProjects().isDetached(uri), is(false));
        assertThat(server.getProjects().detachedProjects().keySet(), empty());
        assertThat(server.getFirstProject().getSmithyFile(uri), notNullValue());
        assertThat(server.getFirstProject().modelResult(), hasValue(hasShapeWithId("com.foo#Foo")));
    }

    @Test
    public void appliedTraitsAreMaintainedInPartialLoad() throws Exception {
        String modelText1 = safeString("""
                $version: "2"
                namespace com.foo
                string Foo
                """);
        String modelText2 = safeString("""
                $version: "2"
                namespace com.foo
                string Bar
                apply Foo @length(min: 1)
                """);
        TestWorkspace workspace = TestWorkspace.multipleModels(modelText1, modelText2);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri2 = workspace.getUri("model-1.smithy");

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri2)
                .text(modelText2)
                .build());
        server.didChange(RequestBuilders.didChange()
                .uri(uri2)
                .range(LspAdapter.of(3, 23, 3, 24))
                .text("2")
                .build());

        server.getLifecycleManager().waitForAllTasks();

        assertThat(server.getFirstProject().modelResult(), hasValue(hasShapeWithId("com.foo#Bar")));
        Shape foo = server.getFirstProject().modelResult().getResult().get().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.getIntroducedTraits().keySet(), containsInAnyOrder(LengthTrait.ID));
        assertThat(foo.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(2L)));

        String uri1 = workspace.getUri("model-0.smithy");

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri1)
                .text(modelText1)
                .build());
        server.didChange(RequestBuilders.didChange()
                .uri(uri1)
                .range(LspAdapter.point(3, 0))
                .text(safeString("string Another\n"))
                .build());

        server.getLifecycleManager().waitForAllTasks();

        assertThat(server.getFirstProject().modelResult(), hasValue(hasShapeWithId("com.foo#Bar")));
        assertThat(server.getFirstProject().modelResult(), hasValue(hasShapeWithId("com.foo#Another")));
        foo = server.getFirstProject().modelResult().getResult().get().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.getIntroducedTraits().keySet(), containsInAnyOrder(LengthTrait.ID));
        assertThat(foo.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(2L)));
    }

    @Test
    public void brokenBuildFileEventuallyConsistent() throws Exception {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        SmithyLanguageServer server = initFromWorkspace(workspace);

        workspace.addModel("model/main.smithy", "");
        String uri = workspace.getUri("model/main.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text("")
                .build());
        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(uri, FileChangeType.Created)
                .build());

        String invalidDependency = "software.amazon.smithy:smithy-smoke-test-traits:[1.0, 2.0[";
        workspace.updateConfig(workspace.getConfig().toBuilder()
                .maven(MavenConfig.builder()
                        .dependencies(Collections.singletonList(invalidDependency))
                        .build())
                .build());
        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(workspace.getUri("smithy-build.json"), FileChangeType.Changed)
                .build());

        String fixed = "software.amazon.smithy:smithy-smoke-test-traits:1.49.0";
        workspace.updateConfig(workspace.getConfig().toBuilder()
                .maven(MavenConfig.builder()
                        .dependencies(Collections.singletonList(fixed))
                        .build())
                .build());
        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(workspace.getUri("smithy-build.json"), FileChangeType.Changed)
                .build());

        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .text(safeString("""
                        $version: "2"
                        namespace com.foo
                        string Foo
                        """))
                .range(LspAdapter.origin())
                .build());
        server.getLifecycleManager().waitForAllTasks();

        assertThat(server.getProjects().getProject(uri), notNullValue());
        assertThat(server.getProjects().getDocument(uri), notNullValue());
        assertThat(server.getProjects().getProject(uri).getSmithyFile(uri), notNullValue());
        assertThat(server.getProjects().getProject(uri).modelResult(), hasValue(hasShapeWithId("com.foo#Foo")));
    }

    @Test
    public void completionHoverDefinitionWithAbsoluteIds() throws Exception {
        String modelText1 = safeString("""
                $version: "2"
                namespace com.foo
                use com.bar#Bar
                @com.bar#baz
                structure Foo {
                    bar: com.bar#Bar
                }
                """);
        String modelText2 = safeString("""
                $version: "2"
                namespace com.bar
                string Bar
                string Bar2
                @trait
                structure baz {}
                """);
        TestWorkspace workspace = TestWorkspace.multipleModels(modelText1, modelText2);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("model-0.smithy");

        // use com.b
        RequestBuilders.PositionRequest useTarget = RequestBuilders.positionRequest()
                .uri(uri)
                .line(2)
                .character(8);
        // @com.b
        RequestBuilders.PositionRequest trait = RequestBuilders.positionRequest()
                .uri(uri)
                .line(3)
                .character(2);
        // bar: com.ba
        RequestBuilders.PositionRequest memberTarget = RequestBuilders.positionRequest()
                .uri(uri)
                .line(5)
                .character(14);

        List<CompletionItem> useTargetCompletions = server.completion(useTarget.buildCompletion()).get().getLeft();
        List<CompletionItem> traitCompletions = server.completion(trait.buildCompletion()).get().getLeft();
        List<CompletionItem> memberTargetCompletions = server.completion(memberTarget.buildCompletion()).get().getLeft();

        assertThat(useTargetCompletions, containsInAnyOrder(hasLabel("com.bar#Bar2"))); // won't match 'Bar' because its already imported
        assertThat(traitCompletions, containsInAnyOrder(hasLabel("com.bar#baz")));
        assertThat(memberTargetCompletions, containsInAnyOrder(hasLabel("com.bar#Bar"), hasLabel("com.bar#Bar2")));

        List<? extends Location> useTargetLocations = server.definition(useTarget.buildDefinition()).get().getLeft();
        List<? extends Location> traitLocations = server.definition(trait.buildDefinition()).get().getLeft();
        List<? extends Location> memberTargetLocations = server.definition(memberTarget.buildDefinition()).get().getLeft();

        String uri1 = workspace.getUri("model-1.smithy");

        assertThat(useTargetLocations, hasSize(1));
        assertThat(useTargetLocations.get(0).getUri(), equalTo(uri1));
        assertThat(useTargetLocations.get(0).getRange().getStart(), equalTo(new Position(2, 0)));

        assertThat(traitLocations, hasSize(1));
        assertThat(traitLocations.get(0).getUri(), equalTo(uri1));
        assertThat(traitLocations.get(0).getRange().getStart(), equalTo(new Position(5, 0)));

        assertThat(memberTargetLocations, hasSize(1));
        assertThat(memberTargetLocations.get(0).getUri(), equalTo(uri1));
        assertThat(memberTargetLocations.get(0).getRange().getStart(), equalTo(new Position(2, 0)));

        Hover useTargetHover = server.hover(useTarget.buildHover()).get();
        Hover traitHover = server.hover(trait.buildHover()).get();
        Hover memberTargetHover = server.hover(memberTarget.buildHover()).get();

        assertThat(useTargetHover.getContents().getRight().getValue(), containsString("string Bar"));
        assertThat(traitHover.getContents().getRight().getValue(), containsString("structure baz {}"));
        assertThat(memberTargetHover.getContents().getRight().getValue(), containsString("string Bar"));
    }

    @Test
    public void useCompletionDoesntAutoImport() throws Exception {
        String modelText1 = safeString("""
                $version: "2"
                namespace com.foo
                """);
        String modelText2 = safeString("""
                $version: "2"
                namespace com.bar
                string Bar
                """);
        TestWorkspace workspace = TestWorkspace.multipleModels(modelText1, modelText2);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("model-0.smithy");
        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText1)
                .build());
        server.didChange(RequestBuilders.didChange()
                .uri(uri)
                .range(LspAdapter.point(2, 0))
                .text("use co")
                .build());

        List<CompletionItem> completions = server.completion(RequestBuilders.positionRequest()
                .uri(uri)
                .line(2)
                .character(5)
                .buildCompletion())
                .get()
                .getLeft();

        assertThat(completions, containsInAnyOrder(hasLabel("com.bar#Bar")));
        assertThat(completions.get(0).getAdditionalTextEdits(), nullValue());
    }

    @Test
    public void loadsMultipleRoots() {
        TestWorkspace workspaceFoo = TestWorkspace.builder()
                .withName("foo")
                .withPath("foo")
                .withSourceFile("foo.smithy", """
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """)
                .build();

        TestWorkspace workspaceBar = TestWorkspace.builder()
                .withName("bar")
                .withPath("bar")
                .withSourceFile("bar.smithy", """
                        $version: "2"
                        namespace com.bar
                        structure Bar {}
                        """)
                .build();

        SmithyLanguageServer server = initFromWorkspaces(workspaceFoo, workspaceBar);

        assertThat(server.getProjects().attachedProjects(), hasKey("foo"));
        assertThat(server.getProjects().attachedProjects(), hasKey("bar"));

        assertThat(server.getProjects().getDocument(workspaceFoo.getUri("foo.smithy")), notNullValue());
        assertThat(server.getProjects().getDocument(workspaceBar.getUri("bar.smithy")), notNullValue());

        Project projectFoo = server.getProjects().getProjectByName("foo");
        Project projectBar = server.getProjects().getProjectByName("bar");

        assertThat(projectFoo.smithyFiles(), hasKey(endsWith("foo.smithy")));
        assertThat(projectBar.smithyFiles(), hasKey(endsWith("bar.smithy")));

        assertThat(projectFoo.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.foo#Foo")));
        assertThat(projectBar.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.bar#Bar")));
    }

    @Test
    public void multiRootLifecycleManagement() throws Exception {
        TestWorkspace workspaceFoo = TestWorkspace.builder()
                .withName("foo")
                .withPath("foo")
                .withSourceFile("foo.smithy", """
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """)
                .build();

        TestWorkspace workspaceBar = TestWorkspace.builder()
                .withName("bar")
                .withPath("bar")
                .withSourceFile("bar.smithy", """
                        $version: "2"
                        namespace com.bar
                        structure Bar {}
                        """)
                .build();

        SmithyLanguageServer server = initFromWorkspaces(workspaceFoo, workspaceBar);

        String fooUri = workspaceFoo.getUri("foo.smithy");
        String barUri = workspaceBar.getUri("bar.smithy");

        server.didOpen(RequestBuilders.didOpen()
                .uri(fooUri)
                .build());
        server.didOpen(RequestBuilders.didOpen()
                .uri(barUri)
                .build());

        server.didChange(RequestBuilders.didChange()
                .uri(fooUri)
                .text("\nstructure Bar {}")
                .range(LspAdapter.point(server.getProjects().getDocument(fooUri).end()))
                .build());
        server.didChange(RequestBuilders.didChange()
                .uri(barUri)
                .text("\nstructure Foo {}")
                .range(LspAdapter.point(server.getProjects().getDocument(barUri).end()))
                .build());

        server.didSave(RequestBuilders.didSave()
                .uri(fooUri)
                .build());
        server.didSave(RequestBuilders.didSave()
                .uri(barUri)
                .build());

        server.getLifecycleManager().waitForAllTasks();

        Project projectFoo = server.getProjects().getProjectByName("foo");
        Project projectBar = server.getProjects().getProjectByName("bar");

        assertThat(projectFoo.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.foo#Foo")));
        assertThat(projectFoo.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.foo#Bar")));

        assertThat(projectBar.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.bar#Bar")));
        assertThat(projectBar.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.bar#Foo")));
    }

    @Test
    public void multiRootAddingWatchedFile() throws Exception {
        TestWorkspace workspaceFoo = TestWorkspace.builder()
                .withName("foo")
                .withPath("foo")
                .withSourceDir(new TestWorkspace.Dir()
                        .withPath("model")
                        .withSourceFile("main.smithy", ""))
                .build();
        TestWorkspace workspaceBar = TestWorkspace.builder()
                .withName("bar")
                .withPath("bar")
                .withSourceDir(new TestWorkspace.Dir()
                        .withPath("model")
                        .withSourceFile("main.smithy", ""))
                .build();

        SmithyLanguageServer server = initFromWorkspaces(workspaceFoo, workspaceBar);

        String fooUri = workspaceFoo.getUri("model/main.smithy");
        String barUri = workspaceBar.getUri("model/main.smithy");

        String newFilename = "model/other.smithy";
        String newText = """
                $version: "2"
                namespace com.bar
                structure Bar {}
                """;
        workspaceBar.addModel(newFilename, newText);

        String newUri = workspaceBar.getUri(newFilename);

        server.didOpen(RequestBuilders.didOpen()
                .uri(fooUri)
                .build());
        server.didOpen(RequestBuilders.didOpen()
                .uri(barUri)
                .build());

        server.didChange(RequestBuilders.didChange()
                .uri(fooUri)
                .text("""
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """)
                .range(LspAdapter.origin())
                .build());

        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(newUri, FileChangeType.Created)
                .build());

        server.didChange(RequestBuilders.didChange()
                .uri(fooUri)
                .text("""
                        
                        structure Bar {}""")
                .range(LspAdapter.point(3, 0))
                .build());

        server.getLifecycleManager().waitForAllTasks();

        Project projectFoo = server.getProjects().getProjectByName("foo");
        Project projectBar = server.getProjects().getProjectByName("bar");

        assertThat(projectFoo.smithyFiles(), hasKey(endsWith("main.smithy")));
        assertThat(projectBar.smithyFiles(), hasKey(endsWith("main.smithy")));
        assertThat(projectBar.smithyFiles(), hasKey(endsWith("other.smithy")));

        assertThat(projectFoo.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.foo#Foo")));
        assertThat(projectFoo.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.foo#Bar")));
        assertThat(projectBar.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.bar#Bar")));
    }

    @Test
    public void multiRootChangingBuildFile() throws Exception {
        TestWorkspace workspaceFoo = TestWorkspace.builder()
                .withName("foo")
                .withPath("foo")
                .withSourceDir(new TestWorkspace.Dir()
                        .withPath("model")
                        .withSourceFile("main.smithy", ""))
                .build();
        TestWorkspace workspaceBar = TestWorkspace.builder()
                .withName("bar")
                .withPath("bar")
                .withSourceDir(new TestWorkspace.Dir()
                        .withPath("model")
                        .withSourceFile("main.smithy", ""))
                .build();

        SmithyLanguageServer server = initFromWorkspaces(workspaceFoo, workspaceBar);

        String newFilename = "other.smithy";
        String newText = """
                $version: "2"
                namespace com.other
                structure Other {}
                """;
        workspaceBar.addModel(newFilename, newText);
        String newUri = workspaceBar.getUri(newFilename);

        server.didOpen(RequestBuilders.didOpen()
                .uri(newUri)
                .text(newText)
                .build());

        List<String> updatedSources = new ArrayList<>(workspaceBar.getConfig().getSources());
        updatedSources.add(newFilename);
        workspaceBar.updateConfig(workspaceBar.getConfig().toBuilder()
                .sources(updatedSources)
                .build());

        server.didOpen(RequestBuilders.didOpen()
                .uri(workspaceFoo.getUri("model/main.smithy"))
                .build());
        server.didChange(RequestBuilders.didChange()
                .uri(workspaceFoo.getUri("model/main.smithy"))
                .text("""
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """)
                .range(LspAdapter.origin())
                .build());

        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(workspaceBar.getUri("smithy-build.json"), FileChangeType.Changed)
                .build());

        server.didChange(RequestBuilders.didChange()
                .uri(workspaceBar.getUri("model/main.smithy"))
                .text("""
                        $version: "2"
                        namespace com.bar
                        structure Bar {
                            other: com.other#Other
                        }
                        """)
                .range(LspAdapter.origin())
                .build());

        server.getLifecycleManager().waitForAllTasks();

        assertThat(server.getProjects().detachedProjects(), anEmptyMap());
        assertThat(server.getProjects().getProject(newUri), notNullValue());
        assertThat(server.getProjects().getProject(workspaceBar.getUri("model/main.smithy")), notNullValue());
        assertThat(server.getProjects().getProject(workspaceFoo.getUri("model/main.smithy")), notNullValue());

        Project projectFoo = server.getProjects().getProjectByName("foo");
        Project projectBar = server.getProjects().getProjectByName("bar");

        assertThat(projectFoo.smithyFiles(), hasKey(endsWith("main.smithy")));
        assertThat(projectBar.smithyFiles(), hasKey(endsWith("main.smithy")));
        assertThat(projectBar.smithyFiles(), hasKey(endsWith("other.smithy")));

        assertThat(projectFoo.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.foo#Foo")));
        assertThat(projectBar.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.bar#Bar")));
        assertThat(projectBar.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.bar#Bar$other")));
        assertThat(projectBar.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.other#Other")));
    }

    @Test
    public void addingWorkspaceFolder() throws Exception {
        String fooModel = """
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """;
        TestWorkspace workspaceFoo = TestWorkspace.builder()
                .withName("foo")
                .withPath("foo")
                .withSourceFile("foo.smithy", fooModel)
                .build();

        SmithyLanguageServer server = initFromWorkspace(workspaceFoo);

        String barModel = """
                        $version: "2"
                        namespace com.bar
                        structure Bar {}
                        """;
        TestWorkspace workspaceBar = TestWorkspace.builder()
                .withName("bar")
                .withPath("bar")
                .withSourceFile("bar.smithy", barModel)
                .build();

        server.didOpen(RequestBuilders.didOpen()
                .uri(workspaceFoo.getUri("foo.smithy"))
                .text(fooModel)
                .build());

        server.didChangeWorkspaceFolders(RequestBuilders.didChangeWorkspaceFolders()
                .added(workspaceBar.getRoot().toUri().toString(), "bar")
                .build());

        server.didOpen(RequestBuilders.didOpen()
                .uri(workspaceBar.getUri("bar.smithy"))
                .text(barModel)
                .build());

        server.getLifecycleManager().waitForAllTasks();

        assertThat(server.getProjects().attachedProjects(), hasKey("foo"));
        assertThat(server.getProjects().attachedProjects(), hasKey("bar"));

        assertThat(server.getProjects().getDocument(workspaceFoo.getUri("foo.smithy")), notNullValue());
        assertThat(server.getProjects().getDocument(workspaceBar.getUri("bar.smithy")), notNullValue());

        Project projectFoo = server.getProjects().getProjectByName("foo");
        Project projectBar = server.getProjects().getProjectByName("bar");

        assertThat(projectFoo.smithyFiles(), hasKey(endsWith("foo.smithy")));
        assertThat(projectBar.smithyFiles(), hasKey(endsWith("bar.smithy")));

        assertThat(projectFoo.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.foo#Foo")));
        assertThat(projectBar.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.bar#Bar")));
    }

    @Test
    public void removingWorkspaceFolder() {
        String fooModel = """
                        $version: "2"
                        namespace com.foo
                        structure Foo {}
                        """;
        TestWorkspace workspaceFoo = TestWorkspace.builder()
                .withName("foo")
                .withPath("foo")
                .withSourceFile("foo.smithy", fooModel)
                .build();

        String barModel = """
                        $version: "2"
                        namespace com.bar
                        structure Bar {}
                        """;
        TestWorkspace workspaceBar = TestWorkspace.builder()
                .withName("bar")
                .withPath("bar")
                .withSourceFile("bar.smithy", barModel)
                .build();

        SmithyLanguageServer server = initFromWorkspaces(workspaceFoo, workspaceBar);

        server.didOpen(RequestBuilders.didOpen()
                .uri(workspaceFoo.getUri("foo.smithy"))
                .text(fooModel)
                .build());

        server.didOpen(RequestBuilders.didOpen()
                .uri(workspaceBar.getUri("bar.smithy"))
                .text(barModel)
                .build());

        server.didChangeWorkspaceFolders(RequestBuilders.didChangeWorkspaceFolders()
                .removed(workspaceBar.getRoot().toUri().toString(), "bar")
                .build());

        assertThat(server.getProjects().attachedProjects(), hasKey("foo"));
        assertThat(server.getProjects().attachedProjects(), not(hasKey("bar")));
        assertThat(server.getProjects().detachedProjects(), hasKey(endsWith("bar.smithy")));
        assertThat(server.getProjects().isDetached(workspaceBar.getUri("bar.smithy")), is(true));

        assertThat(server.getProjects().getDocument(workspaceFoo.getUri("foo.smithy")), notNullValue());
        assertThat(server.getProjects().getDocument(workspaceBar.getUri("bar.smithy")), notNullValue());

        Project projectFoo = server.getProjects().getProjectByName("foo");
        Project projectBar = server.getProjects().getProject(workspaceBar.getUri("bar.smithy"));

        assertThat(projectFoo.smithyFiles(), hasKey(endsWith("foo.smithy")));
        assertThat(projectBar.smithyFiles(), hasKey(endsWith("bar.smithy")));

        assertThat(projectFoo.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.foo#Foo")));
        assertThat(projectBar.modelResult(), SmithyMatchers.hasValue(SmithyMatchers.hasShapeWithId("com.bar#Bar")));
    }
    public static SmithyLanguageServer initFromWorkspace(TestWorkspace workspace) {
        return initFromWorkspace(workspace, new StubClient());
    }

    public static SmithyLanguageServer initFromWorkspace(TestWorkspace workspace, LanguageClient client) {
        try {
            SmithyLanguageServer server = new SmithyLanguageServer();
            server.connect(client);

            server.initialize(RequestBuilders.initialize()
                    .workspaceFolder(workspace.getRoot().toUri().toString(), workspace.getName())
                    .build())
                    .get();

            return server;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static SmithyLanguageServer initFromWorkspaces(TestWorkspace... workspaces) {
        LanguageClient client = new StubClient();
        SmithyLanguageServer server = new SmithyLanguageServer();
        server.connect(client);

        RequestBuilders.Initialize initialize = RequestBuilders.initialize();
        for (TestWorkspace workspace : workspaces) {
            initialize.workspaceFolder(workspace.getRoot().toUri().toString(), workspace.getName());
        }

        try {
            server.initialize(initialize.build()).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return server;
    }

    public static SmithyLanguageServer initFromRoot(Path root) {
        try {
            LanguageClient client = new StubClient();
            SmithyLanguageServer server = new SmithyLanguageServer();
            server.connect(client);

            server.initialize(new RequestBuilders.Initialize()
                    .workspaceFolder(root.toUri().toString(), "test")
                    .build())
                    .get();

            return server;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
