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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

public final class Utils {
  private Utils() {

  }

  /**
   * @param value Value to be used.
   * @param <U>   Type of Value.
   * @return Returns the value of a specific type as a CompletableFuture.
   */
  public static <U> CompletableFuture<U> completableFuture(U value) {
    Supplier<U> supplier = new Supplier<U>() {
      public U get() {
        return value;
      }
    };

    return CompletableFuture.supplyAsync(supplier);
  }

  /**
   * @param rawUri String
   * @return Returns whether the uri points to a file in jar.
   */
  public static boolean isSmithyJarFile(String rawUri) throws IOException {
    try {
      String uri = java.net.URLDecoder.decode(rawUri, StandardCharsets.UTF_8.name());
      return uri.startsWith("smithyjar:");
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * @param rawUri String
   * @return Returns whether the uri points to a file in the filesystem (as
   *         opposed to a file in a jar).
   */
  public static boolean isFile(String rawUri) {
    try {
      String uri = java.net.URLDecoder.decode(rawUri, StandardCharsets.UTF_8.name());
      return uri.startsWith("file:");
    } catch (IOException e) {
      return false;
    }
  }

  private static List<String> getLines(InputStream is) throws IOException {
    List<String> result = null;
    try {
      if (is != null) {
        InputStreamReader isr = new InputStreamReader(is);
        BufferedReader reader = new BufferedReader(isr);
        result = reader.lines().collect(Collectors.toList());
      }
    } finally {
      is.close();
    }

    return result;
  }

  /**
   * @param rawUri      the uri to a file in a jar.
   * @param classLoader a classloader used to retrieve resources.
   * @return the lines of the jar file, as a list.
   * @throws IOException when rawUri cannot be URI-decoded.
   */
  public static List<String> jarFileContents(String rawUri) throws IOException {
    String uri = java.net.URLDecoder.decode(rawUri, StandardCharsets.UTF_8.name());
    String[] pathArray = uri.split("!/");
    String jarPath = Utils.jarPath(rawUri);
    String file = pathArray[1];

    try (JarFile jar = new JarFile(new File(jarPath))) {
      ZipEntry entry = jar.getEntry(file);

      return getLines(jar.getInputStream(entry));
    }
  }

  public static String jarPath(String rawUri) throws UnsupportedEncodingException {
    String uri = java.net.URLDecoder.decode(rawUri, StandardCharsets.UTF_8.name());
    if (uri.startsWith("smithyjar:"))
      uri = uri.replaceFirst("smithyjar:", "");
    String[] pathArray = uri.split("!/");
    return pathArray[0];
  }

}
