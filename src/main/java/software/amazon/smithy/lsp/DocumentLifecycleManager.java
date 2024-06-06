/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Tracks asynchronous lifecycle tasks. Allows cancelling of an ongoing task
 * if a new task needs to be started
 */
final class DocumentLifecycleManager {
    private final Map<String, CompletableFuture<Void>> tasks = new HashMap<>();

    DocumentLifecycleManager() {
    }

    CompletableFuture<Void> getTask(String uri) {
        return tasks.get(uri);
    }

    void cancelTask(String uri) {
        if (tasks.containsKey(uri)) {
            CompletableFuture<Void> task = tasks.get(uri);
            if (!task.isDone() && !task.isCancelled()) {
                task.cancel(true);
            }
        }
    }

    void putTask(String uri, CompletableFuture<Void> future) {
        tasks.put(uri, future);
    }

    void cancelAllTasks() {
        for (CompletableFuture<Void> task : tasks.values()) {
            task.cancel(true);
        }
    }

    void waitForAllTasks() throws ExecutionException, InterruptedException {
        for (CompletableFuture<Void> task : tasks.values()) {
            task.get();
        }
    }
}
