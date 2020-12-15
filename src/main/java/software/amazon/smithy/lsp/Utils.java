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

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class Utils {
  private Utils() {

  }

  /**
   * @param value Value to be used.
   * @param <U> Type of Value.
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
}
