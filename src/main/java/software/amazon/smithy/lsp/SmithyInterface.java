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

package software.amazon.smithy.lsp;

import java.io.File;
import java.util.Collection;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.validation.ValidatedResult;

public final class SmithyInterface {
    private SmithyInterface() {

    }

    /**
     * Reads the model in a specified file, adding external jars to model builder.
     *
     * @param files        list of smithy files
     * @param externalJars set of external jars
     * @return either an exception encountered during model building, or the result
     *         of model building
     */
    public static Either<Exception, ValidatedResult<Model>> readModel(Collection<File> files,
            Collection<File> externalJars) {

        try {
            ModelAssembler assembler = Model.assembler().discoverModels();

            for (File jar : externalJars) {
                assembler = assembler.addImport(jar.getAbsolutePath());
            }

            for (File file : files) {
                assembler = assembler.addImport(file.getAbsolutePath());
            }

            return Either.forRight(assembler.assemble());
        } catch (Exception e) {
            return Either.forLeft(e);
        }
    }
}
