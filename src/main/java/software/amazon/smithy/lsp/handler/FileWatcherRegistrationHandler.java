/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.handler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.FileSystemWatcher;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.Unregistration;
import org.eclipse.lsp4j.WatchKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectConfigLoader;

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
    private static final List<Registration> BUILD_FILE_WATCHER_REGISTRATIONS;
    private static final List<Unregistration> SMITHY_FILE_WATCHER_UNREGISTRATIONS;

    static {
        // smithy-build.json + .smithy-project.json + build exts
        int buildFileWatcherCount = 2 + ProjectConfigLoader.SMITHY_BUILD_EXTS.length;
        List<FileSystemWatcher> buildFileWatchers = new ArrayList<>(buildFileWatcherCount);
        buildFileWatchers.add(new FileSystemWatcher(Either.forLeft(ProjectConfigLoader.SMITHY_BUILD)));
        buildFileWatchers.add(new FileSystemWatcher(Either.forLeft(ProjectConfigLoader.SMITHY_PROJECT)));
        for (String ext : ProjectConfigLoader.SMITHY_BUILD_EXTS) {
            buildFileWatchers.add(new FileSystemWatcher(Either.forLeft(ext)));
        }

        BUILD_FILE_WATCHER_REGISTRATIONS = Collections.singletonList(new Registration(
                WATCH_BUILD_FILES_ID,
                WATCH_FILES_METHOD,
                new DidChangeWatchedFilesRegistrationOptions(buildFileWatchers)));

        SMITHY_FILE_WATCHER_UNREGISTRATIONS = Collections.singletonList(new Unregistration(
                WATCH_SMITHY_FILES_ID,
                WATCH_FILES_METHOD));
    }

    private FileWatcherRegistrationHandler() {
    }

    /**
     * @return The registrations to watch for build file changes
     */
    public static List<Registration> getBuildFileWatcherRegistrations() {
        return BUILD_FILE_WATCHER_REGISTRATIONS;
    }

    /**
     * @param project The Project to get registrations for
     * @return The registrations to watch for Smithy file changes
     */
    public static List<Registration> getSmithyFileWatcherRegistrations(Project project) {
        List<FileSystemWatcher> smithyFileWatchers = Stream.concat(project.sources().stream(),
                        project.imports().stream())
                .map(FileWatcherRegistrationHandler::smithyFileWatcher)
                .collect(Collectors.toList());

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

    private static FileSystemWatcher smithyFileWatcher(Path path) {
        String glob = path.toString();
        if (!glob.endsWith(".smithy") && !glob.endsWith(".json")) {
            // we have a directory
            if (glob.endsWith("/")) {
                glob = glob + "**/*.{smithy,json}";
            } else {
                glob = glob + "/**/*.{smithy,json}";
            }
        }
        // Watch the absolute path, either a directory or file
        return new FileSystemWatcher(Either.forLeft(glob), SMITHY_WATCH_FILE_KIND);
    }
}
