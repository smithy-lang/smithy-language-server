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
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.validation.ValidatedResult;

public final class SmithyInterface {
  private SmithyInterface() {

  }

  /**
   * @param path File of single-file model.
   * @param workspaceRoot Workspace
   * @return Returns either an Exception, or the ValidatedResult of a model.
   */
  public static Either<Exception, ValidatedResult<Model>> readModel(File path, File workspaceRoot) {
    try {
      ModelAssembler assembler = Model.assembler();
      assembler.discoverModels();
      // If the workspace root has been set, get all the Smithy model files available.
      if (workspaceRoot != null) {
        List<File> modelFiles = getModelFiles(workspaceRoot);
        modelFiles.forEach(f -> assembler.addImport(f.getAbsolutePath()));
      } else {
        // Fall back to the single model file.
        assembler.addImport(path.getAbsolutePath());
      }
      return Either.forRight(assembler.assemble());
    } catch (Exception e) {
      return Either.forLeft(e);
    }
  }

  private static List<File> getModelFiles(File root) {
    Collection<File> smithyModelFiles = FileUtils.listFiles(root, new String[]{"smithy"}, true);
    // Filter out any any model files that have been generated during a previous build.
    return smithyModelFiles.stream().filter(f -> !f.getAbsolutePath().contains(root.getAbsolutePath() + "/build"))
            .collect(Collectors.toList());
  }
}
