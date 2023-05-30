/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import software.amazon.smithy.lsp.Utils;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.InputTrait;
import software.amazon.smithy.model.traits.OutputTrait;
import software.amazon.smithy.model.traits.Trait;

/**
 * Creates a cache of {@link ModelFile} and uses it to collect the locations of container
 * shapes in all files, then collects their members.
 */
final class FileCachingCollector implements ShapeLocationCollector {

    private Model model;
    private Map<ShapeId, Location> locations;
    private Map<String, ModelFile> fileCache;
    private Map<OperationShape, List<Shape>> operationsWithInlineInputOutputMap;
    private Map<ShapeId, List<MemberShape>> containerMembersMap;
    private Map<ShapeId, ShapeId> membersToUpdateMap;

    @Override
    public Map<ShapeId, Location> collectDefinitionLocations(Model model) {
        this.locations = new HashMap<>();
        this.model = model;
        this.fileCache = createModelFileCache(model);
        this.operationsWithInlineInputOutputMap = new HashMap<>();
        this.containerMembersMap = new HashMap<>();
        this.membersToUpdateMap = new HashMap<>();

        for (ModelFile modelFile : this.fileCache.values()) {
            try {
                collectContainerShapeLocationsInModelFile(modelFile);
            } catch (Exception e) {
                throw new RuntimeException("Exception while collecting container shape locations in model file: "
                        + modelFile.filename, e);
            }
        }

        operationsWithInlineInputOutputMap.forEach((this::collectInlineInputOutputLocations));
        containerMembersMap.forEach(this::collectMemberLocations);
        // Make final pass to set locations for mixed-in member locations that weren't available on first pass.
        membersToUpdateMap.forEach(this::updateElidedMemberLocation);
        return this.locations;
    }

    private static Map<String, ModelFile> createModelFileCache(Model model) {
        Map<String, ModelFile> fileCache = new HashMap<>();
        List<String> modelFilenames = getAllFilenamesFromModel(model);
        for (String filename : modelFilenames) {
            List<Shape> shapes = getReverseSortedShapesInFileFromModel(model, filename);
            List<String> lines = getFileLines(filename);
            DocumentPreamble preamble = Document.detectPreamble(lines);
            ModelFile modelFile = new ModelFile(filename, lines, preamble, shapes);
            fileCache.put(filename, modelFile);
        }
        return fileCache;
    }

    private void collectContainerShapeLocationsInModelFile(ModelFile modelFile) {
        String filename = modelFile.filename;
        int endMarker = getInitialEndMarker(modelFile.lines);

        for (Shape shape : modelFile.shapes) {
            SourceLocation sourceLocation = shape.getSourceLocation();
            Position startPosition = getStartPosition(sourceLocation);
            Position endPosition;
            if (endMarker < sourceLocation.getLine()) {
                endPosition = new Position(sourceLocation.getLine() - 1, sourceLocation.getColumn() - 1);
            } else {
                endPosition = getEndPosition(endMarker, modelFile.lines);
            }
            // If a shape belongs to an operation as an inlined input or output, collect a map of the operation
            // with the reversed ordered list of inputs and outputs within that operation. Once the location of
            // the containing operation has been determined, the map can be revisited to determine the locations of
            // the inlined inputs and outputs.
            Optional<OperationShape> matchingOperation = getOperationForInlinedInputOrOutput(shape, modelFile);

            if (matchingOperation.isPresent()) {
                operationsWithInlineInputOutputMap.computeIfAbsent(matchingOperation.get(), s ->
                        new ArrayList<>()).add(shape);
                // Collect a map of container shapes and a list of member shapes, reverse ordered by source location
                // in the model file. This map will be revisited after the location of the containing shape has been
                // determined since it is needed to determine the locations of each member.
            } else if (shape.isMemberShape()) {
                MemberShape memberShape = shape.asMemberShape().get();
                ShapeId containerId = memberShape.getContainer();
                containerMembersMap.computeIfAbsent(containerId, s -> new ArrayList<>()).add(memberShape);
            } else {
                endMarker = advanceMarkerOnNonMemberShapes(startPosition, shape, modelFile);
                locations.put(shape.getId(), createLocation(filename, startPosition, endPosition));
            }
        }
    }

