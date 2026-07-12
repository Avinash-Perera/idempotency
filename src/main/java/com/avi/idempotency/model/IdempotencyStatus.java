package com.avi.idempotency.model;

/**
 * Lifecycle states of an idempotency record stored in the backing store.
 *
 * <ul>
 *   <li>{@link #IN_PROGRESS} – the original request is currently being processed.
 *       Concurrent duplicate requests receive a {@code 409 Conflict}.</li>
 *   <li>{@link #COMPLETED} – the original request finished successfully.
 *       Replayed requests receive the cached response immediately.</li>
 * </ul>
 */
public enum IdempotencyStatus {

    /**
     * The request is being processed. Used as a distributed lock.
     */
    IN_PROGRESS,

    /**
     * The request completed and the response has been cached.
     */
    COMPLETED
}
