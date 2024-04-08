package software.amazon.smithy.lsp;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static software.amazon.smithy.lsp.LspMatchers.hasLabel;
import static software.amazon.smithy.lsp.LspMatchers.hasText;
import static software.amazon.smithy.lsp.LspMatchers.makesEditedDocument;
import static software.amazon.smithy.lsp.SmithyMatchers.hasMessage;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.protocol.RangeAdapter;

public class SmithyLanguageServerTest {
    @Test
    public void runsSelector() throws Exception {
        String model = "$version: \"2\"\n"
                       + "namespace com.foo\n"
                       + "\n"
                       + "string Foo\n";
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        SelectorParams params = new SelectorParams("string");
        List<? extends Location> locations = server.selectorCommand(params).get();

        assertThat(locations, not(empty()));
    }

    @Test
    public void completion() throws Exception {
        String model = "$version: \"2\"\n" +
                       "namespace com.foo\n" +
                       "\n" +
                       "structure Foo {\n" +
                       "    bar: String\n" +
                       "}\n" +
                       "\n" +
                       "@default(0)\n" +
                       "integer Bar\n";
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
        String model1 = "$version: \"2\"\n" +
                        "namespace com.foo\n" +
                        "\n" +
                        "structure Foo {\n" +
                        "}\n";
        String model2 = "$version: \"2\"\n" +
                        "namespace com.bar\n" +
                        "\n" +
                        "string Bar\n";
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
                .range(new RangeAdapter()
                        .startLine(3)
                        .startCharacter(15)
                        .endLine(3)
                        .endCharacter(15)
                        .build())
                .text("\n    bar: Ba")
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

        Document document = server.getProject().getDocument(uri);
        // TODO: The server puts the 'use' on the wrong line
        assertThat(completions.get(0).getAdditionalTextEdits(), containsInAnyOrder(makesEditedDocument(document, "$version: \"2\"\n" +
                                                                                                      "namespace com.foo\n" +
                                                                                                      "use com.bar#Bar\n" +
                                                                                                      "\n" +
                                                                                                      "structure Foo {\n" +
                                                                                                      "    bar: Ba\n" +
                                                                                                      "}\n")));
    }

    @Test
    public void definition() throws Exception {
        String model = "$version: \"2\"\n" +
                       "namespace com.foo\n" +
                       "\n" +
                       "@trait\n" +
                       "string myTrait\n" +
                       "\n" +
                       "structure Foo {\n" +
                       "    bar: Baz\n" +
                       "}\n" +
                       "\n" +
                       "@myTrait(\"\")\n" +
                       "string Baz\n";
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

        Document document = server.getProject().getDocument(uri);
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
        String model = "$version: \"2\"\n" +
                       "namespace com.foo\n" +
                       "\n" +
                       "@trait\n" +
                       "string myTrait\n" +
                       "\n" +
                       "structure Foo {\n" +
                       "    bar: Bar\n" +
                       "}\n" +
                       "\n" +
                       "@myTrait(\"\")\n" +
                       "structure Bar {\n" +
                       "    baz: String\n" +
                       "}\n";
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
        String model = "$version: \"2\"\n" +
                       "namespace com.foo\n" +
                       "\n" +
                       "structure Foo {\n" +
                       "    bar: Bar\n" +
                       "    baz: String\n" +
                       "}\n";
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
        String model = "$version: \"2\"\n" +
                       "namespace com.foo\n" +
                       "\n" +
                       "@trait\n" +
                       "string myTrait\n" +
                       "\n" +
                       "structure Foo {\n" +
                       "    @required\n" +
                       "    bar: Bar\n" +
                       "}\n" +
                       "\n" +
                       "structure Bar {\n" +
                       "    @myTrait(\"foo\")\n" +
                       "    baz: Baz\n" +
                       "}\n" +
                       "\n" +
                       "@myTrait(\"abc\")\n" +
                       "integer Baz\n";
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("main.smithy");

        DocumentSymbolParams params = new DocumentSymbolParams(new TextDocumentIdentifier(uri));
        List<Either<SymbolInformation, DocumentSymbol>> response = server.documentSymbol(params).get();
        List<DocumentSymbol> documentSymbols = response.stream().map(Either::getRight).collect(Collectors.toList());
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
        String model = "$version: \"2\"\n" +
                       "namespace com.foo\n" +
                       "\n" +
                       "structure Foo{\n" +
                       "bar:    Baz}\n" +
                       "\n" +
                       "@tags(\n" +
                       "[\"a\",\n" +
                       "    \"b\"])\n" +
                       "string Baz\n";
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("main.smithy");

        TextDocumentIdentifier id = new TextDocumentIdentifier(uri);
        DocumentFormattingParams params = new DocumentFormattingParams(id, new FormattingOptions());
        List<? extends TextEdit> edits = server.formatting(params).get();
        Document document = server.getProject().getDocument(uri);

        assertThat(edits, (Matcher) containsInAnyOrder(makesEditedDocument(document, "$version: \"2\"\n" +
                                                                "\n" +
                                                                "namespace com.foo\n" +
                                                                "\n" +
                                                                "structure Foo {\n" +
                                                                "    bar: Baz\n" +
                                                                "}\n" +
                                                                "\n" +
                                                                "@tags([\"a\", \"b\"])\n" +
                                                                "string Baz\n")));
    }