    // Determine the location of inlined inputs and outputs can be determined using the containing operation.
    private void collectInlineInputOutputLocations(OperationShape operation, List<Shape> shapes) {
        int operationEndMarker = locations.get(operation.getId()).getRange().getEnd().getLine();
        for (Shape shape : shapes) {
            SourceLocation sourceLocation = shape.getSourceLocation();
            ModelFile modelFile = fileCache.get(sourceLocation.getFilename());
            Position startPosition = getStartPosition(sourceLocation);
            Position endPosition = getEndPosition(operationEndMarker, modelFile.lines);
            Location location = createLocation(modelFile.filename, startPosition, endPosition);
            locations.put(shape.getId(), location);
            operationEndMarker = sourceLocation.getLine() - 1;
        }
    }

    private void collectMemberLocations(ShapeId containerId, List<MemberShape> members) {

        Location containerLocation = locations.get(containerId);
        Range containerLocationRange = containerLocation.getRange();
        int memberEndMarker = containerLocationRange.getEnd().getLine();
        // Keep track of previous line to make sure that end marker has been advanced.
        String previousLine = "";
        // The member shapes were reverse ordered by source location when assembling this list, so we can
        // iterate through it as-is to work from bottom to top in the model file.
        for (MemberShape memberShape : members) {
            ModelFile modelFile = fileCache.get(memberShape.getSourceLocation().getFilename());
            int memberShapeSourceLocationLine = memberShape.getSourceLocation().getLine();

            boolean isContainerInAnotherFile = !containerLocation.getUri().equals(getUri(modelFile.filename));
            // If the member's source location is within the container location range, it is being defined
            // or re-defined there.
            boolean isMemberDefinedInContainer =
                    memberShapeSourceLocationLine >= containerLocationRange.getStart().getLine()
                    && memberShapeSourceLocationLine <= containerLocationRange.getEnd().getLine();

            // If the member has mixins, and was not defined within the container, use the mixin source location.
            if (memberShape.getMixins().size() > 0 && !isMemberDefinedInContainer) {
                ShapeId mixinSource = memberShape.getMixins().iterator().next();
                // If the mixin source location has been determined, use its location now.
                if (locations.containsKey(mixinSource)) {
                    locations.put(memberShape.getId(), locations.get(mixinSource));
                // If the mixin source location has not yet been determined, save to re-visit later.
                } else {
                    membersToUpdateMap.put(memberShape.getId(), mixinSource);
                }
            } else if (isContainerInAnotherFile) {
                locations.put(memberShape.getId(), createInheritedMemberLocation(containerLocation));
                // Otherwise, determine the correct location by trimming comments, empty lines and applied traits.
            } else {
                String currentLine = modelFile.lines.get(memberEndMarker - 1).trim();
                while (currentLine.startsWith("//")
                        || currentLine.equals("")
                        || currentLine.equals("}")
                        || currentLine.startsWith("@")
                        || currentLine.equals(previousLine)
                ) {
                    memberEndMarker = memberEndMarker - 1;
                    currentLine = modelFile.lines.get(memberEndMarker - 1).trim();
                }
                Position startPosition = getStartPosition(memberShape.getSourceLocation());
                Position endPosition = getEndPosition(memberEndMarker, modelFile.lines);

                // Advance the member end marker on any non-mixin traits on the current member, so that the next
                // member location end is correct. Mixin traits will have been declared outside the
                // containing shape and shouldn't impact determining the end location of the next member.
                List<Trait> traits = memberShape.getAllTraits().values().stream()
                        .filter(trait -> !trait.getSourceLocation().equals(SourceLocation.NONE))
                        .filter(trait -> trait.getSourceLocation().getFilename().equals(modelFile.filename))
                        .filter(trait -> !isFromMixin(containerLocationRange, trait))
                        .collect(Collectors.toList());

                if (!traits.isEmpty()) {
                    traits.sort(Comparator.comparing(Trait::getSourceLocation));
                    memberEndMarker = traits.get(0).getSourceLocation().getLine();
                }

                locations.put(memberShape.getId(), createLocation(modelFile.filename, startPosition, endPosition));
                previousLine = currentLine;
            }
        }
    }

