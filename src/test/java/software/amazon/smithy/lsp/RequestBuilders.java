/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.CompletionTriggerKind;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent;
import software.amazon.smithy.utils.IoUtils;

/**
 * Contains builder classes for LSP requests/notifications used for testing
 */
public final class RequestBuilders {
    private RequestBuilders() {}

    public static DidChange didChange() {
        return new DidChange();
    }

    public static DidOpen didOpen() {
        return new DidOpen();
    }

    public static DidSave didSave() {
        return new DidSave();
    }

    public static DidClose didClose() {
        return new DidClose();
    }

    public static Initialize initialize() {
        return new Initialize();
    }

    public static PositionRequest positionRequest() {
        return new PositionRequest();
    }

    public static DidChangeWatchedFiles didChangeWatchedFiles() {
        return new DidChangeWatchedFiles();
    }

    public static DidChangeWorkspaceFolders didChangeWorkspaceFolders() {
        return new DidChangeWorkspaceFolders();
    }

    public static final class DidChange {
        private String uri;
        private Integer version = 1;
        private Range range;
        private String text;

        public DidChange next() {
            this.version += 1;
            return this;
        }

        public DidChange uri(String uri) {
            this.uri = uri;
            return this;
        }

        public DidChange version(Integer version) {
            this.version = version;
            return this;
        }

        public DidChange range(Range range) {
            this.range = range;
            return this;
        }

        public DidChange text(String text) {
            this.text = text;
            return this;
        }

        public DidChangeTextDocumentParams build() {
            VersionedTextDocumentIdentifier id = new VersionedTextDocumentIdentifier(uri, version);
            TextDocumentContentChangeEvent change;
            if (range != null) {
                change = new TextDocumentContentChangeEvent(range, text);
            } else {
                change = new TextDocumentContentChangeEvent(text);
            }
            return new DidChangeTextDocumentParams(id, Collections.singletonList(change));
        }

    }

    public static final class Initialize {
        private final List<WorkspaceFolder> workspaceFolders = new ArrayList<>();
        private Object initializationOptions;

        public Initialize workspaceFolder(String uri, String name) {
            this.workspaceFolders.add(new WorkspaceFolder(uri, name));
            return this;
        }

        public Initialize initializationOptions(Object object) {
            this.initializationOptions = object;
            return this;
        }

        public InitializeParams build() {
            InitializeParams params = new InitializeParams();
            params.setCapabilities(new ClientCapabilities()); // non-null
            params.setWorkspaceFolders(workspaceFolders);
            if (initializationOptions != null) {
                params.setInitializationOptions(initializationOptions);
            }
            return params;
        }
    }

    public static final class DidClose {
        private String uri;

        public DidClose uri(String uri) {
            this.uri = uri;
            return this;
        }

        public DidCloseTextDocumentParams build() {
            return new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri));
        }
    }

    public static final class DidOpen {
        private String uri;
        private String languageId = "smithy";
        private int version = 1;
        private String text;

        public DidOpen uri(String uri) {
            this.uri = uri;
            return this;
        }

        public DidOpen languageId(String languageId) {
            this.languageId = languageId;
            return this;
        }

        public DidOpen version(int version) {
            this.version = version;
            return this;
        }

        public DidOpen text(String text) {
            this.text = text;
            return this;
        }

        public DidOpenTextDocumentParams build() {
            if (text == null) {
                text = IoUtils.readUtf8File(Paths.get(URI.create(uri)));
            }
            return new DidOpenTextDocumentParams(new TextDocumentItem(uri, languageId, version, text));
        }
    }

    public static final class DidSave {
        private String uri;

        public DidSave uri(String uri) {
            this.uri = uri;
            return this;
        }

        public DidSaveTextDocumentParams build() {
            return new DidSaveTextDocumentParams(new TextDocumentIdentifier(uri));
        }
    }

    public static final class PositionRequest {
        private String uri;
        private int line;
        private int character;

        public PositionRequest uri(String uri) {
            this.uri = uri;
            return this;
        }

        public PositionRequest line(int line) {
            this.line = line;
            return this;
        }

        public PositionRequest character(int character) {
            this.character = character;
            return this;
        }

        public PositionRequest position(Position position) {
            this.line = position.getLine();
            this.character = position.getCharacter();
            return this;
        }

        public HoverParams buildHover() {
            return new HoverParams(new TextDocumentIdentifier(uri), new Position(line, character));
        }

        public DefinitionParams buildDefinition() {
            return new DefinitionParams(new TextDocumentIdentifier(uri), new Position(line, character));
        }

        public CompletionParams buildCompletion() {
            return new CompletionParams(
                    new TextDocumentIdentifier(uri),
                    new Position(line, character),
                    new CompletionContext(CompletionTriggerKind.Invoked));
        }

        public ReferenceParams buildReference() {
            return new ReferenceParams(
                    new TextDocumentIdentifier(uri),
                    new Position(line, character),
                    new ReferenceContext(true)
            );
        }

        public RenameParams buildRename(String newName) {
            return new RenameParams(
                    new TextDocumentIdentifier(uri),
                    new Position(line, character),
                    newName
            );
        }

        public PrepareRenameParams buildPrepareRename() {
            return new PrepareRenameParams(
                    new TextDocumentIdentifier(uri),
                    new Position(line, character)
            );
        }
    }

    public static final class DidChangeWatchedFiles {
        private final List<FileEvent> changes = new ArrayList<>();

        public DidChangeWatchedFiles event(String uri, FileChangeType type) {
            this.changes.add(new FileEvent(uri, type));
            return this;
        }

        public DidChangeWatchedFilesParams build() {
            return new DidChangeWatchedFilesParams(changes);
        }
    }

    public static final class DidChangeWorkspaceFolders {
        private final List<WorkspaceFolder> added = new ArrayList<>();
        private final List<WorkspaceFolder> removed = new ArrayList<>();

        public DidChangeWorkspaceFolders added(String uri, String name) {
            this.added.add(new WorkspaceFolder(uri, name));
            return this;
        }

        public DidChangeWorkspaceFolders removed(String uri, String name) {
            this.removed.add(new WorkspaceFolder(uri, name));
            return this;
        }

        public DidChangeWorkspaceFoldersParams build() {
            return new DidChangeWorkspaceFoldersParams(
                    new WorkspaceFoldersChangeEvent(added, removed));
        }
    }
}
