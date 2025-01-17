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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
import org.eclipse.lsp4j.DiagnosticSeverity;
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
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SetTraceParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
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
import software.amazon.smithy.lsp.document.DocumentParser;
import software.amazon.smithy.lsp.document.DocumentShape;
import software.amazon.smithy.lsp.ext.OpenProject;
import software.amazon.smithy.lsp.ext.SelectorParams;
import software.amazon.smithy.lsp.ext.ServerStatus;
import software.amazon.smithy.lsp.ext.SmithyProtocolExtensions;
import software.amazon.smithy.lsp.handler.CompletionHandler;
import software.amazon.smithy.lsp.handler.DefinitionHandler;
import software.amazon.smithy.lsp.handler.HoverHandler;
import software.amazon.smithy.lsp.project.BuildFile;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectAndFile;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.loader.IdlTokenizer;
import software.amazon.smithy.model.selector.Selector;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidationEvent;
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

    Project getFirstProject() {
        return state.attachedProjects().values().stream().findFirst().orElse(null);
    }

    ServerState getState() {
        return state;
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

    private void tryInitProject(Path root) {
        List<Exception> loadErrors = state.tryInitProject(root);
        if (!loadErrors.isEmpty()) {
            String baseMessage = "Failed to load Smithy project at " + root;
            StringBuilder errorMessage = new StringBuilder(baseMessage).append(":");
            for (Exception error : loadErrors) {
                errorMessage.append(System.lineSeparator());
                errorMessage.append('\t');
                errorMessage.append(error.getMessage());
            }
            client.error(errorMessage.toString());

            String showMessage = baseMessage + ". Check server logs to find out what went wrong.";
            client.showMessage(new MessageParams(MessageType.Error, showMessage));
        }
    }

    private CompletableFuture<Void> registerSmithyFileWatchers() {
        List<Registration> registrations = FileWatcherRegistrations.getSmithyFileWatcherRegistrations(
                state.attachedProjects().values());
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

        // Select from all available projects
        Collection<Project> detached = state.detachedProjects().values();
        Collection<Project> nonDetached = state.attachedProjects().values();

        return completedFuture(Stream.concat(detached.stream(), nonDetached.stream())
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
        for (Project project : state.attachedProjects().values()) {
            openProjects.add(new OpenProject(
                    LspAdapter.toUri(project.root().toString()),
                    project.smithyFiles().keySet().stream()
                            .map(LspAdapter::toUri)
                            .toList(),
                    false));
        }

        for (Map.Entry<String, Project> entry : state.detachedProjects().entrySet()) {
            openProjects.add(new OpenProject(
                    entry.getKey(),
                    Collections.singletonList(entry.getKey()),
                    true));
        }

        return completedFuture(new ServerStatus(openProjects));
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        LOGGER.finest("DidChangeWatchedFiles");
        // Smithy files were added or deleted to watched sources/imports (specified by smithy-build.json),
        // the smithy-build.json itself was changed, added, or deleted.

        WorkspaceChanges changes = WorkspaceChanges.computeWorkspaceChanges(params.getChanges(), state);

        changes.byProject().forEach((projectName, projectChange) -> {
            Project project = state.attachedProjects().get(projectName);

            if (!projectChange.changedBuildFileUris().isEmpty()) {
                client.info("Build files changed, reloading project");
                // TODO: Handle more granular updates to build files.
                // Note: This will take care of removing projects when build files are deleted
                tryInitProject(project.root());
            } else {
                Set<String> createdUris = projectChange.createdSmithyFileUris();
                Set<String> deletedUris = projectChange.deletedSmithyFileUris();
                client.info("Project files changed, adding files "
                            + createdUris + " and removing files " + deletedUris);

                // We get this notification for watched files, which only includes project files,
                // so we don't need to resolve detachedProjects projects.
                project.updateFiles(createdUris, deletedUris);
            }
        });

        changes.newProjectRoots().forEach(this::tryInitProject);

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

        state.lifecycleManager().cancelTask(uri);

        ProjectAndFile projectAndFile = state.findProjectAndFile(uri);
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
        if (projectAndFile.file() instanceof BuildFile) {
            return;
        }

        if (!this.serverOptions.getOnlyReloadOnSave()) {
            Project project = projectAndFile.project();

            // TODO: A consequence of this is that any existing validation events are cleared, which
            //  is kinda annoying.
            // Report any parse/shape/trait loading errors
            CompletableFuture<Void> future = CompletableFuture
                    .runAsync(() -> project.updateModelWithoutValidating(uri))
                    .thenComposeAsync(unused -> sendFileDiagnostics(uri));
            state.lifecycleManager().putTask(uri, future);
        }
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        LOGGER.finest("DidOpen");

        String uri = params.getTextDocument().getUri();

        state.lifecycleManager().cancelTask(uri);
        state.managedUris().add(uri);

        String text = params.getTextDocument().getText();
        ProjectAndFile projectAndFile = state.findProjectAndFile(uri);
        if (projectAndFile != null) {
            projectAndFile.file().document().applyEdit(null, text);
        } else {
            state.createDetachedProject(uri, text);
        }

        state.lifecycleManager().putTask(uri, sendFileDiagnostics(uri));
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        LOGGER.finest("DidClose");

        String uri = params.getTextDocument().getUri();
        state.managedUris().remove(uri);

        if (state.isDetached(uri)) {
            // Only cancel tasks for detachedProjects projects, since we're dropping the project
            state.lifecycleManager().cancelTask(uri);
            state.detachedProjects().remove(uri);
        }

        // TODO: Clear diagnostics? Can do this by sending an empty list
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        LOGGER.finest("DidSave");

        String uri = params.getTextDocument().getUri();
        state.lifecycleManager().cancelTask(uri);

        ProjectAndFile projectAndFile = state.findProjectAndFile(uri);
        if (projectAndFile == null) {
            // TODO: Could also load a detachedProjects project here, but I don't know how this would
            //  actually happen in practice
            client.unknownFileError(uri, "save");
            return;
        }

        if (params.getText() != null) {
            projectAndFile.file().document().applyEdit(null, params.getText());
        }

        Project project = projectAndFile.project();
        if (projectAndFile.file() instanceof BuildFile) {
            tryInitProject(project.root());
            unregisterSmithyFileWatchers().thenRun(this::registerSmithyFileWatchers);
            sendFileDiagnosticsForManagedDocuments();
        } else {
            CompletableFuture<Void> future = CompletableFuture
                    .runAsync(() -> project.updateAndValidateModel(uri))
                    .thenCompose(unused -> sendFileDiagnostics(uri));
            state.lifecycleManager().putTask(uri, future);
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

        if (!(projectAndFile.file() instanceof SmithyFile smithyFile)) {
            return completedFuture(Either.forLeft(List.of()));
        }

        Project project = projectAndFile.project();
        return CompletableFutures.computeAsync((cc) -> {
            CompletionHandler handler = new CompletionHandler(project, smithyFile);
            return Either.forLeft(handler.handle(params, cc));
        });
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

        if (!(projectAndFile.file() instanceof SmithyFile smithyFile)) {
            return completedFuture(List.of());
        }

        return CompletableFutures.computeAsync((cc) -> {
            Collection<DocumentShape> documentShapes = smithyFile.documentShapes();
            if (documentShapes.isEmpty()) {
                return Collections.emptyList();
            }

            if (cc.isCanceled()) {
                return Collections.emptyList();
            }

            List<Either<SymbolInformation, DocumentSymbol>> documentSymbols = new ArrayList<>(documentShapes.size());
            for (DocumentShape documentShape : documentShapes) {
                SymbolKind symbolKind;
                switch (documentShape.kind()) {
                    case Inline:
                        // No shape name in the document text, so no symbol
                        continue;
                    case DefinedMember:
                    case Elided:
                        symbolKind = SymbolKind.Property;
                        break;
                    case DefinedShape:
                    case Targeted:
                    default:
                        symbolKind = SymbolKind.Class;
                        break;
                }

                // Check before copying shapeName, which is actually a reference to the underlying document, and may
                // be changed.
                cc.checkCanceled();

                String symbolName = documentShape.shapeName().toString();
                if (symbolName.isEmpty()) {
                    LOGGER.warning("[DocumentSymbols] Empty shape name for " + documentShape);
                    continue;
                }
                Range symbolRange = documentShape.range();
                DocumentSymbol symbol = new DocumentSymbol(symbolName, symbolKind, symbolRange, symbolRange);
                documentSymbols.add(Either.forRight(symbol));
            }

            return documentSymbols;
        });
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

        if (!(projectAndFile.file() instanceof SmithyFile smithyFile)) {
            return completedFuture(null);
        }

        Project project = projectAndFile.project();
        List<Location> locations = new DefinitionHandler(project, smithyFile).handle(params);
        return completedFuture(Either.forLeft(locations));
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

        if (!(projectAndFile.file() instanceof SmithyFile smithyFile)) {
            return completedFuture(null);
        }

        Project project = projectAndFile.project();

        // TODO: Abstract away passing minimum severity
        Hover hover = new HoverHandler(project, smithyFile).handle(params, this.serverOptions.getMinimumSeverity());
        return completedFuture(hover);
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
        for (String managedDocumentUri : state.managedUris()) {
            state.lifecycleManager().putOrComposeTask(managedDocumentUri, sendFileDiagnostics(managedDocumentUri));
        }
    }

    private CompletableFuture<Void> sendFileDiagnostics(String uri) {
        return CompletableFuture.runAsync(() -> {
            List<Diagnostic> diagnostics = getFileDiagnostics(uri);
            PublishDiagnosticsParams publishDiagnosticsParams = new PublishDiagnosticsParams(uri, diagnostics);
            client.publishDiagnostics(publishDiagnosticsParams);
        });
    }

    List<Diagnostic> getFileDiagnostics(String uri) {
        if (LspAdapter.isJarFile(uri) || LspAdapter.isSmithyJarFile(uri)) {
            // Don't send diagnostics to jar files since they can't be edited
            // and diagnostics could be misleading.
            return Collections.emptyList();
        }

        ProjectAndFile projectAndFile = state.findProjectAndFile(uri);
        if (projectAndFile == null) {
            client.unknownFileError(uri, "diagnostics");
            return List.of();
        }

        if (!(projectAndFile.file() instanceof SmithyFile smithyFile)) {
            return List.of();
        }

        Project project = projectAndFile.project();
        String path = LspAdapter.toPath(uri);

        List<Diagnostic> diagnostics = project.modelResult().getValidationEvents().stream()
                .filter(validationEvent -> validationEvent.getSeverity().compareTo(
                        this.serverOptions.getMinimumSeverity()) >= 0)
                .filter(validationEvent -> validationEvent.getSourceLocation().getFilename().equals(path))
                .map(validationEvent -> toDiagnostic(validationEvent, smithyFile))
                .collect(Collectors.toCollection(ArrayList::new));

        Diagnostic versionDiagnostic = SmithyDiagnostics.versionDiagnostic(smithyFile);
        if (versionDiagnostic != null) {
            diagnostics.add(versionDiagnostic);
        }

        if (state.isDetached(uri)) {
            diagnostics.add(SmithyDiagnostics.detachedDiagnostic(smithyFile));
        }

        return diagnostics;
    }

    private static Diagnostic toDiagnostic(ValidationEvent validationEvent, SmithyFile smithyFile) {
        DiagnosticSeverity severity = toDiagnosticSeverity(validationEvent.getSeverity());
        SourceLocation sourceLocation = validationEvent.getSourceLocation();
        Range range = determineRange(validationEvent, sourceLocation, smithyFile);
        String message = validationEvent.getId() + ": " + validationEvent.getMessage();
        return new Diagnostic(range, message, severity, "Smithy");
    }

    private static Range determineRange(ValidationEvent validationEvent,
                                        SourceLocation sourceLocation,
                                        SmithyFile smithyFile) {
        final Range defaultRange = LspAdapter.lineOffset(LspAdapter.toPosition(sourceLocation));

        if (smithyFile == null) {
            return defaultRange;
        }

        DocumentParser parser = DocumentParser.forDocument(smithyFile.document());

        // Case where we have shapes present
        if (validationEvent.getShapeId().isPresent()) {
            // Event is (probably) on a member target
            if (validationEvent.containsId("Target")) {
                DocumentShape documentShape = smithyFile.documentShapesByStartPosition()
                        .get(LspAdapter.toPosition(sourceLocation));
                if (documentShape != null && documentShape.hasMemberTarget()) {
                    return documentShape.targetReference().range();
                }
            } else {
                // Check if the event location is on a trait application
                return Objects.requireNonNullElse(parser.traitIdRange(sourceLocation), defaultRange);
            }
        }

        return Objects.requireNonNullElse(parser.findContiguousRange(sourceLocation), defaultRange);
    }

    private static DiagnosticSeverity toDiagnosticSeverity(Severity severity) {
        return switch (severity) {
            case ERROR, DANGER -> DiagnosticSeverity.Error;
            case WARNING -> DiagnosticSeverity.Warning;
            case NOTE -> DiagnosticSeverity.Information;
            default -> DiagnosticSeverity.Hint;
        };
    }
}
