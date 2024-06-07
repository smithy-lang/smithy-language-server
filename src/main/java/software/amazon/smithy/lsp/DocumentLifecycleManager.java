/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

/**
 * Tracks asynchronous lifecycle tasks and client-managed documents.
 * Allows cancelling of an ongoing task if a new task needs to be started.
 */
final class DocumentLifecycleManager {
    private static final Logger LOGGER = Logger.getLogger(DocumentLifecycleManager.class.getName());
    private final Map<String, CompletableFuture<Void>> tasks = new HashMap<>();
    private final Set<String> managedDocumentUris = new HashSet<>();

    DocumentLifecycleManager() {
    }

    Set<String> getManagedDocuments() {
        return managedDocumentUris;
    }

    boolean isManaged(String uri) {
        return getManagedDocuments().contains(uri);
    }

    CompletableFuture<Void> getTask(String uri) {
        return tasks.get(uri);
    }

    void cancelTask(String uri) {
        if (tasks.containsKey(uri)) {
            CompletableFuture<Void> task = tasks.get(uri);
            if (!task.isDone()) {
                task.cancel(true);
            }
        }
    }

    void putTask(String uri, CompletableFuture<Void> future) {
        tasks.put(uri, future);
    }

    void putOrComposeTask(String uri, CompletableFuture<Void> future) {
        if (tasks.containsKey(uri)) {
            tasks.computeIfPresent(uri, (k, v) -> v.thenCompose((unused) -> future));
        } else {
            tasks.put(uri, future);
        }
    }

    void cancelAllTasks() {
        for (CompletableFuture<Void> task : tasks.values()) {
            task.cancel(true);
        }
    }

    void waitForAllTasks() throws ExecutionException, InterruptedException {
        for (CompletableFuture<Void> task : tasks.values()) {
            if (!task.isDone()) {
                task.get();
            }
        }
    }
}
