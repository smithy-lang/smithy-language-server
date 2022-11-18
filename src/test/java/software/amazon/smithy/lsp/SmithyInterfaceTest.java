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

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.validation.ValidatedResult;

import static org.junit.Assert.*;

public class SmithyInterfaceTest {
    @Test
    public void reloadingModelWithDependencies() throws Exception {
        Path baseDir = Paths.get(SmithyInterface.class.getResource("traits").toURI());
        Path model = baseDir.resolve("test-traits.smithy");
        Path dependencyJar = baseDir.resolve("smithy-test-traits.jar");
        List<File> files = Collections.singletonList(model.toFile());
        List<File> externalJars = Collections.singletonList(dependencyJar.toFile());
        Either<Exception, ValidatedResult<Model>> result = SmithyInterface.readModel(files, externalJars);
        Either<Exception, ValidatedResult<Model>> resultTwo = SmithyInterface.readModel(files, externalJars);
        assertTrue(result.isRight());
        assertTrue(resultTwo.isRight());
    }

    @Test
    public void runValidators() throws Exception {
        Path baseDir = Paths.get(SmithyInterface.class.getResource("traits").toURI());
        Path model = baseDir.resolve("validate-traits.smithy");
        Path dependencyJar = baseDir.resolve("alloy-core.jar");
        List<File> files = Collections.singletonList(model.toFile());
        List<File> externalJars = Collections.singletonList(dependencyJar.toFile());
        Either<Exception, ValidatedResult<Model>> result = SmithyInterface.readModel(files, externalJars);
        assertTrue(result.isRight());
        assertEquals(
            result.getRight().getValidationEvents().get(0).getMessage(),
            "Proto index 1 is used muliple times in members name,age of shape (structure: `some.test#MyStruct`)."
        );
    }
}
