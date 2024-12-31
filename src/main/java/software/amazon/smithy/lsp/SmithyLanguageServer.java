/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

import static java.util.concurrent.CompletableFuture.completedFuture;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFilter;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SetTraceParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentChangeRegistrationOptions;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentRegistrationOptions;
import org.eclipse.lsp4j.TextDocumentSaveRegistrationOptions;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.Unregistration;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCancelParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceFoldersOptions;
import org.eclipse.lsp4j.WorkspaceServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import software.amazon.smithy.lsp.codeactions.SmithyCodeActions;
import software.amazon.smithy.lsp.diagnostics.SmithyDiagnostics;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.ext.OpenProject;
import software.amazon.smithy.lsp.ext.SelectorParams;
import software.amazon.smithy.lsp.ext.ServerStatus;
import software.amazon.smithy.lsp.ext.SmithyProtocolExtensions;
import software.amazon.smithy.lsp.language.CompletionHandler;
import software.amazon.smithy.lsp.language.DefinitionHandler;
import software.amazon.smithy.lsp.language.DocumentSymbolHandler;
import software.amazon.smithy.lsp.language.HoverHandler;
import software.amazon.smithy.lsp.project.BuildFile;
import software.amazon.smithy.lsp.project.IdlFile;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectAndFile;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.lsp.syntax.Syntax;
import software.amazon.smithy.model.loader.IdlTokenizer;
import software.amazon.smithy.model.selector.Selector;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.syntax.Formatter;
import software.amazon.smithy.syntax.TokenTree;
import software.amazon.smithy.utils.IoUtils;

