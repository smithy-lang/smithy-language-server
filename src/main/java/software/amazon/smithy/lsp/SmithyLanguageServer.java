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
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static org.eclipse.lsp4j.jsonrpc.CompletableFutures.computeAsync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
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
import org.eclipse.lsp4j.DynamicRegistrationCapabilities;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.RenameOptions;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SetTraceParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentChangeRegistrationOptions;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
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
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceFoldersOptions;
import org.eclipse.lsp4j.WorkspaceServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
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
import software.amazon.smithy.lsp.language.BuildCompletionHandler;
import software.amazon.smithy.lsp.language.BuildHoverHandler;
import software.amazon.smithy.lsp.language.CompletionHandler;
import software.amazon.smithy.lsp.language.DefinitionHandler;
import software.amazon.smithy.lsp.language.DocumentSymbolHandler;
import software.amazon.smithy.lsp.language.FoldingRangeHandler;
import software.amazon.smithy.lsp.language.HoverHandler;
import software.amazon.smithy.lsp.language.InlayHintHandler;
import software.amazon.smithy.lsp.language.ReferencesHandler;
import software.amazon.smithy.lsp.language.RenameHandler;
import software.amazon.smithy.lsp.project.BuildFile;
import software.amazon.smithy.lsp.project.IdlFile;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectAndFile;
import software.amazon.smithy.lsp.project.ProjectDiffer;
import software.amazon.smithy.lsp.project.ProjectFile;
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
    static final String RELOAD_DIFF_BASELINE_COMMAND = "smithy.reloadDiffBaseline";

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
        capabilities.setFoldingRangeProvider(true);
        capabilities.setInlayHintProvider(true);
        capabilities.setReferencesProvider(true);
        capabilities.setRenameProvider(new RenameOptions(true));
        capabilities.setExecuteCommandProvider(new ExecuteCommandOptions(List.of(RELOAD_DIFF_BASELINE_COMMAND)));

        WorkspaceFoldersOptions workspaceFoldersOptions = new WorkspaceFoldersOptions();
        workspaceFoldersOptions.setSupported(true);
        capabilities.setWorkspace(new WorkspaceServerCapabilities(workspaceFoldersOptions));

        CAPABILITIES = capabilities;
    }

    private SmithyLanguageClient client;
    private final ServerState state = new ServerState();
    private final ProjectDiffer projectDiffer;

    // The unmanaged (closed) files that currently carry a published diff diagnostic, keyed by
    // project root. Tracked so that when a breaking change is fixed and a file stops carrying a
    // diff event, its stale diagnostic is cleared — sendFileDiagnosticsForManagedDocuments only
    // re-publishes open files, so a closed file would otherwise keep the diagnostic forever.
    private final Map<String, Set<String>> unmanagedDiffDiagnosticUrisByRoot = new ConcurrentHashMap<>();
    private ClientCapabilities clientCapabilities;
    private ServerOptions serverOptions;

    SmithyLanguageServer() {
        this.projectDiffer = new ProjectDiffer(this::showBaselineConfigError);
        registerProjectDiffer();
    }

    // Test seam: lets tests supply a ProjectDiffer with an in-memory baseline provider so the
    // diff can run without resolving a real Maven coordinate.
    SmithyLanguageServer(ProjectDiffer projectDiffer) {
        this.projectDiffer = projectDiffer;
        registerProjectDiffer();
    }

    // Release each project's diff resources (its cached evaluator class loader) when the project
    // is removed, so dependency jar handles aren't leaked (finding #10), and clear any diff
    // diagnostics still published to the removed project's unopened files — nothing would ever
    // republish them once the project is gone.
    private void registerProjectDiffer() {
        state.setProjectRemovalListener(root -> {
            projectDiffer.evict(root);
            clearUnmanagedDiffDiagnostics(root);
        });
    }

    // Clears diff diagnostics previously published to unmanaged (closed) files of a removed
    // project. Synchronized with sendDiffDiagnosticsForUnmanagedAnchoredFiles, which maintains
    // the same tracking map.
    private synchronized void clearUnmanagedDiffDiagnostics(String root) {
        Set<String> uris = unmanagedDiffDiagnosticUrisByRoot.remove(root);
        if (uris == null || client == null) {
            return;
        }
        for (String uri : uris) {
            if (state.findManaged(uri) == null) {
                client.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
            }
        }
    }

    // Logs a diff task that died unexpectedly; without this, an exception escaping a
    // CompletableFuture chain nobody joins would vanish entirely. Cancellation (didChange
    // superseding a save's task) is routine, not an error.
    private static void logDiffTaskFailure(Object ignored, Throwable e) {
        if (e != null && !(e instanceof CancellationException)) {
            LOGGER.log(Level.WARNING, "Diff task failed", e);
        }
    }

    ServerState getState() {
        return state;
    }

    Severity getMinimumSeverity() {
        return serverOptions.getMinimumSeverity();
    }

    // Eagerly load each loaded project's diff baseline at startup, off the message thread, so the
    // first save isn't slowed by baseline resolution + assembly. A baseline config error surfaces
    // via the same window message / diagnostic path as a save-time diff; projects without a diff
    // config are skipped inside ProjectDiffer.
    private void warmDiffBaselines() {
        // Snapshot before iterating on a background thread: later LSP callbacks mutate the
        // project map concurrently with this loop.
        List<Project> projects = List.copyOf(state.getAllProjects());
        CompletableFuture.runAsync(() -> {
            for (Project project : projects) {
                projectDiffer.warmBaseline(project);
            }
        }).whenComplete(SmithyLanguageServer::logDiffTaskFailure);
    }

    // Surfaces a baseline configuration error as a window message, in addition to the
    // diagnostic on .smithy-project.json (which is only visible when that file is open).
    private void showBaselineConfigError(String message) {
        if (client != null) {
            client.showMessage(new MessageParams(MessageType.Error, message));
        }
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

            warmDiffBaselines();
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
        return Optional.ofNullable(clientCapabilities)
                .map(ClientCapabilities::getTextDocument)
                .map(TextDocumentClientCapabilities::getSynchronization)
                .map(DynamicRegistrationCapabilities::getDynamicRegistration)
                .orElse(false);
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
            return completedFuture(IoUtils.readUtf8Url(LspAdapter.smithyJarUriToReadableUrl(uri)));
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
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        LOGGER.finest("ExecuteCommand");

        if (RELOAD_DIFF_BASELINE_COMMAND.equals(params.getCommand())) {
            // Snapshot roots rather than Project instances: a concurrent rebuild (build-file
            // save, watched-file change) swaps the instance in ServerState, and the reload's
            // diff events must land on the live instance or the refresh below won't see them.
            List<String> roots = state.getAllProjects().stream()
                    .map(project -> project.root().toString())
                    .toList();
            return CompletableFuture.runAsync(() -> {
                for (String root : roots) {
                    Project project = state.findProjectByRoot(root);
                    if (project != null) {
                        projectDiffer.reload(project);
                        sendDiffDiagnosticsForUnmanagedAnchoredFiles(project);
                    }
                }
                sendFileDiagnosticsForManagedDocuments();
            }).whenComplete(SmithyLanguageServer::logDiffTaskFailure).thenApply(ignored -> null);
        }

        return completedFuture(null);
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
        // Rebuilt projects carry over the previous diff events (see ServerState.tryInitProject),
        // but the external change may have altered the model or the diff config, so re-run the
        // diff and refresh whatever it touched.
        runDiffsAndPublish();
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
        // Projects in newly added folders haven't run a diff yet; run it and refresh.
        runDiffsAndPublish();
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

        projectAndFile.file().reparse();

        Project project = projectAndFile.project();
        switch (projectAndFile.file()) {
            case SmithyFile ignored -> {
                if (this.serverOptions.getOnlyReloadOnSave()) {
                    return;
                }

                // TODO: A consequence of this is that any existing validation events are cleared, which
                //  is kinda annoying.
                // Report any parse/shape/trait loading errors
                CompletableFuture<Void> future = CompletableFuture
                        .runAsync(() -> project.updateModelWithoutValidating(uri))
                        .thenRunAsync(() -> sendFileDiagnostics(projectAndFile));

                state.lifecycleTasks().putTask(uri, future);
            }
            case BuildFile ignored -> {
                CompletableFuture<Void> future = CompletableFuture
                        .runAsync(project::validateConfig)
                        .thenRunAsync(() -> sendFileDiagnostics(projectAndFile));

                state.lifecycleTasks().putTask(uri, future);
            }
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
            // Publish the rebuilt project's diagnostics right away — the diff below can block on
            // baseline network I/O and must not delay them. tryInitProject carries the previous
            // diff events onto the rebuilt project, so this publish doesn't wipe them.
            sendFileDiagnosticsForManagedDocuments();
            // Re-run the diff against the rebuilt project so a changed baseline coordinate is
            // picked up immediately (the coordinate-keyed cache in ProjectDiffer rebuilds when
            // it changes). Keep the baseline I/O off the message thread.
            Project rebuilt = state.findProjectByRoot(project.root().toString());
            if (rebuilt != null) {
                CompletableFuture<Void> future = CompletableFuture
                        .supplyAsync(() -> projectDiffer.runDiff(rebuilt))
                        .thenAccept(changedDiffFiles -> {
                            if (!changedDiffFiles.isEmpty()) {
                                sendFileDiagnosticsForManagedDocuments();
                                sendDiffDiagnosticsForUnmanagedAnchoredFiles(rebuilt);
                            }
                        })
                        .whenComplete(SmithyLanguageServer::logDiffTaskFailure);
                state.lifecycleTasks().putTask(uri, future);
            }
        } else {
            CompletableFuture<Void> future = CompletableFuture
                    .runAsync(() -> project.updateAndValidateModel(uri))
                    // Publish the saved file's ordinary validation diagnostics immediately: the
                    // diff below can block on baseline network I/O, and cancelling this chain
                    // (didChange superseding the save) only cancels stages after this one.
                    .thenRun(() -> publishFileDiagnostics(projectAndFile))
                    // The changed-diff-files set (empty when the diff produced the same events
                    // as last time) lets us skip the workspace-wide refresh when nothing the
                    // diff touched changed.
                    .thenApplyAsync(ignored -> projectDiffer.runDiff(project))
                    .thenAccept(changedDiffFiles -> {
                        if (changedDiffFiles.isEmpty()) {
                            return;
                        }
                        // Diff events can anchor to files other than the saved one (a removal's
                        // namespace file, or the build file). Only refresh those files when the
                        // diff actually changed them; otherwise keep the cheaper single-file path
                        // for the saved document (finding #11).
                        String savedPath = LspAdapter.toPath(uri);
                        boolean onlySavedFileChanged =
                                changedDiffFiles.size() == 1 && changedDiffFiles.contains(savedPath);
                        if (onlySavedFileChanged) {
                            publishFileDiagnostics(projectAndFile);
                        } else {
                            sendFileDiagnosticsForManagedDocuments();
                            sendDiffDiagnosticsForUnmanagedAnchoredFiles(project);
                        }
                    })
                    .whenComplete(SmithyLanguageServer::logDiffTaskFailure);
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

        Project project = projectAndFile.project();
        return switch (projectAndFile.file()) {
            case IdlFile idlFile -> {
                var handler = new CompletionHandler(project, idlFile);
                yield computeAsync((cc) -> Either.forLeft(handler.handle(params, cc)));
            }
            case BuildFile buildFile -> {
                var handler = new BuildCompletionHandler(project, buildFile);
                yield supplyAsync(() -> Either.forLeft(handler.handle(params)));
            }
            default -> completedFuture(Either.forLeft(List.of()));
        };
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
    public CompletableFuture<List<FoldingRange>> foldingRange(FoldingRangeRequestParams params) {
        LOGGER.finest("FoldingRange");

        String uri = params.getTextDocument().getUri();
        ProjectAndFile projectAndFile = state.findProjectAndFile(uri);
        if (projectAndFile == null) {
            client.unknownFileError(uri, "folding range");
            return completedFuture(Collections.emptyList());
        }

        if (!(projectAndFile.file() instanceof IdlFile idlFile)) {
            return completedFuture(List.of());
        }

        List<Syntax.Statement> statements = idlFile.getParse().statements();
        var handler = new FoldingRangeHandler(idlFile.document(), idlFile.getParse().imports(), statements);
        return CompletableFuture.supplyAsync(handler::handle);
    }

    @Override
    public CompletableFuture<List<InlayHint>> inlayHint(InlayHintParams params) {
        LOGGER.finest("InlayHint");

        String uri = params.getTextDocument().getUri();
        ProjectAndFile projectAndFile = state.findProjectAndFile(uri);
        if (projectAndFile == null) {
            client.unknownFileError(uri, "inlay hint");
            return completedFuture(Collections.emptyList());
        }

        if (!(projectAndFile.file() instanceof IdlFile idlFile)) {
            return completedFuture(List.of());
        }

        List<Syntax.Statement> statements = idlFile.getParse().statements();
        var handler = new InlayHintHandler(idlFile.document(), statements, params.getRange());
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

        return switch (projectAndFile.file()) {
            case IdlFile idlFile -> {
                Project project = projectAndFile.project();

                var handler = new HoverHandler(project, idlFile);
                yield CompletableFuture.supplyAsync(() -> handler.handle(params));
            }
            case BuildFile buildFile -> {
                var handler = new BuildHoverHandler(buildFile);
                yield CompletableFuture.supplyAsync(() -> handler.handle(params));
            }
            default -> completedFuture(null);
        };
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

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        LOGGER.finest("References");
        String uri = params.getTextDocument().getUri();
        ProjectAndFile projectAndFile = state.findProjectAndFile(uri);
        if (projectAndFile == null) {
            client.unknownFileError(uri, "references");
            return completedFuture(null);
        }

        if (!(projectAndFile.file() instanceof IdlFile idlFile)) {
            return completedFuture(null);
        }

        var handler = new ReferencesHandler(projectAndFile.project(), idlFile);
        return supplyAsync(() -> handler.handle(params));
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        LOGGER.finest("Rename");
        String uri = params.getTextDocument().getUri();
        ProjectAndFile projectAndFile = state.findProjectAndFile(uri);
        if (projectAndFile == null) {
            client.unknownFileError(uri, "rename");
            return completedFuture(null);
        }

        if (!(projectAndFile.file() instanceof IdlFile idlFile)) {
            return completedFuture(null);
        }

        var handler = new RenameHandler(projectAndFile.project(), idlFile);
        return supplyAsync(() -> handler.handle(params));
    }

    @Override
    public CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>>
    prepareRename(PrepareRenameParams params) {
        LOGGER.finest("PrepareRename");
        String uri = params.getTextDocument().getUri();
        ProjectAndFile projectAndFile = state.findProjectAndFile(uri);
        if (projectAndFile == null) {
            client.unknownFileError(uri, "prepareRename");
            return completedFuture(null);
        }

        if (!(projectAndFile.file() instanceof IdlFile idlFile)) {
            return completedFuture(null);
        }

        var handler = new RenameHandler(projectAndFile.project(), idlFile);
        return supplyAsync(() -> Either3.forFirst(handler.prepare(params)));
    }

    private void sendFileDiagnosticsForManagedDocuments() {
        for (ProjectAndFile managed : state.getAllManaged()) {
            state.lifecycleTasks().putOrComposeTask(managed.uri(), sendFileDiagnostics(managed));
        }
    }

    // Re-runs the diff for every project off the message thread and refreshes diagnostics for
    // the files the diff touched. Used by paths that rebuild projects outside didSave (watched
    // file changes, workspace folder changes), which would otherwise leave diff diagnostics
    // stale. Roots are resolved to live Project instances at execution time so results land on
    // the instance ServerState currently tracks.
    private void runDiffsAndPublish() {
        List<String> roots = state.getAllProjects().stream()
                .map(project -> project.root().toString())
                .toList();
        CompletableFuture.runAsync(() -> {
            boolean anyChanged = false;
            for (String root : roots) {
                Project project = state.findProjectByRoot(root);
                if (project == null) {
                    continue;
                }
                if (!projectDiffer.runDiff(project).isEmpty()) {
                    anyChanged = true;
                    sendDiffDiagnosticsForUnmanagedAnchoredFiles(project);
                }
            }
            if (anyChanged) {
                sendFileDiagnosticsForManagedDocuments();
            }
        }).whenComplete(SmithyLanguageServer::logDiffTaskFailure);
    }

    // Diff events re-anchor to whichever file is most relevant — a removal's namespace file, or
    // the .smithy-project.json for full-namespace removals / no-shapeId events / baseline config
    // errors. Those target files are often not open, and the managed-document refresh above only
    // reaches open files, so the most severe breaks would never appear in the Problems panel
    // (finding #6). publishDiagnostics populates the panel for any URI, open or not, so publish
    // for each distinct unmanaged file a diff event anchored to. (Open files are already covered.)
    // Synchronized: concurrent save/reload continuations would otherwise interleave the
    // read-modify-write on unmanagedDiffDiagnosticUrisByRoot and lose stale-URI clears.
    private synchronized void sendDiffDiagnosticsForUnmanagedAnchoredFiles(Project project) {
        String root = project.root().toString();
        Set<String> anchoredPaths = project.diffEvents().stream()
                .map(event -> event.getSourceLocation().getFilename())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> currentUris = new LinkedHashSet<>();
        for (String path : anchoredPaths) {
            String uri = LspAdapter.toUri(path);
            if (state.findManaged(uri) != null) {
                continue; // open files are refreshed via sendFileDiagnosticsForManagedDocuments
            }
            ProjectFile file = project.getProjectFile(uri);
            if (file != null) {
                currentUris.add(uri);
                publishFileDiagnostics(new ProjectAndFile(uri, project, file));
            }
        }

        // Clear stale diff diagnostics: any unmanaged file that carried a diff diagnostic on the
        // previous cycle but no longer does (e.g. the breaking change was fixed). Re-publishing its
        // current diagnostics drops the now-absent diff event while keeping any real diagnostics;
        // if it's no longer a project file, publish an empty set to clear it outright.
        Set<String> previousUris = unmanagedDiffDiagnosticUrisByRoot.getOrDefault(root, Set.of());
        for (String staleUri : previousUris) {
            if (currentUris.contains(staleUri) || state.findManaged(staleUri) != null) {
                continue;
            }
            ProjectFile file = project.getProjectFile(staleUri);
            if (file != null) {
                publishFileDiagnostics(new ProjectAndFile(staleUri, project, file));
            } else {
                client.publishDiagnostics(new PublishDiagnosticsParams(staleUri, List.of()));
            }
        }

        if (currentUris.isEmpty()) {
            unmanagedDiffDiagnosticUrisByRoot.remove(root);
        } else {
            unmanagedDiffDiagnosticUrisByRoot.put(root, currentUris);
        }
    }

    private CompletableFuture<Void> sendFileDiagnostics(ProjectAndFile projectAndFile) {
        return CompletableFuture.runAsync(() -> publishFileDiagnostics(projectAndFile));
    }

    private void publishFileDiagnostics(ProjectAndFile projectAndFile) {
        List<Diagnostic> diagnostics = SmithyDiagnostics.getFileDiagnostics(
                projectAndFile, getMinimumSeverity());
        var publishDiagnosticsParams = new PublishDiagnosticsParams(projectAndFile.uri(), diagnostics);
        client.publishDiagnostics(publishDiagnosticsParams);
    }
}
