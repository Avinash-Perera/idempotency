package com.avi.idempotency.interceptor;

import com.avi.idempotency.annotation.Idempotent;
import com.avi.idempotency.config.IdempotencyConfig;
import com.avi.idempotency.core.IdempotencyProcessor;
import com.avi.idempotency.model.IdempotencyRecord;
import com.avi.idempotency.model.ProcessResult;
import com.avi.idempotency.store.IdempotencyStore;
import com.avi.idempotency.util.FingerprintGenerator;
import com.avi.idempotency.util.ResponseCapture;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingRequestWrapper;
import com.avi.idempotency.filter.IdempotencyFilter;
import com.avi.idempotency.filter.SizeLimitedResponseWrapper;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * <b>Mode B — Per-Endpoint {@code @Idempotent} Interceptor</b>
 *
 * <p>A Spring {@link HandlerInterceptor} that inspects the resolved handler via
 * reflection to detect {@link Idempotent} on the controller method.  When found,
 * it applies idempotency logic with the annotation's TTL value (falling back to
 * the global default when {@code ttlSeconds == -1}).</p>
 *
 * <h2>Integration with SizeLimitedResponseWrapper</h2>
 * <p>Because {@link HandlerInterceptor#afterCompletion} receives the original
 * {@code HttpServletResponse}, the interceptor expects a
 * {@link SizeLimitedResponseWrapper} to have been placed in the chain by
 * {@link com.avi.idempotency.filter.IdempotencyResponseWrapFilter}.</p>
 *
 * <h2>Request Attributes Used</h2>
 * <ul>
 *   <li>{@link #ATTR_FINGERPRINT} — the SHA-256 fingerprint for this request</li>
 *   <li>{@link #ATTR_TTL} — the effective TTL in seconds</li>
 * </ul>
 */
public class IdempotencyInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyInterceptor.class);

    /** Request attribute keys used to pass state between preHandle and afterCompletion. */
    static final String ATTR_FINGERPRINT = "idempotency.fingerprint";
    static final String ATTR_TTL         = "idempotency.ttl";

    /** Response header added on cache hits. */
    public static final String CACHE_HIT_HEADER = "Idempotency-Cache";
    public static final String CACHE_HIT_VALUE  = "HIT";

    private final IdempotencyStore     store;
    private final IdempotencyConfig    config;
    private final IdempotencyProcessor processor;

    public IdempotencyInterceptor(IdempotencyStore store,
                                   IdempotencyConfig config,
                                   IdempotencyProcessor processor) {
        this.store     = store;
        this.config    = config;
        this.processor = processor;
    }

    // ── HandlerInterceptor ───────────────────────────────────────────────────

    /**
     * Runs before the controller method.  Detects {@link Idempotent}, resolves
     * the effective TTL, then checks the store.
     *
     * @return {@code false} (short-circuit) when the request is already
     *         {@code IN_PROGRESS} (409) or {@code COMPLETED} (cached replay);
     *         {@code true} to continue to the controller.
     */
    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        // Only inspect HandlerMethod (controller method) handlers
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // Detect @Idempotent on the controller method via reflection
        Method method = handlerMethod.getMethod();
        Idempotent annotation = method.getAnnotation(Idempotent.class);
        if (annotation == null) {
            return true; // Not annotated → pass through
        }

        // Prevent mode collisions (Filter and Interceptor both processing the request)
        if (Boolean.TRUE.equals(request.getAttribute(IdempotencyFilter.ATTR_IDEMPOTENCY_CHECKED))) {
            return true;
        }

        String idempotencyKey = request.getHeader(config.headerName());
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            return true; // Header absent or invalid length → pass through
        }

        // Effective TTL: annotation value or global default
        long ttl = annotation.ttlSeconds() >= 0
                ? annotation.ttlSeconds()
                : config.defaultTtlSeconds();

        String principal   = resolvePrincipal(request);
        String body        = resolveBody(request);
        String fingerprint = FingerprintGenerator.generate(
                idempotencyKey, request.getMethod(), request.getRequestURI(), principal, body);

        ProcessResult result = processor.checkAndLock(fingerprint, store, ttl);

        return switch (result) {
            case ProcessResult.InProgress ignored -> {
                writeConflict(response);
                yield false;
            }
            case ProcessResult.Completed c -> {
                replayResponse(response, c.record());
                yield false;
            }
            case ProcessResult.NotFound ignored -> {
                // Store fingerprint & TTL in request attributes for afterCompletion
                request.setAttribute(ATTR_FINGERPRINT, fingerprint);
                request.setAttribute(ATTR_TTL, ttl);
                yield true;
            }
        };
    }

    /**
     * Runs after the controller method and view rendering.
     * Finalises the idempotency record: persists success or releases lock on failure.
     */
    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) throws Exception {

        String fingerprint = (String) request.getAttribute(ATTR_FINGERPRINT);
        if (fingerprint == null) {
            return; // This request was not under idempotency control
        }

        Long ttl = (Long) request.getAttribute(ATTR_TTL);

        if (ex != null || response.getStatus() >= 500) {
            processor.releaseLock(fingerprint, store);
            log.debug("Idempotency interceptor: lock released (error) fingerprint={}", fingerprint);
        } else {
            // Use wrapper placed by IdempotencyResponseWrapFilter if present,
            // otherwise release the lock and warn — caching an empty body would
            // cause silent replay of blank responses to duplicate requests.
            SizeLimitedResponseWrapper wrapper =
                    (response instanceof SizeLimitedResponseWrapper w)
                    ? w
                    : null;

            if (wrapper != null) {
                ResponseCapture.CapturedResponse captured = ResponseCapture.capture(wrapper, config);
                processor.completeSuccess(fingerprint, captured, store, ttl);
                // Do NOT call copyBodyToResponse here — the wrapping filter handles flushing
            } else {
                // SizeLimitedResponseWrapper was not present in the filter chain.
                // Releasing the lock is safer than caching an empty-body COMPLETED record,
                // which would replay blank responses to all future duplicate requests.
                // Fix: ensure IdempotencyResponseWrapFilter is registered in your application.
                log.warn("IdempotencyInterceptor: SizeLimitedResponseWrapper not present for " +
                         "fingerprint={}. Releasing lock so the client can retry safely. " +
                         "Ensure IdempotencyResponseWrapFilter is registered as a servlet filter.",
                         fingerprint);
                processor.releaseLock(fingerprint, store);
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void writeConflict(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_CONFLICT);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(org.springframework.http.HttpHeaders.RETRY_AFTER, String.valueOf(config.conflictRetryAfterSeconds()));
        response.getWriter().write(
                """
                {"error":"IDEMPOTENCY_CONFLICT","message":"A request with this Idempotency-Key is already in progress. Please retry after it completes."}
                """.strip()
        );
    }

    private void replayResponse(HttpServletResponse response,
                                 IdempotencyRecord record) throws Exception {
        response.setStatus(record.httpStatus());
        response.setHeader(CACHE_HIT_HEADER, CACHE_HIT_VALUE);
        for (Map.Entry<String, List<String>> entry : record.headers().entrySet()) {
            for (String value : entry.getValue()) {
                response.addHeader(entry.getKey(), value);
            }
        }
        if (!record.body().isEmpty()) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(record.body());
        }
    }

    private String resolvePrincipal(HttpServletRequest request) {
        return request.getUserPrincipal() != null
                ? request.getUserPrincipal().getName()
                : null;
    }

    /**
     * Reads the body from a {@link ContentCachingRequestWrapper} when
     * {@link IdempotencyConfig#includeBodyInFingerprint()} is enabled.
     *
     * <p>The wrapper must have been placed in the filter chain by
     * {@link IdempotencyFilter} (Mode A) or by a dedicated
     * {@code ContentCachingRequestWrapper} filter before this interceptor runs.
     * If the wrapper is absent and body fingerprinting is requested, this method
     * returns {@code null} and logs a warning.</p>
     */
    private String resolveBody(HttpServletRequest request) {
        if (!config.includeBodyInFingerprint()) {
            return null;
        }
        if (request instanceof ContentCachingRequestWrapper wrapper) {
            byte[] bytes = wrapper.getContentAsByteArray();
            return bytes.length > 0 ? new String(bytes, StandardCharsets.UTF_8) : null;
        }
        log.warn("IdempotencyInterceptor: includeBodyInFingerprint=true but request is not a " +
                 "ContentCachingRequestWrapper. Body will NOT be included in the fingerprint. " +
                 "Register IdempotencyFilter (Mode A) or a ContentCachingFilter before the interceptor.");
        return null;
    }
}