public class SmithyLanguageServer implements
        LanguageServer, LanguageClientAware, SmithyProtocolExtensions, WorkspaceService, TextDocumentService {
    private static final Logger LOGGER = Logger.getLogger(SmithyLanguageServer.class.getName());
    private static final ServerCapabilities CAPABILITIES;

    static {
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setCodeActionProvider(new CodeActionOptions(SmithyCodeActions.all()));
        capabilities.setDefinitionProvider(true);
        capabilities.setDeclarationProvider(true);
        capabilities.setCompletionProvider(new CompletionOptions(true, null));
        capabilities.setHoverProvider(true);
        capabilities.setDocumentFormattingProvider(true);
        capabilities.setDocumentSymbolProvider(true);

        WorkspaceFoldersOptions workspaceFoldersOptions = new WorkspaceFoldersOptions();
        workspaceFoldersOptions.setSupported(true);
        capabilities.setWorkspace(new WorkspaceServerCapabilities(workspaceFoldersOptions));

        CAPABILITIES = capabilities;
    }

    private SmithyLanguageClient client;
    private final ServerState state = new ServerState();
    private ClientCapabilities clientCapabilities;
    private ServerOptions serverOptions;

    SmithyLanguageServer() {
    }

    ServerState getState() {
        return state;
    }

    Severity getMinimumSeverity() {
        return this.serverOptions.getMinimumSeverity();
    }

    @Override
    public void connect(LanguageClient client) {
        LOGGER.finest("Connect");
        this.client = new SmithyLanguageClient(client);
        String message = "smithy-language-server";
        try {
            Properties props = new Properties();
            props.load(Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("version.properties")));
            message += " version " + props.getProperty("version");
        } catch (IOException e) {
            this.client.error("Failed to load smithy-language-server version: " + e);
        }
        this.client.info(message + " started.");
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        LOGGER.finest("Initialize");

        Optional.ofNullable(params.getProcessId())
                .flatMap(ProcessHandle::of)
                .ifPresent(processHandle -> processHandle.onExit().thenRun(this::exit));

        this.serverOptions = ServerOptions.fromInitializeParams(params, client);
        // TODO: Replace with a Gson Type Adapter if more config options are added beyond `logToFile`.

        if (params.getWorkspaceFolders() != null && !params.getWorkspaceFolders().isEmpty()) {
            Either<String, Integer> workDoneProgressToken = params.getWorkDoneToken();
            if (workDoneProgressToken != null) {
                WorkDoneProgressBegin notification = new WorkDoneProgressBegin();
                notification.setTitle("Initializing");
                client.notifyProgress(new ProgressParams(workDoneProgressToken, Either.forLeft(notification)));
            }

            for (WorkspaceFolder workspaceFolder : params.getWorkspaceFolders()) {
                state.loadWorkspace(workspaceFolder);
            }

            if (workDoneProgressToken != null) {
                WorkDoneProgressEnd notification = new WorkDoneProgressEnd();
                client.notifyProgress(new ProgressParams(workDoneProgressToken, Either.forLeft(notification)));
            }
        }

        this.clientCapabilities = params.getCapabilities();

        // We register for this capability dynamically otherwise
        if (!isDynamicSyncRegistrationSupported()) {
            CAPABILITIES.setTextDocumentSync(TextDocumentSyncKind.Incremental);
        }

        LOGGER.finest("Done initialize");
        return completedFuture(new InitializeResult(CAPABILITIES));
    }

    private void reportProjectLoadErrors(List<Exception> errors) {
        if (!errors.isEmpty()) {
            StringBuilder errorMessage = new StringBuilder("Failed to load Smithy projects").append(":");
            for (Exception error : errors) {
                errorMessage.append(System.lineSeparator());
                errorMessage.append('\t');
                errorMessage.append(error.getMessage());
            }
            client.error(errorMessage.toString());
        }
    }

    private CompletableFuture<Void> registerSmithyFileWatchers() {
        List<Registration> registrations = FileWatcherRegistrations.getSmithyFileWatcherRegistrations(
                state.getAllProjects());
        return client.registerCapability(new RegistrationParams(registrations));
    }

    private CompletableFuture<Void> unregisterSmithyFileWatchers() {
        List<Unregistration> unregistrations = FileWatcherRegistrations.getSmithyFileWatcherUnregistrations();
        return client.unregisterCapability(new UnregistrationParams(unregistrations));
    }

    private CompletableFuture<Void> registerWorkspaceBuildFileWatchers() {
        var registrations = FileWatcherRegistrations.getBuildFileWatcherRegistrations(state.workspacePaths());
        return client.registerCapability(new RegistrationParams(registrations));
    }

    private CompletableFuture<Void> unregisterWorkspaceBuildFileWatchers() {
        var unregistrations = FileWatcherRegistrations.getBuildFileWatcherUnregistrations();
        return client.unregisterCapability(new UnregistrationParams(unregistrations));
    }

    @Override
    public void initialized(InitializedParams params) {
        // We have to do this in `initialized` because we can't send dynamic registrations in `initialize`.
        if (isDynamicSyncRegistrationSupported()) {
            registerDocumentSynchronization();
        }

        registerWorkspaceBuildFileWatchers();
        registerSmithyFileWatchers();
    }

    private boolean isDynamicSyncRegistrationSupported() {
        return clientCapabilities != null
               && clientCapabilities.getTextDocument() != null
               && clientCapabilities.getTextDocument().getSynchronization() != null
               && clientCapabilities.getTextDocument().getSynchronization().getDynamicRegistration();
    }

    private void registerDocumentSynchronization() {
        List<DocumentFilter> buildDocumentSelector = List.of(
                new DocumentFilter("json", "file", "**/{smithy-build,.smithy-project}.json"));

        var openCloseBuildOpts = new TextDocumentRegistrationOptions(buildDocumentSelector);
        var changeBuildOpts = new TextDocumentChangeRegistrationOptions(TextDocumentSyncKind.Incremental);
        changeBuildOpts.setDocumentSelector(buildDocumentSelector);
        var saveBuildOpts = new TextDocumentSaveRegistrationOptions();
        saveBuildOpts.setDocumentSelector(buildDocumentSelector);
        saveBuildOpts.setIncludeText(true);

        client.registerCapability(new RegistrationParams(List.of(
                new Registration("SyncSmithyBuildFiles/Open", "textDocument/didOpen", openCloseBuildOpts),
                new Registration("SyncSmithyBuildFiles/Close", "textDocument/didClose", openCloseBuildOpts),
                new Registration("SyncSmithyBuildFiles/Change", "textDocument/didChange", changeBuildOpts),
                new Registration("SyncSmithyBuildFiles/Save", "textDocument/didSave", saveBuildOpts))));

        DocumentFilter smithyFilter = new DocumentFilter();
        smithyFilter.setLanguage("smithy");
        smithyFilter.setScheme("file");

        DocumentFilter smithyJarFilter = new DocumentFilter();
        smithyJarFilter.setLanguage("smithy");
        smithyJarFilter.setScheme("smithyjar");

        List<DocumentFilter> smithyDocumentSelector = List.of(smithyFilter);

        var openCloseSmithyOpts = new TextDocumentRegistrationOptions(List.of(smithyFilter, smithyJarFilter));
        var changeSmithyOpts = new TextDocumentChangeRegistrationOptions(TextDocumentSyncKind.Incremental);
        changeSmithyOpts.setDocumentSelector(smithyDocumentSelector);
        var saveSmithyOpts = new TextDocumentSaveRegistrationOptions();
        saveSmithyOpts.setDocumentSelector(smithyDocumentSelector);
        saveSmithyOpts.setIncludeText(true);

        client.registerCapability(new RegistrationParams(List.of(
                new Registration("SyncSmithyFiles/Open", "textDocument/didOpen", openCloseSmithyOpts),
                new Registration("SyncSmithyFiles/Close", "textDocument/didClose", openCloseSmithyOpts),
                new Registration("SyncSmithyFiles/Change", "textDocument/didChange", changeSmithyOpts),
                new Registration("SyncSmithyFiles/Save", "textDocument/didSave", saveSmithyOpts))));
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return this;
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return this;
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        // TODO: Cancel all in-progress requests
        return completedFuture(new Object());
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override
    public void cancelProgress(WorkDoneProgressCancelParams params) {
        // TODO: Right now this stub just avoids a possible runtime error from the default
        //  impl in lsp4j. If we start using work done tokens, we will want to support canceling
        //  them here.
        LOGGER.warning("window/workDoneProgress/cancel not implemented");
    }

    @Override
    public void setTrace(SetTraceParams params) {
        // TODO: Eventually when we set up better logging, maybe there's something to do here.
        //  For now, this stub just avoids a runtime error from the default impl in lsp4j.
        LOGGER.warning("$/setTrace not implemented");
    }

    @Override
    public CompletableFuture<String> jarFileContents(TextDocumentIdentifier textDocumentIdentifier) {
        LOGGER.finest("JarFileContents");

        String uri = textDocumentIdentifier.getUri();
        ProjectAndFile projectAndFile = state.findProjectAndFile(uri);
        if (projectAndFile != null) {
            return completedFuture(projectAndFile.file().document().copyText());
        } else {
            // Technically this can throw if the uri is invalid
            return completedFuture(IoUtils.readUtf8Url(LspAdapter.jarUrl(uri)));
        }
    }

    @Override
    public CompletableFuture<List<? extends Location>> selectorCommand(SelectorParams selectorParams) {
        LOGGER.finest("SelectorCommand");
        Selector selector;
        try {
            selector = Selector.parse(selectorParams.expression());
        } catch (Exception e) {
            LOGGER.info("Invalid selector");
            // TODO: Respond with error somehow
            return completedFuture(Collections.emptyList());
        }

        return completedFuture(state.getAllProjects().stream()
                .flatMap(project -> project.modelResult().getResult().stream())
                .map(selector::select)
                .flatMap(shapes -> shapes.stream()
                        // TODO: Use proper location (source is just a point)
                        .map(Shape::getSourceLocation)
                        .map(LspAdapter::toLocation))
                .toList());
    }

    @Override
    public CompletableFuture<ServerStatus> serverStatus() {
        List<OpenProject> openProjects = new ArrayList<>();
        for (Project project : state.getAllProjects()) {
            openProjects.add(new OpenProject(
                    LspAdapter.toUri(project.root().toString()),
                    project.getAllSmithyFilePaths().stream()
                            .map(LspAdapter::toUri)
                            .toList(),
                    project.type() == Project.Type.DETACHED));
        }
        return completedFuture(new ServerStatus(openProjects));
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        LOGGER.finest("DidChangeWatchedFiles");

        // Smithy files were added or deleted to watched sources/imports (specified by smithy-build.json),
        // the smithy-build.json itself was changed, added, or deleted.
        reportProjectLoadErrors(state.applyFileEvents(params.getChanges()));

        // TODO: Update watchers based on specific changes
        // Note: We don't update build file watchers here - only on workspace changes
        unregisterSmithyFileWatchers().thenRun(this::registerSmithyFileWatchers);

        sendFileDiagnosticsForManagedDocuments();
    }

    @Override
    public void didChangeWorkspaceFolders(DidChangeWorkspaceFoldersParams params) {
        LOGGER.finest("DidChangeWorkspaceFolders");

        for (WorkspaceFolder folder : params.getEvent().getAdded()) {
            state.loadWorkspace(folder);
        }

        for (WorkspaceFolder folder : params.getEvent().getRemoved()) {
            state.removeWorkspace(folder);
        }

        unregisterSmithyFileWatchers().thenRun(this::registerSmithyFileWatchers);
        unregisterWorkspaceBuildFileWatchers().thenRun(this::registerWorkspaceBuildFileWatchers);
        sendFileDiagnosticsForManagedDocuments();
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        LOGGER.finest("DidChange");

        if (params.getContentChanges().isEmpty()) {
            LOGGER.info("Received empty DidChange");
            return;
        }

        String uri = params.getTextDocument().getUri();

        state.lifecycleTasks().cancelTask(uri);

        ProjectAndFile projectAndFile = state.findManaged(uri);
        if (projectAndFile == null) {
            client.unknownFileError(uri, "change");
            return;
        }

        Document document = projectAndFile.file().document();
        for (TextDocumentContentChangeEvent contentChangeEvent : params.getContentChanges()) {
            if (contentChangeEvent.getRange() != null) {
                document.applyEdit(contentChangeEvent.getRange(), contentChangeEvent.getText());
            } else {
                document.applyEdit(document.fullRange(), contentChangeEvent.getText());
            }
        }

        // Don't reload or update the project on build file changes, only on save
        if (!(projectAndFile.file() instanceof SmithyFile smithyFile)) {
            return;
        }

        smithyFile.reparse();
        if (!this.serverOptions.getOnlyReloadOnSave()) {
            Project project = projectAndFile.project();

            // TODO: A consequence of this is that any existing validation events are cleared, which
            //  is kinda annoying.
            // Report any parse/shape/trait loading errors
            CompletableFuture<Void> future = CompletableFuture
                    .runAsync(() -> project.updateModelWithoutValidating(uri))
                    .thenComposeAsync(unused -> sendFileDiagnostics(projectAndFile));
            state.lifecycleTasks().putTask(uri, future);
        }
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        LOGGER.finest("DidOpen");

        String uri = params.getTextDocument().getUri();

        state.lifecycleTasks().cancelTask(uri);

        ProjectAndFile projectAndFile = state.open(uri, params.getTextDocument().getText());

        state.lifecycleTasks().putTask(uri, sendFileDiagnostics(projectAndFile));
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        LOGGER.finest("DidClose");

        String uri = params.getTextDocument().getUri();
        state.close(uri);
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        LOGGER.finest("DidSave");

        String uri = params.getTextDocument().getUri();
        state.lifecycleTasks().cancelTask(uri);

        ProjectAndFile projectAndFile = state.findManaged(uri);
        if (projectAndFile == null) {
            client.unknownFileError(uri, "save");
            return;
        }

        if (params.getText() != null) {
            projectAndFile.file().document().applyEdit(null, params.getText());
        }

        Project project = projectAndFile.project();
        if (projectAndFile.file() instanceof BuildFile) {
            reportProjectLoadErrors(state.tryInitProject(project.root()));
            unregisterSmithyFileWatchers().thenRun(this::registerSmithyFileWatchers);
            sendFileDiagnosticsForManagedDocuments();
        } else {
            CompletableFuture<Void> future = CompletableFuture
                    .runAsync(() -> project.updateAndValidateModel(uri))
                    .thenCompose(unused -> sendFileDiagnostics(projectAndFile));
            state.lifecycleTasks().putTask(uri, future);
        }
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        LOGGER.finest("Completion");

        String uri = params.getTextDocument().getUri();
        ProjectAndFile projectAndFile = state.findProjectAndFile(uri);
        if (projectAndFile == null) {
            client.unknownFileError(uri, "completion");
            return completedFuture(Either.forLeft(Collections.emptyList()));
        }

        if (!(projectAndFile.file() instanceof IdlFile smithyFile)) {
            return completedFuture(Either.forLeft(List.of()));
        }

        Project project = projectAndFile.project();
        var handler = new CompletionHandler(project, smithyFile);
        return CompletableFutures.computeAsync((cc) -> Either.forLeft(handler.handle(params, cc)));
    }

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
        LOGGER.finest("ResolveCompletion");
        // TODO: Use this to add the import when a completion item is selected, if its expensive
        return completedFuture(unresolved);
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>>
    documentSymbol(DocumentSymbolParams params) {
        LOGGER.finest("DocumentSymbol");

        String uri = params.getTextDocument().getUri();
        ProjectAndFile projectAndFile = state.findProjectAndFile(uri);
        if (projectAndFile == null) {
            client.unknownFileError(uri, "document symbol");
            return completedFuture(Collections.emptyList());
        }

        if (!(projectAndFile.file() instanceof IdlFile idlFile)) {
            return completedFuture(List.of());
        }

        List<Syntax.Statement> statements = idlFile.getParse().statements();
        var handler = new DocumentSymbolHandler(idlFile.document(), statements);
        return CompletableFuture.supplyAsync(handler::handle);
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
    definition(DefinitionParams params) {
        LOGGER.finest("Definition");

        String uri = params.getTextDocument().getUri();
        ProjectAndFile projectAndFile = state.findProjectAndFile(uri);
        if (projectAndFile == null) {
            client.unknownFileError(uri, "definition");
            return completedFuture(null);
        }

        if (!(projectAndFile.file() instanceof IdlFile smithyFile)) {
            return completedFuture(null);
        }

        Project project = projectAndFile.project();
        var handler = new DefinitionHandler(project, smithyFile);
        return CompletableFuture.supplyAsync(() -> Either.forLeft(handler.handle(params)));
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        LOGGER.finest("Hover");

        String uri = params.getTextDocument().getUri();
        ProjectAndFile projectAndFile = state.findProjectAndFile(uri);
        if (projectAndFile == null) {
            client.unknownFileError(uri, "hover");
            return completedFuture(null);
        }

        if (!(projectAndFile.file() instanceof IdlFile smithyFile)) {
            return completedFuture(null);
        }

        Project project = projectAndFile.project();

        // TODO: Abstract away passing minimum severity
        var handler = new HoverHandler(project, smithyFile, this.serverOptions.getMinimumSeverity());
        return CompletableFuture.supplyAsync(() -> handler.handle(params));
    }

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        List<Either<Command, CodeAction>> versionCodeActions =
                SmithyCodeActions.versionCodeActions(params).stream()
                        .map(Either::<Command, CodeAction>forRight)
                        .collect(Collectors.toList());
        return completedFuture(versionCodeActions);
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        LOGGER.finest("Formatting");

        String uri = params.getTextDocument().getUri();
        ProjectAndFile projectAndFile = state.findProjectAndFile(uri);
        if (projectAndFile == null) {
            client.unknownFileError(uri, "format");
            return completedFuture(null);
        }

        if (!(projectAndFile.file() instanceof SmithyFile smithyFile)) {
            return completedFuture(null);
        }

        Document document = smithyFile.document();

        IdlTokenizer tokenizer = IdlTokenizer.create(uri, document.borrowText());
        TokenTree tokenTree = TokenTree.of(tokenizer);
        String formatted = Formatter.format(tokenTree);
        Range range = document.fullRange();
        TextEdit edit = new TextEdit(range, formatted);
        return completedFuture(Collections.singletonList(edit));
    }

    private void sendFileDiagnosticsForManagedDocuments() {
        for (ProjectAndFile managed : state.getAllManaged()) {
            state.lifecycleTasks().putOrComposeTask(managed.uri(), sendFileDiagnostics(managed));
        }
    }

    private CompletableFuture<Void> sendFileDiagnostics(ProjectAndFile projectAndFile) {
        return CompletableFuture.runAsync(() -> {
            List<Diagnostic> diagnostics = SmithyDiagnostics.getFileDiagnostics(
                    projectAndFile, this.serverOptions.getMinimumSeverity());
            var publishDiagnosticsParams = new PublishDiagnosticsParams(projectAndFile.uri(), diagnostics);
            client.publishDiagnostics(publishDiagnosticsParams);
        });
    }
}