    @Test
    public void didChange() throws Exception {
        String model = "$version: \"2\"\n" +
                       "\n" +
                       "namespace com.foo\n" +
                       "\n" +
                       "structure GetFooInput {\n" +
                       "}\n" +
                       "\n" +
                       "operation GetFoo {\n" +
                       "}\n";
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("main.smithy");

        DidOpenTextDocumentParams openParams = new RequestBuilders.DidOpen()
                .uri(uri)
                .text(model)
                .build();
        server.didOpen(openParams);

        RangeAdapter rangeAdapter = new RangeAdapter()
                .startLine(7)
                .startCharacter(18)
                .endLine(7)
                .endCharacter(18);
        RequestBuilders.DidChange changeBuilder = new RequestBuilders.DidChange().uri(uri);

        // Add new line and leading spaces
        server.didChange(changeBuilder.range(rangeAdapter.build()).text("\n    ").build());
        // add 'input: G'
        server.didChange(changeBuilder.range(rangeAdapter.shiftNewLine().shiftRight(4).build()).text("i").build());
        server.didChange(changeBuilder.range(rangeAdapter.shiftRight().build()).text("n").build());
        server.didChange(changeBuilder.range(rangeAdapter.shiftRight().build()).text("p").build());
        server.didChange(changeBuilder.range(rangeAdapter.shiftRight().build()).text("u").build());
        server.didChange(changeBuilder.range(rangeAdapter.shiftRight().build()).text("t").build());
        server.didChange(changeBuilder.range(rangeAdapter.shiftRight().build()).text(":").build());
        server.didChange(changeBuilder.range(rangeAdapter.shiftRight().build()).text(" ").build());
        server.didChange(changeBuilder.range(rangeAdapter.shiftRight().build()).text("G").build());

        server.getLifecycleManager().getTask(uri).get();

        // mostly so you can see what it looks like
        assertThat(server.getProject().getDocument(uri).copyText(), equalTo("$version: \"2\"\n" +
                                                                            "\n" +
                                                                            "namespace com.foo\n" +
                                                                            "\n" +
                                                                            "structure GetFooInput {\n" +
                                                                            "}\n" +
                                                                            "\n" +
                                                                            "operation GetFoo {\n" +
                                                                            "    input: G\n" +
                                                                            "}\n"));

        // input: G
        CompletionParams completionParams = new RequestBuilders.PositionRequest()
                .uri(uri)
                .position(rangeAdapter.shiftRight().build().getStart())
                .buildCompletion();
        List<CompletionItem> completions = server.completion(completionParams).get().getLeft();

        assertThat(completions, containsInAnyOrder(hasLabel("GetFoo"), hasLabel("GetFooInput")));
    }

    @Test
    public void didChangeReloadsModel() throws Exception {
        String model = "$version: \"2\"\n" +
                       "namespace com.foo\n" +
                       "\n" +
                       "operation Foo {}\n";
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri("main.smithy");

        DidOpenTextDocumentParams openParams = new RequestBuilders.DidOpen()
                .uri(uri)
                .text(model)
                .build();
        server.didOpen(openParams);
        assertThat(server.getProject().getModelResult().getValidationEvents(), empty());

        DidChangeTextDocumentParams didChangeParams = new RequestBuilders.DidChange()
                .uri(uri)
                .text("@http(method:\"\", uri: \"\")\n")
                .range(RangeAdapter.point(3, 0))
                .build();
        server.didChange(didChangeParams);

        server.getLifecycleManager().getTask(uri).get();

        assertThat(server.getProject().getModelResult().getValidationEvents(),
                containsInAnyOrder(hasMessage(containsString("Error creating trait"))));

        DidSaveTextDocumentParams didSaveParams = new RequestBuilders.DidSave().uri(uri).build();
        server.didSave(didSaveParams);

        assertThat(server.getProject().getModelResult().getValidationEvents(),
                containsInAnyOrder(hasMessage(containsString("Error creating trait"))));
    }

