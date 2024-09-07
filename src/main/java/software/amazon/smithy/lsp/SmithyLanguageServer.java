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

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.Unregistration;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
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
import software.amazon.smithy.lsp.handler.FileWatcherRegistrationHandler;
import software.amazon.smithy.lsp.handler.HoverHandler;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectChanges;
import software.amazon.smithy.lsp.project.ProjectLoader;
import software.amazon.smithy.lsp.project.ProjectManager;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.lsp.util.Result;
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
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental);
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
    private final ProjectManager projects = new ProjectManager();
    private final DocumentLifecycleManager lifecycleManager = new DocumentLifecycleManager();
    private Severity minimumSeverity = Severity.WARNING;
    private boolean onlyReloadOnSave = false;

    SmithyLanguageServer() {
    }

    SmithyLanguageClient getClient() {
        return this.client;
    }

    Project getFirstProject() {
        return projects.attachedProjects().values().stream().findFirst().orElse(null);
    }

    ProjectManager getProjects() {
        return projects;
    }

    DocumentLifecycleManager getLifecycleManager() {
        return this.lifecycleManager;
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

        // TODO: Replace with a Gson Type Adapter if more config options are added beyond `logToFile`.
        Object initializationOptions = params.getInitializationOptions();
        if (initializationOptions instanceof JsonObject jsonObject) {
            if (jsonObject.has("diagnostics.minimumSeverity")) {
                String configuredMinimumSeverity = jsonObject.get("diagnostics.minimumSeverity").getAsString();
                Optional<Severity> severity = Severity.fromString(configuredMinimumSeverity);
                if (severity.isPresent()) {
                    this.minimumSeverity = severity.get();
                } else {
                    client.error(String.format("""
                            Invalid value for 'diagnostics.minimumSeverity': %s.
                            Must be one of %s.""", configuredMinimumSeverity, Arrays.toString(Severity.values())));
                }
            }
            if (jsonObject.has("onlyReloadOnSave")) {
                this.onlyReloadOnSave = jsonObject.get("onlyReloadOnSave").getAsBoolean();
                client.info("Configured only reload on save: " + this.onlyReloadOnSave);
            }
        }


        if (params.getWorkspaceFolders() != null && !params.getWorkspaceFolders().isEmpty()) {
            Either<String, Integer> workDoneProgressToken = params.getWorkDoneToken();
            if (workDoneProgressToken != null) {
                WorkDoneProgressBegin notification = new WorkDoneProgressBegin();
                notification.setTitle("Initializing");
                client.notifyProgress(new ProgressParams(workDoneProgressToken, Either.forLeft(notification)));
            }

            for (WorkspaceFolder workspaceFolder : params.getWorkspaceFolders()) {
                Path root = Paths.get(URI.create(workspaceFolder.getUri()));
                tryInitProject(workspaceFolder.getName(), root);
            }

            if (workDoneProgressToken != null) {
                WorkDoneProgressEnd notification = new WorkDoneProgressEnd();
                client.notifyProgress(new ProgressParams(workDoneProgressToken, Either.forLeft(notification)));
            }
        }

        LOGGER.finest("Done initialize");
        return completedFuture(new InitializeResult(CAPABILITIES));
    }

    private void tryInitProject(String name, Path root) {
        LOGGER.finest("Initializing project at " + root);
        lifecycleManager.cancelAllTasks();
        Result<Project, List<Exception>> loadResult = ProjectLoader.load(
                root, projects, lifecycleManager.managedDocuments());
        if (loadResult.isOk()) {
            Project updatedProject = loadResult.unwrap();
            resolveDetachedProjects(this.projects.getProjectByName(name), updatedProject);
            this.projects.updateProjectByName(name, updatedProject);
            LOGGER.finest("Initialized project at " + root);
        } else {
            LOGGER.severe("Init project failed");
            // TODO: Maybe we just start with this anyways by default, and then add to it
            //  if we find a smithy-build.json, etc.
            // If we overwrite an existing project with an empty one, we lose track of the state of tracked
            // files. Instead, we will just keep the original project before the reload failure.
            if (projects.getProjectByName(name) == null) {
                projects.updateProjectByName(name, Project.empty(root));
            }

            String baseMessage = "Failed to load Smithy project " + name + " at " + root;
            StringBuilder errorMessage = new StringBuilder(baseMessage).append(":");
            for (Exception error : loadResult.unwrapErr()) {
                errorMessage.append(System.lineSeparator());
                errorMessage.append('\t');
                errorMessage.append(error.getMessage());
            }
            client.error(errorMessage.toString());

            String showMessage = baseMessage + ". Check server logs to find out what went wrong.";
            client.showMessage(new MessageParams(MessageType.Error, showMessage));
        }
    }

    private void resolveDetachedProjects(Project oldProject, Project updatedProject) {
        // This is a project reload, so we need to resolve any added/removed files
        // that need to be moved to or from detached projects.
        if (oldProject != null) {
            Set<String> currentProjectSmithyPaths = oldProject.smithyFiles().keySet();
            Set<String> updatedProjectSmithyPaths = updatedProject.smithyFiles().keySet();

            Set<String> addedPaths = new HashSet<>(updatedProjectSmithyPaths);
            addedPaths.removeAll(currentProjectSmithyPaths);
            for (String addedPath : addedPaths) {
                String addedUri = LspAdapter.toUri(addedPath);
                if (projects.isDetached(addedUri)) {
                    projects.removeDetachedProject(addedUri);
                }
            }

            Set<String> removedPaths = new HashSet<>(currentProjectSmithyPaths);
            removedPaths.removeAll(updatedProjectSmithyPaths);
            for (String removedPath : removedPaths) {
                String removedUri = LspAdapter.toUri(removedPath);
                // Only move to a detached project if the file is managed
                if (lifecycleManager.managedDocuments().contains(removedUri)) {
                    Document removedDocument = oldProject.getDocument(removedUri);
                    // The copy here is technically unnecessary, if we make ModelAssembler support borrowed strings
                    projects.createDetachedProject(removedUri, removedDocument.copyText());
                }
            }
        }
    }

    private CompletableFuture<Void> registerSmithyFileWatchers() {
        List<Registration> registrations = FileWatcherRegistrationHandler.getSmithyFileWatcherRegistrations(
                projects.attachedProjects().values());
        return client.registerCapability(new RegistrationParams(registrations));
    }

    private CompletableFuture<Void> unregisterSmithyFileWatchers() {
        List<Unregistration> unregistrations = FileWatcherRegistrationHandler.getSmithyFileWatcherUnregistrations();
        return client.unregisterCapability(new UnregistrationParams(unregistrations));
    }

    @Override
    public void initialized(InitializedParams params) {
        List<Registration> registrations = FileWatcherRegistrationHandler.getBuildFileWatcherRegistrations(
                projects.attachedProjects().values());
        client.registerCapability(new RegistrationParams(registrations));
        registerSmithyFileWatchers();
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
    public CompletableFuture<String> jarFileContents(TextDocumentIdentifier textDocumentIdentifier) {
        LOGGER.finest("JarFileContents");
        String uri = textDocumentIdentifier.getUri();
        Project project = projects.getProject(uri);
        Document document = project.getDocument(uri);
        if (document != null) {
            return completedFuture(document.copyText());
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
        Collection<Project> detached = projects.detachedProjects().values();
        Collection<Project> nonDetached = projects.attachedProjects().values();

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
        for (Project project : projects.attachedProjects().values()) {
            openProjects.add(new OpenProject(
                    LspAdapter.toUri(project.root().toString()),
                    project.smithyFiles().keySet().stream()
                            .map(LspAdapter::toUri)
                            .toList(),
                    false));
        }

        for (Map.Entry<String, Project> entry : projects.detachedProjects().entrySet()) {
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
        // or the smithy-build.json itself was changed

        Map<String, ProjectChanges> changesByProject = projects.computeProjectChanges(params.getChanges());

        changesByProject.forEach((projectName, projectChanges) -> {
            Project project = projects.getProjectByName(projectName);
            if (projectChanges.hasChangedBuildFiles()) {
                client.info("Build files changed, reloading project");
                // TODO: Handle more granular updates to build files.
                tryInitProject(projectName, project.root());
            } else if (projectChanges.hasChangedSmithyFiles()) {
                Set<String> createdUris = projectChanges.createdSmithyFileUris();
                Set<String> deletedUris = projectChanges.deletedSmithyFileUris();
                client.info("Project files changed, adding files "
                            + createdUris + " and removing files " + deletedUris);

                // We get this notification for watched files, which only includes project files,
                // so we don't need to resolve detached projects.
                project.updateFiles(createdUris, deletedUris);
            }
        });

        // TODO: Update watchers based on specific changes
        unregisterSmithyFileWatchers().thenRun(this::registerSmithyFileWatchers);

        sendFileDiagnosticsForManagedDocuments();
    }

    @Override
    public void didChangeWorkspaceFolders(DidChangeWorkspaceFoldersParams params) {
        LOGGER.finest("DidChangeWorkspaceFolders");

        Either<String, Integer> progressToken = Either.forLeft(UUID.randomUUID().toString());
        try {
            client.createProgress(new WorkDoneProgressCreateParams(progressToken)).get();
        } catch (ExecutionException | InterruptedException e) {
            client.error(String.format("Unable to create work done progress token: %s", e.getMessage()));
            progressToken = null;
        }

        if (progressToken != null) {
            WorkDoneProgressBegin begin = new WorkDoneProgressBegin();
            begin.setTitle("Updating workspace");
            client.notifyProgress(new ProgressParams(progressToken, Either.forLeft(begin)));
        }

        for (WorkspaceFolder folder : params.getEvent().getAdded()) {
            Path root = Paths.get(URI.create(folder.getUri()));
            tryInitProject(folder.getName(), root);
        }

        for (WorkspaceFolder folder : params.getEvent().getRemoved()) {
            Project removedProject = projects.removeProjectByName(folder.getName());
            if (removedProject == null) {
                continue;
            }

            resolveDetachedProjects(removedProject, Project.empty(removedProject.root()));
        }

        unregisterSmithyFileWatchers().thenRun(this::registerSmithyFileWatchers);
        sendFileDiagnosticsForManagedDocuments();

        if (progressToken != null) {
            WorkDoneProgressEnd end = new WorkDoneProgressEnd();
            client.notifyProgress(new ProgressParams(progressToken, Either.forLeft(end)));
        }
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

        lifecycleManager.cancelTask(uri);

        Document document = projects.getDocument(uri);
        if (document == null) {
            client.unknownFileError(uri, "change");
            return;
        }

        for (TextDocumentContentChangeEvent contentChangeEvent : params.getContentChanges()) {
            if (contentChangeEvent.getRange() != null) {
                document.applyEdit(contentChangeEvent.getRange(), contentChangeEvent.getText());
            } else {
                document.applyEdit(document.fullRange(), contentChangeEvent.getText());
            }
        }

        if (!onlyReloadOnSave) {
            Project project = projects.getProject(uri);
            if (project == null) {
                client.unknownFileError(uri, "change");
                return;
            }

            // TODO: A consequence of this is that any existing validation events are cleared, which
            //  is kinda annoying.
            // Report any parse/shape/trait loading errors
            CompletableFuture<Void> future = CompletableFuture
                    .runAsync(() -> project.updateModelWithoutValidating(uri))
                    .thenComposeAsync(unused -> sendFileDiagnostics(uri));
            lifecycleManager.putTask(uri, future);
        }
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        LOGGER.finest("DidOpen");

        String uri = params.getTextDocument().getUri();

        lifecycleManager.cancelTask(uri);
        lifecycleManager.managedDocuments().add(uri);

        String text = params.getTextDocument().getText();
        Document document = projects.getDocument(uri);
        if (document != null) {
            document.applyEdit(null, text);
        } else {
            projects.createDetachedProject(uri, text);
        }

        lifecycleManager.putTask(uri, sendFileDiagnostics(uri));
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        LOGGER.finest("DidClose");

        String uri = params.getTextDocument().getUri();
        lifecycleManager.managedDocuments().remove(uri);

        if (projects.isDetached(uri)) {
            // Only cancel tasks for detached projects, since we're dropping the project
            lifecycleManager.cancelTask(uri);
            projects.removeDetachedProject(uri);
        }

        // TODO: Clear diagnostics? Can do this by sending an empty list
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        LOGGER.finest("DidSave");

        String uri = params.getTextDocument().getUri();
        lifecycleManager.cancelTask(uri);
        if (!projects.isTracked(uri)) {
            // TODO: Could also load a detached project here, but I don't know how this would
            //  actually happen in practice
            client.unknownFileError(uri, "save");
            return;
        }

        Project project = projects.getProject(uri);
        if (params.getText() != null) {
            Document document = project.getDocument(uri);
            document.applyEdit(null, params.getText());
        }

        CompletableFuture<Void> future = CompletableFuture
                .runAsync(() -> project.updateAndValidateModel(uri))
                .thenCompose(unused -> sendFileDiagnostics(uri));
        lifecycleManager.putTask(uri, future);
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        LOGGER.finest("Completion");

        String uri = params.getTextDocument().getUri();
        if (!projects.isTracked(uri)) {
            client.unknownFileError(uri, "completion");
            return completedFuture(Either.forLeft(Collections.emptyList()));
        }

        Project project = projects.getProject(uri);
        SmithyFile smithyFile = project.getSmithyFile(uri);
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
        if (!projects.isTracked(uri)) {
            client.unknownFileError(uri, "document symbol");
            return completedFuture(Collections.emptyList());
        }

        Project project = projects.getProject(uri);
        SmithyFile smithyFile = project.getSmithyFile(uri);

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
        if (!projects.isTracked(uri)) {
            client.unknownFileError(uri, "definition");
            return completedFuture(Either.forLeft(Collections.emptyList()));
        }

        Project project = projects.getProject(uri);
        SmithyFile smithyFile = project.getSmithyFile(uri);
        List<Location> locations = new DefinitionHandler(project, smithyFile).handle(params);
        return completedFuture(Either.forLeft(locations));
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        LOGGER.finest("Hover");

        String uri = params.getTextDocument().getUri();
        if (!projects.isTracked(uri)) {
            client.unknownFileError(uri, "hover");
            return completedFuture(HoverHandler.emptyContents());
        }

        Project project = projects.getProject(uri);
        SmithyFile smithyFile = project.getSmithyFile(uri);

        // TODO: Abstract away passing minimum severity
        Hover hover = new HoverHandler(project, smithyFile).handle(params, minimumSeverity);
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
        Project project = projects.getProject(uri);
        Document document = project.getDocument(uri);
        if (document == null) {
            return completedFuture(Collections.emptyList());
        }

        IdlTokenizer tokenizer = IdlTokenizer.create(uri, document.borrowText());
        TokenTree tokenTree = TokenTree.of(tokenizer);
        String formatted = Formatter.format(tokenTree);
        Range range = document.fullRange();
        TextEdit edit = new TextEdit(range, formatted);
        return completedFuture(Collections.singletonList(edit));
    }

    private void sendFileDiagnosticsForManagedDocuments() {
        for (String managedDocumentUri : lifecycleManager.managedDocuments()) {
            lifecycleManager.putOrComposeTask(managedDocumentUri, sendFileDiagnostics(managedDocumentUri));
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

        if (!projects.isTracked(uri)) {
            client.unknownFileError(uri, "diagnostics");
        }

        Project project = projects.getProject(uri);
        SmithyFile smithyFile = project.getSmithyFile(uri);
        String path = LspAdapter.toPath(uri);

        List<Diagnostic> diagnostics = project.modelResult().getValidationEvents().stream()
                .filter(validationEvent -> validationEvent.getSeverity().compareTo(minimumSeverity) >= 0)
                .filter(validationEvent -> validationEvent.getSourceLocation().getFilename().equals(path))
                .map(validationEvent -> toDiagnostic(validationEvent, smithyFile))
                .collect(Collectors.toCollection(ArrayList::new));

        Diagnostic versionDiagnostic = SmithyDiagnostics.versionDiagnostic(smithyFile);
        if (versionDiagnostic != null) {
            diagnostics.add(versionDiagnostic);
        }

        if (projects.isDetached(uri)) {
            diagnostics.add(SmithyDiagnostics.detachedDiagnostic(smithyFile));
        }

        return diagnostics;
    }

    private static Diagnostic toDiagnostic(ValidationEvent validationEvent, SmithyFile smithyFile) {
        DiagnosticSeverity severity = toDiagnosticSeverity(validationEvent.getSeverity());
        SourceLocation sourceLocation = validationEvent.getSourceLocation();

        // TODO: Improve location of diagnostics
        Range range = LspAdapter.lineOffset(LspAdapter.toPosition(sourceLocation));
        if (validationEvent.getShapeId().isPresent() && smithyFile != null) {
            // Event is (probably) on a member target
            if (validationEvent.containsId("Target")) {
                DocumentShape documentShape = smithyFile.documentShapesByStartPosition()
                        .get(LspAdapter.toPosition(sourceLocation));
                if (documentShape != null && documentShape.hasMemberTarget()) {
                    range = documentShape.targetReference().range();
                }
            }  else {
                // Check if the event location is on a trait application
                Range traitRange = DocumentParser.forDocument(smithyFile.document()).traitIdRange(sourceLocation);
                if (traitRange != null) {
                    range = traitRange;
                }
            }
        }

        String message = validationEvent.getId() + ": " + validationEvent.getMessage();
        return new Diagnostic(range, message, severity, "Smithy");
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
