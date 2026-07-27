package com.watchonce.core;

/**
 * Sealed result type: callers are forced by the compiler (exhaustive switch) to handle
 * both branches instead of an exception that can silently propagate past a layer that
 * should have handled it.
 */
public sealed interface Result<T, E> {

    record Ok<T, E>(T value) implements Result<T, E> {}

    record Err<T, E>(E error) implements Result<T, E> {}

    static <T, E> Result<T, E> ok(T value) {
        return new Ok<>(value);
    }

    static <T, E> Result<T, E> err(E error) {
        return new Err<>(error);
    }

    default boolean isOk() {
        return this instanceof Ok<T, E>;
    }

    default T orElseThrow() {
        if (this instanceof Ok<T, E> ok) {
            return ok.value();
        }
        throw new IllegalStateException("Result is an error: " + ((Err<T, E>) this).error());
    }
}
