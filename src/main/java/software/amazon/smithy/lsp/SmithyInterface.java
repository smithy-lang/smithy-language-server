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
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.Map;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import software.amazon.smithy.lsp.ext.LspLog;
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
      ModelAssembler assembler = getModelAssembler(externalJars);

      for (File file : files) {
        assembler.addImport(file.getAbsolutePath());
      }

      return Either.forRight(assembler.assemble());
    } catch (Exception e) {
      LspLog.println(e);
      return Either.forLeft(e);
    }
  }

  /**
   * Reads in the specified model files, adding external jars to model builder.
   *
   * @param sources Map of model file URIs to their contents
   * @param externalJars set of external jars
   * @return either an exception encountered during model building, or the result
   *         of model building
   */
  public static Either<Exception, ValidatedResult<Model>> readModel(
          Map<String, String> sources,
          Collection<File> externalJars
  ) {
    try {
      ModelAssembler assembler = getModelAssembler(externalJars);
      for (String uri : sources.keySet()) {
        assembler.addUnparsedModel(uri, sources.get(uri));
      }
      return Either.forRight(assembler.assemble());
    } catch (Exception e) {
      LspLog.println(e);
      return Either.forLeft(e);
    }
  }

  private static ModelAssembler getModelAssembler(Collection<File> externalJars) {
    URL[] urls = externalJars.stream().map(SmithyInterface::fileToUrl).toArray(URL[]::new);
    URLClassLoader urlClassLoader = new URLClassLoader(urls);
    return Model.assembler(urlClassLoader)
            .discoverModels(urlClassLoader)
            // We don't want the model to be broken when there are unknown traits,
            // because that will essentially disable language server features.
            .putProperty(ModelAssembler.ALLOW_UNKNOWN_TRAITS, true);
  }

  private static URL fileToUrl(File file) {
    try {
      return file.toURI().toURL();
    } catch (MalformedURLException e) {
      throw new RuntimeException("Failed to get file's URL", e);
    }
  }
}