    // If a mixed-in member is not redefined within its containing structure, set its location to the mixin member.
    private void updateElidedMemberLocation(ShapeId member, ShapeId sourceMember) {
        if (locations.containsKey(sourceMember)) {
            locations.put(member, locations.get(sourceMember));
        }
    }

    // Use an empty range at the container's start since inherited members are not present in the model file.
    private static Location createInheritedMemberLocation(Location containerLocation) {
        Position startPosition = containerLocation.getRange().getStart();
        Range memberRange = new Range(startPosition, startPosition);
        return new Location(containerLocation.getUri(), memberRange);
    }

    // If the trait was defined outside the container, it was mixed in.
    private static boolean isFromMixin(Range containerRange, Trait trait) {
        int traitLocationLine = trait.getSourceLocation().getLine();
        return traitLocationLine < containerRange.getStart().getLine()
                || traitLocationLine > containerRange.getEnd().getLine();
    }

    // Get the operation that matches an inlined input or output structure.
    private Optional<OperationShape> getOperationForInlinedInputOrOutput(Shape shape, ModelFile modelFile) {
        DocumentPreamble preamble = modelFile.preamble;
        if (preamble.getIdlVersion().isPresent()
                && preamble.getIdlVersion().get().startsWith("2")
                && shape.isStructureShape()
                && (shape.hasTrait(OutputTrait.class) || shape.hasTrait(InputTrait.class))
        ) {
            String suffix = getOperationInputOrOutputSuffix(shape, preamble);
            String shapeName = shape.getId().getName();

            String matchingOperationName = shapeName.substring(0, shapeName.length() - suffix.length());
            ShapeId matchingOperationId = ShapeId.fromParts(shape.getId().getNamespace(), matchingOperationName);

            return model.shapes(OperationShape.class)
                    .filter(operationShape -> operationShape.getId().equals(matchingOperationId))
                    .findFirst()
                    .filter(operation -> shapeWasDefinedInline(operation, shape, modelFile));
        }
        return Optional.empty();
    }

    private static String getOperationInputOrOutputSuffix(Shape shape, DocumentPreamble preamble) {
        if (shape.hasTrait(InputTrait.class)) {
            return preamble.getOperationInputSuffix().orElse("Input");
        }
        if (shape.hasTrait(OutputTrait.class)) {
            return preamble.getOperationOutputSuffix().orElse("Output");
        }
        return "";
    }

    // Iterate through lines in reverse order from current shape start, to the beginning of the above shape, or the
    // start of the operation. If the inline structure assignment operator is encountered, the current shape was
    // defined inline. This check eliminates instances where an operation and its input or output matches the inline
    // structure naming convention.
    private Boolean shapeWasDefinedInline(OperationShape operation, Shape shape, ModelFile modelFile) {
        int shapeStartLine = shape.getSourceLocation().getLine();
        int priorShapeLine = 0;
        if (shape.hasTrait(InputTrait.class) && operation.getOutput().isPresent()) {
            Shape output = model.expectShape(operation.getOutputShape().toShapeId());
            if (output.getSourceLocation().getLine() < shape.getSourceLocation().getLine()) {
                priorShapeLine = output.getSourceLocation().getLine();
            }
        }
        if (shape.hasTrait(OutputTrait.class) && operation.getInput().isPresent()) {
            Shape input = model.expectShape(operation.getInputShape().toShapeId());
            if (input.getSourceLocation().getLine() < shape.getSourceLocation().getLine()) {
                priorShapeLine = input.getSourceLocation().getLine();
            }
        }
        int boundary = Math.max(priorShapeLine, operation.getSourceLocation().getLine());
        while (shapeStartLine >= boundary) {
            String line = modelFile.lines.get(shapeStartLine - 1);

            // note: this doesn't take code inside comments into account
            if (line.contains(":=")) {
                return true;
            }
            shapeStartLine--;
        }
        return false;
    }

    private static Location createLocation(String file, Position startPosition, Position endPosition) {
        return new Location(getUri(file), new Range(startPosition, endPosition));
    }