    @Test
    public void didChangeThenDefinition() throws Exception {
        String model = "$version: \"2\"\n" +
                       "namespace com.foo\n" +
                       "\n" +
                       "structure Foo {\n" +
                       "    bar: Bar\n" +
                       "}\n" +
                       "\n" +
                       "string Bar\n";
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

        RangeAdapter range = new RangeAdapter()
                .startLine(5)
                .startCharacter(1)
                .endLine(5)
                .endCharacter(1);
        RequestBuilders.DidChange change = new RequestBuilders.DidChange().uri(uri);
        server.didChange(change.range(range.build()).text("\n\n").build());
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

        assertThat(server.getProject().getDocument(uri).copyText(), equalTo("$version: \"2\"\n" +
                                                                            "namespace com.foo\n" +
                                                                            "\n" +
                                                                            "structure Foo {\n" +
                                                                            "    bar: Bar\n" +
                                                                            "}\n" +
                                                                            "\n" +
                                                                            "string Baz\n" +
                                                                            "\n" +
                                                                            "string Bar\n"));

        Location afterChanges = server.definition(definitionParams).get().getLeft().get(0);
        assertThat(afterChanges.getUri(), equalTo(uri));
        assertThat(afterChanges.getRange().getStart(), equalTo(new Position(9, 0)));
    }

    @Test
    public void definitionWithApply() throws Exception {
        Path root = Paths.get(getClass().getResource("project/apply").getPath());
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
        String model = "$version: \"2\"\n" +
                       "namespace com.foo\n" +
                       "\n" +
                       "@mixin\n" +
                       "structure Foo {}\n" +
                       "\n";
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);
        String uri = workspace.getUri("main.smithy");

        server.didOpen(new RequestBuilders.DidOpen()
                .uri(uri)
                .text(model)
                .build());

        RangeAdapter range = new RangeAdapter()
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

        assertThat(server.getProject().getDocument(uri).copyText(), equalTo("$version: \"2\"\n" +
                                                                            "namespace com.foo\n" +
                                                                            "\n" +
                                                                            "@mixin\n" +
                                                                            "structure Foo {}\n" +
                                                                            "\n" +
                                                                            "structure Bar with [F]"));

        Position currentPosition = range.build().getStart();
        CompletionParams completionParams = new RequestBuilders.PositionRequest()
                .uri(uri)
                .position(range.shiftRight().build().getStart())
                .buildCompletion();

        assertThat(server.getProject().getDocument(uri).copyToken(currentPosition), equalTo("F"));

        List<CompletionItem> completions = server.completion(completionParams).get().getLeft();

