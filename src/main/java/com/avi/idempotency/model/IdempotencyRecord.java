package com.avi.idempotency.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of a completed (or in-progress) HTTP response.
 *
 * <p>Stored in the {@link com.avi.idempotency.store.IdempotencyStore} and replayed
 * verbatim for duplicate requests.  Uses a Java 21 record for compactness and
 * guaranteed immutability.</p>
 *
 * @param fingerprint SHA-256 fingerprint that uniquely identifies this request context.
 * @param status      Current lifecycle state ({@code IN_PROGRESS} or {@code COMPLETED}).
 * @param httpStatus  HTTP status code of the cached response (0 when {@code IN_PROGRESS}).
 * @param headers     Response headers to replay (sensitive headers like {@code Set-Cookie}
 *                    are stripped before storage).
 * @param body        Response body as a UTF-8 string (empty when {@code IN_PROGRESS}).
 * @param expiresAt   Absolute instant at which this record should be evicted.
 */
public record IdempotencyRecord(
        @JsonProperty("fingerprint") String fingerprint,
        @JsonProperty("status")      IdempotencyStatus status,
        @JsonProperty("httpStatus")  int httpStatus,
        @JsonProperty("headers")     Map<String, List<String>> headers,
        @JsonProperty("body")        String body,
        @JsonProperty("expiresAt")   Instant expiresAt
) {

    /**
     * Factory: creates a lock-only record indicating the request is in-flight.
     *
     * @param fingerprint unique request fingerprint
     * @param ttlSeconds  TTL for the lock
     * @return a new {@code IN_PROGRESS} record
     */
    public static IdempotencyRecord inProgress(String fingerprint, long ttlSeconds) {
        return new IdempotencyRecord(
                fingerprint,
                IdempotencyStatus.IN_PROGRESS,
                0,
                Map.of(),
                "",
                Instant.now().plusSeconds(ttlSeconds)
        );
    }

    /**
     * Factory: creates a record holding the fully cached response.
     *
     * @param fingerprint unique request fingerprint
     * @param httpStatus  response status code
     * @param headers     sanitised response headers
     * @param body        response body
     * @param ttlSeconds  TTL for the cached entry
     * @return a new {@code COMPLETED} record
     */
    public static IdempotencyRecord completed(
            String fingerprint,
            int httpStatus,
            Map<String, List<String>> headers,
            String body,
            long ttlSeconds) {
        return new IdempotencyRecord(
                fingerprint,
                IdempotencyStatus.COMPLETED,
                httpStatus,
                headers,
                body,
                Instant.now().plusSeconds(ttlSeconds)
        );
    }

    /**
     * Returns {@code true} if this record has passed its expiry instant.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
