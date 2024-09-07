/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.util;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Type representing the result of an operation that could be successful
 * or fail.
 *
 * @param <T> Type of successful result
 * @param <E> Type of failed result
 */
public final class Result<T, E> {
    private final T value;
    private final E error;

    private Result(T value, E error) {
        this.value = value;
        this.error = error;
    }

    /**
     * @param value The success value
     * @param <T> Type of successful result
     * @param <E> Type of failed result
     * @return The successful result
     */
    public static <T, E> Result<T, E> ok(T value) {
        return new Result<>(value, null);
    }

    /**
     * @param error The failed value
     * @param <T> Type of successful result
     * @param <E> Type of failed result
     * @return The failed result
     */
    public static <T, E> Result<T, E> err(E error) {
        return new Result<>(null, error);
    }

    /**
     * @param fallible A function that may fail
     * @param <T> Type of successful result
     * @return A result containing the result of calling {@code fallible}
     */
    public static <T> Result<T, Exception> ofFallible(Supplier<T> fallible) {
        try {
            return Result.ok(fallible.get());
        } catch (Exception e) {
            return Result.err(e);
        }
    }

    /**
     * @param throwing A function that may throw
     * @param <T> Type of successful result
     * @return A result containing the result of calling {@code throwing}
     */
    public static <T> Result<T, Exception> ofThrowing(ThrowingSupplier<T, Exception> throwing) {
        try {
            return Result.ok(throwing.get());
        } catch (Exception e) {
            return Result.err(e);
        }
    }

    /**
     * @return Whether this result is successful
     */
    public boolean isOk() {
        return this.value != null;
    }

    /**
     * @return Whether this result is failed
     */
    public boolean isErr() {
        return this.error != null;
    }

    /**
     * @return The successful value, or throw an exception if this Result is failed
     */
    public T unwrap() {
        if (get().isEmpty()) {
            throw new RuntimeException("Called unwrap on an Err Result: " + getErr().get());
        }
        return get().get();
    }

    /**
     * @return The failed value, or throw an exception if this Result is successful
     */
    public E unwrapErr() {
        if (getErr().isEmpty()) {
            throw new RuntimeException("Called unwrapErr on an Ok Result: " + get().get());
        }
        return getErr().get();
    }

    /**
     * @return Get the successful value if present
     */
    public Optional<T> get() {
        return Optional.ofNullable(value);
    }

    /**
     * @return Get the failed value if present
     */
    public Optional<E> getErr() {
        return Optional.ofNullable(error);
    }

    /**
     * Transforms the successful value of this Result, if present.
     *
     * @param mapper Function to apply to the successful value of this result
     * @param <U> The type to map to
     * @return A new result with {@code mapper} applied, if this result is a
     *  successful one
     */
    public <U> Result<U, E> map(Function<T, U> mapper) {
        if (isOk()) {
            return Result.ok(mapper.apply(unwrap()));
        }
        return Result.err(unwrapErr());
    }

    /**
     * Transforms the failed value of this Result, if present.
     *
     * @param mapper Function to apply to the failed value of this result
     * @param <U> The type to map to
     * @return A new result with {@code mapper} applied, if this result is a
     *  failed one
     */
    public <U> Result<T, U> mapErr(Function<E, U> mapper) {
        if (isErr()) {
            return Result.err(mapper.apply(unwrapErr()));
        }
        return Result.ok(unwrap());
    }


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
}
