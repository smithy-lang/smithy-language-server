/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.handler;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.FileSystemWatcher;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.Unregistration;
import org.eclipse.lsp4j.WatchKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectFilePatterns;

/**
 * Handles computing the {@link Registration}s and {@link Unregistration}s for
 * that tell the client which files and directories to watch for changes
 *
 * <p>The server needs to know when files are added or removed from the project's
 * sources or imports. Instead of watching the client's file system, we tell the
 * client to send us notifications when these events occur, so we can reload the
 * project.
 *
 * <p>Clients don't de-duplicate file watchers, so we have to unregister all
 * file watchers before sending a new list to watch, or keep track of them to make
 * more granular changes. The current behavior is to just unregister and re-register
 * everything, since these events should be rarer. But we can optimize it in the
 * future.
 */
public final class FileWatcherRegistrationHandler {
    private static final Integer SMITHY_WATCH_FILE_KIND = WatchKind.Delete | WatchKind.Create;
    private static final String WATCH_BUILD_FILES_ID = "WatchSmithyBuildFiles";
    private static final String WATCH_SMITHY_FILES_ID = "WatchSmithyFiles";
    private static final String WATCH_FILES_METHOD = "workspace/didChangeWatchedFiles";
    private static final List<Unregistration> SMITHY_FILE_WATCHER_UNREGISTRATIONS = List.of(new Unregistration(
            WATCH_SMITHY_FILES_ID,
            WATCH_FILES_METHOD));

    private FileWatcherRegistrationHandler() {
    }

    /**
     * @param projects The projects to get registrations for
     * @return The registrations to watch for Smithy file changes across all projects
     */
    public static List<Registration> getSmithyFileWatcherRegistrations(Collection<Project> projects) {
        List<FileSystemWatcher> smithyFileWatchers = projects.stream()
                .flatMap(project -> ProjectFilePatterns.getSmithyFileWatchPatterns(project).stream())
                .map(pattern -> new FileSystemWatcher(Either.forLeft(pattern), SMITHY_WATCH_FILE_KIND))
                .toList();

        return Collections.singletonList(new Registration(
                WATCH_SMITHY_FILES_ID,
                WATCH_FILES_METHOD,
                new DidChangeWatchedFilesRegistrationOptions(smithyFileWatchers)));
    }

    /**
     * @return The unregistrations to stop watching for Smithy file changes
     */
    public static List<Unregistration> getSmithyFileWatcherUnregistrations() {
        return SMITHY_FILE_WATCHER_UNREGISTRATIONS;
    }

    /**
     * @param projects The projects to get registrations for
     * @return The registrations to watch for build file changes across all projects
     */
    public static List<Registration> getBuildFileWatcherRegistrations(Collection<Project> projects) {
        List<FileSystemWatcher> watchers = projects.stream()
                .map(ProjectFilePatterns::getBuildFilesWatchPattern)
                .map(pattern -> new FileSystemWatcher(Either.forLeft(pattern)))
                .toList();

        return Collections.singletonList(new Registration(
                WATCH_BUILD_FILES_ID,
                WATCH_FILES_METHOD,
                new DidChangeWatchedFilesRegistrationOptions(watchers)));
    }
}