    private static int advanceMarkerOnNonMemberShapes(Position startPosition, Shape shape, ModelFile modelFile) {
        // When handling non-member shapes, advance the end marker for traits and comments above the current
        // shape, ignoring applied traits
        int marker = startPosition.getLine();

        List<Trait> traits = shape.getAllTraits().values().stream()
                .filter(trait -> !trait.getSourceLocation().equals(SourceLocation.NONE))
                .filter(trait -> trait.getSourceLocation().getLine() <= startPosition.getLine())
                .filter(trait -> trait.getSourceLocation().getFilename().equals(modelFile.filename))
                .filter(trait -> !modelFile.lines.get(trait.getSourceLocation().getLine()).trim().startsWith("apply"))
                .collect(Collectors.toList());

        // If the shape has traits, advance the end marker again.
        if (!traits.isEmpty()) {
            traits.sort(Comparator.comparing(Trait::getSourceLocation));
            marker = traits.get(0).getSourceLocation().getLine() - 1;
        }

        // Move the end marker when encountering line comments or empty lines.
        if (modelFile.lines.size() > marker) {
            marker = getNextEndMarker(modelFile.lines, marker);
        }

        return marker;
    }

    private static int getInitialEndMarker(List<String> lines) {
        return getNextEndMarker(lines, lines.size());

    }

    private static int getNextEndMarker(List<String> lines, int currentEndMarker) {
        if (lines.size() == 0) {
            return currentEndMarker;
        }
        int endMarker = currentEndMarker;
        while (endMarker > 0 && shouldIgnoreLine(lines.get(endMarker - 1))) {
            endMarker--;
        }
        return endMarker;
    }

    // Blank lines, comments, and apply statements are ignored because they are unmodeled
    private static boolean shouldIgnoreLine(String line) {
        String trimmed = line.trim();
        return trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("apply");
    }

    private static Position getStartPosition(SourceLocation sourceLocation) {
        return new Position(sourceLocation.getLine() - 1, sourceLocation.getColumn() - 1);
    }

    private static String getUri(String fileName) {
        return Utils.isJarFile(fileName)
                ? Utils.toSmithyJarFile(fileName)
                : addFilePrefix(fileName);
    }

    private static String addFilePrefix(String fileName) {
        return !fileName.startsWith("file:") ? "file:" + fileName : fileName;
    }

    private static List<String> getAllFilenamesFromModel(Model model) {
        return model.shapes()
                .map(shape -> shape.getSourceLocation().getFilename())
                .distinct()
                .collect(Collectors.toList());
    }

    private static List<Shape> getReverseSortedShapesInFileFromModel(Model model, String filename) {
        return model.shapes()
                .filter(shape -> shape.getSourceLocation().getFilename().equals(filename))
                .sorted(Comparator.comparing(Shape::getSourceLocation).reversed())
                .collect(Collectors.toList());
    }

    private static List<String> getFileLines(String file) {
        try {
            if (Utils.isSmithyJarFile(file) || Utils.isJarFile(file)) {
                return Utils.jarFileContents(Utils.toSmithyJarFile(file));
            } else {
                return Files.readAllLines(Paths.get(file));
            }
        } catch (IOException e) {
            LspLog.println("File " + file + " could not be loaded.");
        }
        return Collections.emptyList();
    }

    private static Position getEndPosition(int currentEndMarker, List<String> fileLines) {
        // Skip any blank lines, comments, or apply statements
        int endLine = getNextEndMarker(fileLines, currentEndMarker);

        // Return end position of actual shape line if we have the lines, or set it to the start of the next line
        if (fileLines.size() >= endLine) {
            return new Position(endLine - 1, fileLines.get(endLine - 1).length());
        }
        return new Position(endLine, 0);
    }

    private static final class ModelFile {
        private final String filename;
        private final List<String> lines;
        private final DocumentPreamble preamble;
        private final List<Shape> shapes;

        private ModelFile(String filename, List<String> lines, DocumentPreamble preamble, List<Shape> shapes) {
            this.filename = filename;
            this.lines = lines;
            this.preamble = preamble;
            this.shapes = shapes;
        }
    }

}
