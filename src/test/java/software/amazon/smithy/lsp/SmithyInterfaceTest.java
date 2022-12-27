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

package software.amazon.smithy.lsp;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.model.validation.ValidationEvent;

public class SmithyInterfaceTest {
    private static final String baseDirName = "external-jars";
    private static final String testTraitsModelFilename = "test-traits.smithy";
    private static final String testTraitsDependencyFilename = "smithy-test-traits.jar";
    private static final ShapeId testTraitShapeId = ShapeId.from("smithy.test#test");

    @Test
    public void loadModelWithDependencies() throws Exception {
        List<File> modelFiles = getFiles(testTraitsModelFilename);
        List<File> externalJars = getFiles(testTraitsDependencyFilename);

        Either<Exception, ValidatedResult<Model>> result = SmithyInterface.readModel(modelFiles, externalJars);

        assertTrue(result.isRight());
        assertTrue(result.getRight().getValidationEvents().isEmpty());
        Model model = result.getRight().getResult().get();
        assertTrue(model.getShape(testTraitShapeId).isPresent());
    }

    @Test
    public void reloadingModelWithDependencies() throws Exception {
        List<File> modelFiles = getFiles(testTraitsModelFilename);
        List<File> externalJars = getFiles(testTraitsDependencyFilename);

        Either<Exception, ValidatedResult<Model>> result = SmithyInterface.readModel(modelFiles, externalJars);
        Either<Exception, ValidatedResult<Model>> resultTwo = SmithyInterface.readModel(modelFiles, externalJars);

        assertTrue(result.isRight());
        assertTrue(result.getRight().getValidationEvents().isEmpty());
        assertTrue(resultTwo.isRight());
        assertTrue(resultTwo.getRight().getValidationEvents().isEmpty());
    }

    @Test
    public void addingDependency() throws Exception {
        List<File> modelFiles = getFiles(testTraitsModelFilename);
        List<File> noExternalJars = new ArrayList<>();
        List<File> externalJars = getFiles(testTraitsDependencyFilename);

        Either<Exception, ValidatedResult<Model>> noDependency = SmithyInterface.readModel(modelFiles, noExternalJars);
        Either<Exception, ValidatedResult<Model>> withDependency = SmithyInterface.readModel(modelFiles, externalJars);

        assertTrue(noDependency.isRight());
        assertTrue(withDependency.isRight());
        Model modelWithDependency = withDependency.getRight().getResult().get();
        assertTrue(modelWithDependency.getShape(testTraitShapeId).isPresent());
    }

    @Test
    public void removingDependency() throws Exception {
        List<File> modelFiles = getFiles(testTraitsModelFilename);
        List<File> externalJars = getFiles(testTraitsDependencyFilename);
        List<File> noExternalJars = new ArrayList<>();

        Either<Exception, ValidatedResult<Model>> withDependency = SmithyInterface.readModel(modelFiles, externalJars);
        Either<Exception, ValidatedResult<Model>> noDependency = SmithyInterface.readModel(modelFiles, noExternalJars);

        assertTrue(withDependency.isRight());
        assertTrue(noDependency.isRight());
        Model modelWithoutDependency = noDependency.getRight().getResult().get();
        assertFalse(modelWithoutDependency.getShape(testTraitShapeId).isPresent());
    }

    @Test
    public void runValidators() throws Exception {
        List<File> modelFiles = getFiles("test-validators.smithy");
        List<File> externalJars = getFiles("alloy-core.jar");
        Either<Exception, ValidatedResult<Model>> result = SmithyInterface.readModel(modelFiles, externalJars);

        assertTrue(result.isRight());
        List<ValidationEvent> validationEvents = result.getRight().getValidationEvents();
        assertFalse(validationEvents.isEmpty());
        assertEquals(
            "Proto index 1 is used muliple times in members name,age of shape (structure: `some.test#MyStruct`).",
                validationEvents.get(0).getMessage()
        );
    }

    private static List<File> getFiles(String... filenames) throws Exception {
        Path baseDir = Paths.get(SmithyInterface.class.getResource(SmithyInterfaceTest.baseDirName).toURI());
        return Arrays.stream(filenames).map(baseDir::resolve).map(Path::toFile).collect(Collectors.toList());
    }
}
