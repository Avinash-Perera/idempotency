package com.avi.idempotency.config;

import java.util.Set;

/**
 * Immutable configuration for the idempotency library.
 *
 * <p>
 * Consumers can either rely on {@link #defaults()} or construct a custom
 * instance and expose it as a {@code @Bean} to override the auto-configured
 * one.
 * </p>
 *
 * @param headerName                HTTP request header that carries the
 *                                  idempotency key
 *                                  (defaults to {@code "Idempotency-Key"}).
 * @param defaultTtlSeconds         Default time-to-live for cached responses
 *                                  when no
 *                                  per-endpoint TTL is specified (defaults to
 *                                  {@code 86400}
 *                                  seconds / 24 hours).
 * @param maxCapacity               Maximum number of entries held by the
 *                                  in-memory store
 *                                  before LRU eviction kicks in (defaults to
 *                                  {@code 10000}).
 * @param maxBodySizeBytes          Maximum size of the request body in bytes.
 * @param conflictRetryAfterSeconds Time in seconds to return in Retry-After
 *                                  header on conflict.
 * @param additionalStrippedHeaders Extra response header names to strip before
 *                                  caching (case-insensitive).
 *                                  Use this for custom security headers like
 *                                  {@code X-New-Token} or
 *                                  {@code X-CSRF-Token} that must never be
 *                                  replayed to clients.
 */
public record IdempotencyConfig(
        String headerName,
        long defaultTtlSeconds,
        int maxCapacity,
        int maxBodySizeBytes,
        int conflictRetryAfterSeconds,
        Set<String> additionalStrippedHeaders) {
    public IdempotencyConfig {
        if (headerName == null || headerName.isBlank()) {
            throw new IllegalArgumentException("Header name cannot be null or blank");
        }
        if (defaultTtlSeconds <= 0) {
            throw new IllegalArgumentException("Default TTL must be strictly positive");
        }
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("Max capacity must be strictly positive");
        }
        if (maxBodySizeBytes <= 0) {
            throw new IllegalArgumentException("Max body size must be strictly positive");
        }
        if (conflictRetryAfterSeconds < 0) {
            throw new IllegalArgumentException("Conflict retry after seconds cannot be negative");
        }
        if (additionalStrippedHeaders == null) {
            throw new IllegalArgumentException("additionalStrippedHeaders cannot be null — use Set.of() for none");
        }
    }

    /**
     * Default configuration:
     * - Header: Idempotency-Key
     * - TTL: 24 hours (86400s)
     * - LRU Capacity: 10,000 entries
     * - Max Body Size: 2 MB (2097152 bytes)
     * - Conflict Retry After: 2 seconds
     */
    public static IdempotencyConfig defaults() {
        return new IdempotencyConfig("Idempotency-Key", 86400, 10000, 2097152, 2, Set.of());
    }
}
