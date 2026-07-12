package com.avi.idempotency.model;

/**
 * Sealed hierarchy returned by {@link com.avi.idempotency.core.IdempotencyProcessor}
 * after checking the store for a given fingerprint.
 *
 * <p>Using a sealed interface with Java 21 pattern matching allows callers to
 * exhaustively handle every outcome in a switch expression without a default
 * branch, catching future additions at compile time.</p>
 *
 * <pre>{@code
 * ProcessResult result = processor.checkAndLock(fingerprint, store, ttlSeconds);
 * switch (result) {
 *     case ProcessResult.NotFound nf    -> // proceed with request
 *     case ProcessResult.InProgress ip  -> // return 409
 *     case ProcessResult.Completed c    -> // replay cached response
 * }
 * }</pre>
 */
public sealed interface ProcessResult
        permits ProcessResult.NotFound, ProcessResult.InProgress, ProcessResult.Completed {

    /**
     * No record exists in the store; the caller should process the request normally
     * and then persist the result.
     */
    record NotFound() implements ProcessResult {}

    /**
     * Another thread/process is currently handling this request.
     * The caller should return {@code 409 Conflict}.
     */
    record InProgress() implements ProcessResult {}

    /**
     * The request was already processed successfully.
     * The {@link IdempotencyRecord} contains the full cached response to replay.
     *
     * @param record the cached response record
     */
    record Completed(IdempotencyRecord record) implements ProcessResult {}
}
