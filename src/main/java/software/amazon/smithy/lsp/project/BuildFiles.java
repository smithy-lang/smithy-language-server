/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import software.amazon.smithy.lsp.ManagedFiles;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.utils.IoUtils;

/**
 * Immutable container for multiple {@link BuildFile}s, with accessors by path
 * and {@link BuildFileType}.
 */
final class BuildFiles implements Iterable<BuildFile> {
    private final Map<String, BuildFile> buildFiles;

    private BuildFiles(Map<String, BuildFile> buildFiles) {
        this.buildFiles = buildFiles;
    }

    @Override
    public Iterator<BuildFile> iterator() {
        return buildFiles.values().iterator();
    }

    BuildFile getByPath(String path) {
        return buildFiles.get(path);
    }

    BuildFile getByType(BuildFileType type) {
        for (BuildFile buildFile : buildFiles.values()) {
            if (buildFile.type() == type) {
                return buildFile;
            }
        }
        return null;
    }

    boolean isEmpty() {
        return buildFiles.isEmpty();
    }

    static BuildFiles of(Collection<BuildFile> buildFiles) {
        Map<String, BuildFile> buildFileMap = new HashMap<>(buildFiles.size());
        for (BuildFile buildFile : buildFiles) {
            buildFileMap.put(buildFile.path(), buildFile);
        }
        return new BuildFiles(buildFileMap);
    }

    static BuildFiles load(Path root, ManagedFiles managedFiles) {
        Map<String, BuildFile> buildFiles = new HashMap<>(BuildFileType.values().length);
        for (BuildFileType type : BuildFileType.values()) {
            BuildFile buildFile = readBuildFile(type, root, managedFiles);
            if (buildFile != null) {
                buildFiles.put(buildFile.path(), buildFile);
            }
        }
        return new BuildFiles(buildFiles);
    }

    private static BuildFile readBuildFile(
            BuildFileType type,
            Path workspaceRoot,
            ManagedFiles managedFiles
    ) {
        Path buildFilePath = workspaceRoot.resolve(type.filename());
        if (!Files.isRegularFile(buildFilePath)) {
            return null;
        }

        String pathString = buildFilePath.toString();
        String uri = LspAdapter.toUri(pathString);
        Document document = managedFiles.getManagedDocument(uri);
        if (document == null) {
            // Note: This shouldn't fail since we checked for the file's existence
            document = Document.of(IoUtils.readUtf8File(pathString));
        }

        return BuildFile.create(pathString, document, type);
    }
}
