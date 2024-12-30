/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import software.amazon.smithy.lsp.project.ProjectFile;

/**
 * Todo: Implement a data structure that can:
 *  - keep track of opened files
 *  - handle creating the right type of ProjectFile
 *  - close files
 *  - cache the text of these files to be used when loading models
 *
 *  Notes:
 *   - One idea I have is to make it cache all the project files on load.
 *     Right now, ProjectLoader reads from disk every time. This isn't too
 *     bad, because project changes aren't _that_ frequent, but we end up
 *     reading the files _again_ to create ProjectFiles for everything that
 *     was loaded.
 *      - My concern here is that when there are changes on the filesystem,
 *        we need to make sure we always stay in sync. Right now, we avoid
 *        this by just reading from disk every time we do a full project
 *        reload.
 *   - Another idea is to just cache managed files, and then just create
 *     ProjectFiles as they're being added to the ModelAssembler.
 *      - We still have to handle files in jars though, and I'm _not_
 *        re-implementing ModelDiscovery.
 *   - Yet another idea would be to lazily read these files from disk. This
 *     avoids most implementation complications.
 *      - But there's a design problem - when we want to support refactoring,
 *        we will need to read everything from disk + parse it anyways.
 */
public final class FileCache {
    private final ConcurrentMap<String, ProjectFile> managedProjectFiles = new ConcurrentHashMap<>();
}