         assertThat(completions, containsInAnyOrder(hasLabel("Foo")));
    }

    @Test
    public void existingShapeMixinCompletion() throws Exception {
        String model = "$version: \"2\"\n" +
                       "namespace com.foo\n" +
                       "\n" +
                       "@mixin\n" +
                       "structure Foo {}\n" +
                       "\n" +
                       "structure Bar {}\n";
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);
        String uri = workspace.getUri("main.smithy");

        server.didOpen(new RequestBuilders.DidOpen()
                .uri(uri)
                .text(model)
                .build());

        RangeAdapter range = new RangeAdapter()
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

        assertThat(server.getProject().getDocument(uri).copyText(), equalTo("$version: \"2\"\n" +
                                                                            "namespace com.foo\n" +
                                                                            "\n" +
                                                                            "@mixin\n" +
                                                                            "structure Foo {}\n" +
                                                                            "\n" +
                                                                            "structure Bar with [F] {}\n"));

        Position currentPosition = range.build().getStart();
        CompletionParams completionParams = new RequestBuilders.PositionRequest()
                .uri(uri)
                .position(range.shiftRight().build().getStart())
                .buildCompletion();

        assertThat(server.getProject().getDocument(uri).copyToken(currentPosition), equalTo("F"));

        List<CompletionItem> completions = server.completion(completionParams).get().getLeft();

        assertThat(completions, containsInAnyOrder(hasLabel("Foo")));
    }

    @Test
    public void diagnosticsOnMemberTarget() {
        String model = "$version: \"2\"\n"
                + "namespace com.foo\n"
                + "\n"
                + "structure Foo {\n"
                + "    bar: Bar\n"
                + "}\n";
        TestWorkspace workspace = TestWorkspace.singleModel(model);
        SmithyLanguageServer server = initFromWorkspace(workspace);
        String uri = workspace.getUri("main.smithy");

        List<Diagnostic> diagnostics = server.getFileDiagnostics(uri);

        assertThat(diagnostics, hasSize(1));
        Diagnostic diagnostic = diagnostics.get(0);
        assertThat(diagnostic.getMessage(), startsWith("Target.UnresolvedShape"));

        Document document = server.getProject().getDocument(uri);
        assertThat(diagnostic.getRange(), hasText(document, equalTo("Bar")));
    }

    @Test
    public void diagnosticOnTrait() {
        String model = "$version: \"2\"\n"
                + "namespace com.foo\n"
                + "\n"
                + "structure Foo {\n"
                + "    @bar\n"
                + "    bar: String\n"
                + "}\n";
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

        Document document = server.getProject().getDocument(uri);
        assertThat(diagnostic.getRange(), hasText(document, equalTo("@bar")));
    }

    @Test
    public void diagnosticsOnShape() throws Exception {
        String model = "$version: \"2\"\n"
                + "namespace com.foo\n"
                + "\n"
                + "list Foo {\n"
                + "    \n"
                + "}\n";
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
        String model = "$version: \"2\"\n"
                + "namespace com.foo\n"
                + "\n"
                + "structure Foo {\n"
                + "    bar: String\n"
                + "}\n";
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

        Hover appliedTraitInPreludeHover = server.hover(RequestBuilders.positionRequest()
                .uri(preludeUri)
                .line(36)
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
                .range(RangeAdapter.origin())
                .text("$")
                .build());

        // Make sure the task is running, then wait for it (what if it's already done?)
        CompletableFuture<Void> future = server.getLifecycleManager().getTask(uri);
        assertThat(future, notNullValue());
        future.get();

        assertThat(server.getProjects().isDetached(uri), is(false));
        assertThat(server.getProjects().getMainProject().getSmithyFile(uri), notNullValue());
        assertThat(server.getProjects().getMainProject().getDocument(uri), notNullValue());
        assertThat(server.getProjects().getMainProject().getDocument(uri).copyText(), equalTo("$"));
    }

    @Test
    public void removingWatchedFile() {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        String filename = "model/main.smithy";
        String modelText = "$version: \"2\"\n"
                           + "namespace com.foo\n"
                           + "string Foo\n";
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

        assertThat(server.getProjects().getProject(uri).getSmithyFile(uri), nullValue());
    }

    @Test
    public void addingDetachedFile() {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        String filename = "main.smithy";
        String modelText = "$version: \"2\"\n"
                           + "namespace com.foo\n"
                           + "string Foo\n";
        workspace.addModel(filename, modelText);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri(filename);

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText)
                .build());

        assertThat(server.getProjects().isDetached(uri), is(true));

        String movedFilename = "model/main.smithy";
        workspace.moveModel(filename, movedFilename);
        String movedUri = workspace.getUri(movedFilename);
        server.didChangeWatchedFiles(RequestBuilders.didChangeWatchedFiles()
                .event(movedUri, FileChangeType.Created)
                .build());

        assertThat(server.getProjects().isDetached(movedUri), is(false));
    }

    @Test
    public void removingAttachedFile() {
        TestWorkspace workspace = TestWorkspace.emptyWithDirSource();
        String filename = "model/main.smithy";
        String modelText = "$version: \"2\"\n"
                           + "namespace com.foo\n"
                           + "string Foo\n";
        workspace.addModel(filename, modelText);
        SmithyLanguageServer server = initFromWorkspace(workspace);

        String uri = workspace.getUri(filename);

        server.didOpen(RequestBuilders.didOpen()
                .uri(uri)
                .text(modelText)
                .build());
        assertThat(server.getProjects().isDetached(uri), is(false));

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

        assertThat(server.getProjects().isDetached(movedUri), is(true));
    }

    public static SmithyLanguageServer initFromWorkspace(TestWorkspace workspace) {
        return initFromWorkspace(workspace, new StubClient());
    }

    public static SmithyLanguageServer initFromWorkspace(TestWorkspace workspace, LanguageClient client) {
        try {
            SmithyLanguageServer server = new SmithyLanguageServer();
            server.connect(client);

            server.initialize(RequestBuilders.initialize()
                    .workspaceFolder(workspace.getRoot().toUri().toString(), "test")
                    .build())
                    .get();

            return server;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
