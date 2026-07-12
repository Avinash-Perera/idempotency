package com.avi.idempotency.annotation;

import java.lang.annotation.*;

/**
 * Marks a Spring MVC controller method as idempotent.
 *
 * <p>When {@link com.avi.idempotency.interceptor.IdempotencyInterceptor} is
 * registered (done automatically by the auto-configuration), any request that
 * arrives at an annotated method and carries an {@code Idempotency-Key} header
 * will be subject to idempotency enforcement.</p>
 *
 * <h2>Usage — per-endpoint TTL override</h2>
 * <pre>{@code
 * @PostMapping("/charge")
 * @Idempotent(ttlSeconds = 86400)   // 24-hour TTL for payment idempotency
 * public ResponseEntity<?> charge(@RequestBody PaymentRequest req) { ... }
 * }</pre>
 *
 * <h2>Default TTL</h2>
 * <p>When {@link #ttlSeconds()} is set to {@code -1} (the default), the TTL
 * from {@link com.avi.idempotency.config.IdempotencyConfig#defaultTtlSeconds()}
 * is used instead.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * Custom TTL in seconds for this endpoint's idempotency cache.
     *
     * <p>Use {@code -1} (the default) to fall back to the library-wide default
     * configured via {@link com.avi.idempotency.config.IdempotencyConfig#defaultTtlSeconds()}.</p>
     *
     * @return TTL in seconds, or {@code -1} to use the global default
     */
    long ttlSeconds() default -1L;
}
