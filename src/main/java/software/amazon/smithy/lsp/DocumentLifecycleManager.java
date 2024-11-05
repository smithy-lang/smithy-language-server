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
 * Tracks asynchronous lifecycle tasks, allowing for cancellation of an ongoing
 * task if a new task needs to be started.
 */
final class DocumentLifecycleManager {
    private final Map<String, CompletableFuture<Void>> tasks = new HashMap<>();

    CompletableFuture<Void> getTask(String uri) {
        return tasks.get(uri);
    }

    void cancelTask(String uri) {
        if (tasks.containsKey(uri)) {
            CompletableFuture<Void> task = tasks.get(uri);
            if (!task.isDone()) {
                task.cancel(true);
                tasks.remove(uri);
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
        tasks.clear();
    }

    void waitForAllTasks() throws ExecutionException, InterruptedException {
        for (CompletableFuture<Void> task : tasks.values()) {
            if (!task.isDone()) {
                task.get();
            }
        }
    }
}
