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

package software.amazon.smithy.lsp.ext;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import software.amazon.smithy.lsp.SmithyInterface;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.validation.ValidatedResult;

public final class SmithyProject {
    private final List<Path> imports;
    private final List<File> smithyFiles;
    private final List<File> externalJars;
    private Map<String, List<Location>> locations = Collections.emptyMap();
    private ValidatedResult<Model> model;
    private final File root;

    private SmithyProject(List<Path> imports, List<File> smithyFiles, List<File> externalJars, File root,
            ValidatedResult<Model> model) {
        this.imports = imports;
        this.root = root;
        this.model = model;
        this.smithyFiles = smithyFiles;
        this.externalJars = externalJars;
        model.getResult().ifPresent(m -> {
            this.locations = collectLocations(m);
        });
    }

    /**
     * Recompile the model, adding a file to list of tracked files, potentially
     * excluding some other file.
     * <p>
     * This version of the method above is used when the
     * file is in ephemeral storage (temporary location when file is being changed)
     *
     * @param changed file which may or may not be already tracked by this project
     * @return either an error, or a loaded project
     */
    public Either<Exception, SmithyProject> recompile(File changed, File exclude) {
        List<File> newFiles = new ArrayList<File>();

        for (File existing : onlyExistingFiles(this.smithyFiles)) {
            if (exclude != null && !existing.equals(exclude)) {
                newFiles.add(existing);
            }
        }

        if (changed.isFile()) {
            newFiles.add(changed);
        }

        return load(this.imports, newFiles, this.externalJars, this.root);
    }

    public ValidatedResult<Model> getModel() {
        return this.model;
    }

    public List<File> getExternalJars() {
        return this.externalJars;
    }

    public List<File> getSmithyFiles() {
        return this.smithyFiles;
    }

    public List<CompletionItem> getCompletions(String token) {
        return this.model.getResult().map(model -> Completions.find(model, token)).orElse(Collections.emptyList());
    }

    public Map<String, List<Location>> getLocations() {
        return this.locations;
    }

    public Either<Exception, SmithyProject> reload(SmithyBuildExtensions config) {
        return load(config, this.root);
    }

    /**
     * Load the project using a SmithyBuildExtensions configuration and workspace
     * root.
     *
     * @param config configuration
     * @param root   workspace root
     * @return either an error or a loaded project
     */
    public static Either<Exception, SmithyProject> load(SmithyBuildExtensions config, File root) {
        List<Path> imports = config.getImports().stream().map(p -> Paths.get(root.getAbsolutePath(), p).normalize())
                .collect(Collectors.toList());

        if (imports.isEmpty()) {
            imports.add(root.toPath());
        }

        LspLog.println("Imports from config: " + imports + " will be resolved against root " + root);

        List<File> smithyFiles = discoverSmithyFiles(imports, root);
        LspLog.println("Discovered smithy files: " + smithyFiles);

        List<File> externalJars = downloadExternalDependencies(config);
        LspLog.println("Downloaded external jars: " + externalJars);

        return load(imports, smithyFiles, externalJars, root);

    }

    private static Either<Exception, SmithyProject> load(List<Path> imports, List<File> smithyFiles,
            List<File> externalJars, File root) {
        Either<Exception, ValidatedResult<Model>> model = createModel(smithyFiles, externalJars);

        if (model.isLeft()) {
            return Either.forLeft(model.getLeft());
        } else {
            model.getRight().getValidationEvents().forEach(event -> LspLog.println(event));
            return Either.forRight(new SmithyProject(imports, smithyFiles, externalJars, root, model.getRight()));
        }
    }

    private static Either<Exception, ValidatedResult<Model>> createModel(List<File> discoveredFiles,
            List<File> externalJars) {
        return SmithyInterface.readModel(discoveredFiles, externalJars);
    }

    private static Map<String, List<Location>> collectLocations(Model model) {
        Map<String, List<Location>> locations = new HashMap<>();
        model.shapes().forEach(shape -> {
            SourceLocation sourceLocation = shape.getSourceLocation();
            String uri = sourceLocation.getFilename();
            if (uri.startsWith("jar:file:")) {
                uri = "smithyjar:" + uri.substring(9);
            } else if (!uri.startsWith("file:")) {
                uri = "file:" + uri;
            }
            Position pos = new Position(sourceLocation.getLine() - 1, sourceLocation.getColumn() - 1);
            Location location = new Location(uri, new Range(pos, pos));

            String shapeName = shape.getId().getName();
            // Members get the same shapeName as their parent structure
            // so we ignore them, to avoil producing a location per-member
            // TODO: index members somehow as well?
            if (shape.getType() != ShapeType.MEMBER) {
                if (locations.containsKey(shapeName)) {
                    locations.get(shapeName).add(location);
                } else {
                    List<Location> locList = new ArrayList<Location>();
                    locList.add(location);
                    locations.put(shapeName, locList);
                }
            }
        });

        return locations;
    }

    private static Boolean isValidSmithyFile(Path file) {
        String fName = file.getFileName().toString();
        return fName.endsWith(Constants.SMITHY_EXTENSION);
    }

    private static List<File> walkSmithyFolder(Path path, File root) {

        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile).filter(SmithyProject::isValidSmithyFile).map(Path::toFile)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LspLog.println("Failed to walk import '" + path + "' from root " + root + ": " + e);
            return new ArrayList<>();
        }
    }

    private static List<File> discoverSmithyFiles(List<Path> imports, File root) {
        List<File> smithyFiles = new ArrayList<>();

        imports.forEach(path -> {
            if (Files.isDirectory(path)) {
                smithyFiles.addAll(walkSmithyFolder(path, root));
            } else if (isValidSmithyFile(path)) {
                smithyFiles.add(path.resolve(root.toPath()).toFile());
            }
        });
        return smithyFiles;
    }

    private static List<File> downloadExternalDependencies(SmithyBuildExtensions ext) {
        try {
            return DependencyDownloader.create(ext).download();
        } catch (Exception e) {
            LspLog.println("Failed to download external jars for " + ext + ": " + e);
            return Collections.emptyList();
        }
    }

    private static List<File> onlyExistingFiles(Collection<File> files) {
        return files.stream().filter(File::isFile).collect(Collectors.toList());
    }
}
