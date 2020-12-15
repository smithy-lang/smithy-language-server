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

import org.eclipse.lsp4j.jsonrpc.messages.Either;

import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.validation.ValidatedResult;

public final class SmithyInterface {
  private SmithyInterface() {

  }

  /**
   * @param path File of single-file model.
   * @return Returns either an Exception, or the ValidatedResult of a model.
   */
  public static Either<Exception, ValidatedResult<Model>> readModel(File path) {

    try {
      return Either.forRight(Model.assembler().discoverModels().addImport(path.getAbsolutePath()).assemble());
    } catch (Exception e) {
      return Either.forLeft(e);
    }
  }
}
