/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.util;

/**
 * A supplier that throws a checked exception.
 *
 * @param <T> The output of the supplier
 * @param <E> The exception type that can be thrown
 */
@FunctionalInterface
public interface ThrowingSupplier<T, E extends Exception> {
    T get() throws E;
}
