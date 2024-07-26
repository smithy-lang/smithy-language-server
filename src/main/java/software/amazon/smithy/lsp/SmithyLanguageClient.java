/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.LogTraceParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ShowDocumentParams;
import org.eclipse.lsp4j.ShowDocumentResult;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Wrapper around a delegate {@link LanguageClient} that provides convenience
 * methods and/or Smithy-specific language client features.
 */
public final class SmithyLanguageClient implements LanguageClient {
    private final LanguageClient delegate;

    SmithyLanguageClient(LanguageClient delegate) {
        this.delegate = delegate;
    }

    /**
     * Log a {@link MessageType#Info} message on the client.
     *
     * @param message Message to log
     */
    public void info(String message) {
        delegate.logMessage(new MessageParams(MessageType.Info, message));
    }

    /**
     * Log a {@link MessageType#Error} message on the client.
     *
     * @param message Message to log
     */
    public void error(String message) {
        delegate.logMessage(new MessageParams(MessageType.Error, message));
    }

    /**
     * Log a {@link MessageType#Error} message on the client, specifically for
     * situations where a file is requested but isn't known to the server.
     *
     * @param uri LSP URI of the file that was requested.
     * @param source Reason for requesting the file.
     */
    public void unknownFileError(String uri, String source) {
        delegate.logMessage(new MessageParams(
                MessageType.Error, "attempted to get file for " + source + " that isn't tracked: " + uri));
    }

    @Override
    public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
        return delegate.applyEdit(params);
    }

    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
        return delegate.registerCapability(params);
    }

    @Override
    public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
        return delegate.unregisterCapability(params);
    }

    @Override
    public void telemetryEvent(Object object) {
        delegate.telemetryEvent(object);
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        delegate.publishDiagnostics(diagnostics);
    }

    @Override
    public void showMessage(MessageParams messageParams) {
        delegate.showMessage(messageParams);
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        return delegate.showMessageRequest(requestParams);
    }

    @Override
    public CompletableFuture<ShowDocumentResult> showDocument(ShowDocumentParams params) {
        return delegate.showDocument(params);
    }

    @Override
    public void logMessage(MessageParams message) {
        delegate.logMessage(message);
    }

    @Override
    public CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
        return delegate.workspaceFolders();
    }

    @Override
    public CompletableFuture<List<Object>> configuration(ConfigurationParams configurationParams) {
        return delegate.configuration(configurationParams);
    }

    @Override
    public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
        return delegate.createProgress(params);
    }

    @Override
    public void notifyProgress(ProgressParams params) {
        delegate.notifyProgress(params);
    }

    @Override
    public void logTrace(LogTraceParams params) {
        delegate.logTrace(params);
    }

    @Override
    public CompletableFuture<Void> refreshSemanticTokens() {
        return delegate.refreshSemanticTokens();
    }

    @Override
    public CompletableFuture<Void> refreshCodeLenses() {
        return delegate.refreshCodeLenses();
    }

    @Override
    public CompletableFuture<Void> refreshInlayHints() {
        return delegate.refreshInlayHints();
    }

    @Override
    public CompletableFuture<Void> refreshInlineValues() {
        return delegate.refreshInlineValues();
    }

    @Override
    public CompletableFuture<Void> refreshDiagnostics() {
        return delegate.refreshDiagnostics();
    }
}
