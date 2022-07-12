package io.confluent.csid.actors;

/*-
 * Copyright (C) 2020-2022 Confluent, Inc.
 */

import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * todo docs
 *
 * @param <T>
 * @author Antony Stubbs
 */
public interface IActor<T> {

    void tell(Consumer<T> action);

    /**
     * Same as {@link IActor#tell} but messages will be placed at the front of the queue, instead of at the end.
     */
    void tellImmediately(Consumer<T> action);

    <R> Future<R> ask(Function<T, R> action);

    boolean isEmpty();

    <R> Future<R> askImmediately(Function<T, R> action);
}