package com.avi.idempotency.filter;

import com.avi.idempotency.config.IdempotencyConfig;
import com.avi.idempotency.core.IdempotencyProcessor;
import com.avi.idempotency.model.IdempotencyRecord;
import com.avi.idempotency.model.ProcessResult;
import com.avi.idempotency.store.IdempotencyStore;
import com.avi.idempotency.util.FingerprintGenerator;
import com.avi.idempotency.util.ResponseCapture;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * <b>Mode A — Global URL Pattern Filter</b>
 *
 * <p>A Spring {@link OncePerRequestFilter} that enforces idempotency on all
 * requests that carry an {@code Idempotency-Key} header within the registered
 * URL patterns.  Requests without the header pass through untouched.</p>
 *
 * <h2>Registration</h2>
 * <pre>{@code
 * @Bean
 * public FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(
 *         IdempotencyStore store, IdempotencyConfig config) {
 *     FilterRegistrationBean<IdempotencyFilter> bean = new FilterRegistrationBean<>();
 *     bean.setFilter(new IdempotencyFilter(store, config, processor));
 *     bean.addUrlPatterns("/payments/*", "/orders/*");
 *     bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
 *     return bean;
 * }
 * }</pre>
 *
 * <h2>Execution Flow</h2>
 * <ol>
 *   <li>Read {@code Idempotency-Key} header; skip if absent.</li>
 *   <li>Generate SHA-256 fingerprint.</li>
 *   <li>Check store via {@link IdempotencyProcessor#checkAndLock}.</li>
 *   <li>{@code IN_PROGRESS} → respond {@code 409 Conflict}.</li>
 *   <li>{@code COMPLETED} → replay cached response + {@code Idempotency-Cache: HIT} header.</li>
 *   <li>{@code NOT_FOUND} → wrap response, proceed, then persist result.</li>
 *   <li>On 5xx or exception → release lock so client can retry.</li>
 * </ol>
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String ATTR_IDEMPOTENCY_CHECKED = "idempotency.checked";

    /** Response header added on cache hits to signal a replayed response. */
    public static final String CACHE_HIT_HEADER = "Idempotency-Cache";
    public static final String CACHE_HIT_VALUE  = "HIT";

    private final IdempotencyStore      store;
    private final IdempotencyConfig     config;
    private final IdempotencyProcessor  processor;

    public IdempotencyFilter(IdempotencyStore store,
                              IdempotencyConfig config,
                              IdempotencyProcessor processor) {
        this.store     = store;
        this.config    = config;
        this.processor = processor;
    }

    // ── OncePerRequestFilter ─────────────────────────────────────────────────

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String idempotencyKey = request.getHeader(config.headerName());

        // ── No idempotency key or invalid key → pass through unchanged ────────
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
            chain.doFilter(request, response);
            return;
        }

        // ── Mode overlap prevention ───────────────────────────────────────────
        request.setAttribute(ATTR_IDEMPOTENCY_CHECKED, true);

        // ── Optionally wrap request to allow body re-reading ─────────────────
        HttpServletRequest effectiveRequest = config.includeBodyInFingerprint()
                && !(request instanceof ContentCachingRequestWrapper)
                ? new ContentCachingRequestWrapper(request, config.maxBodySizeBytes())
                : request;

        String principal   = resolvePrincipal(effectiveRequest);
        String body        = resolveBody(effectiveRequest);
        String fingerprint = FingerprintGenerator.generate(
                idempotencyKey, effectiveRequest.getMethod(),
                effectiveRequest.getRequestURI(), principal, body);

        ProcessResult result = processor.checkAndLock(fingerprint, store, config.defaultTtlSeconds());

        switch (result) {
            case ProcessResult.InProgress ignored -> {
                writeConflict(response);
                return;
            }
            case ProcessResult.Completed c -> {
                replayResponse(response, c.record());
                return;
            }
            case ProcessResult.NotFound ignored -> {
                // Fall through: process the request and capture the response
            }
        }

        // ── Wrap response to capture output ───────────────────────────────
        boolean isNewWrapper = !(response instanceof SizeLimitedResponseWrapper);
        SizeLimitedResponseWrapper wrappedResponse = isNewWrapper
                ? new SizeLimitedResponseWrapper(response, config.maxBodySizeBytes())
                : (SizeLimitedResponseWrapper) response;
                
        boolean lockReleased = false;

        try {
            if (isNewWrapper) {
                chain.doFilter(effectiveRequest, wrappedResponse);
            } else {
                chain.doFilter(effectiveRequest, response); // pass the existing wrapper down
            }

            int status = wrappedResponse.getStatus();
            if (status >= 500) {
                // Server error: release lock so the client can retry safely
                processor.releaseLock(fingerprint, store);
                lockReleased = true;
            } else {
                ResponseCapture.CapturedResponse captured = ResponseCapture.capture(wrappedResponse, config);
                processor.completeSuccess(fingerprint, captured, store, config.defaultTtlSeconds());
            }
        } catch (Exception ex) {
            if (!lockReleased) {
                processor.releaseLock(fingerprint, store);
                lockReleased = true;
            }
            throw ex;
        } finally {
            // Only flush if we created the wrapper
            if (isNewWrapper) {
                wrappedResponse.copyBodyToResponse();
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Writes a {@code 409 Conflict} response indicating a duplicate in-flight request.
     */
    private void writeConflict(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_CONFLICT);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(config.conflictRetryAfterSeconds()));
        response.getWriter().write(
                """
                {"error":"IDEMPOTENCY_CONFLICT","message":"A request with this Idempotency-Key is already in progress. Please retry after it completes."}
                """.strip()
        );
    }

    /**
     * Replays a previously cached response including status, headers, and body.
     */
    private void replayResponse(HttpServletResponse response,
                                 IdempotencyRecord record) throws IOException {
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

    /**
     * Resolves the authenticated principal name from the request, or {@code null}
     * for anonymous requests.
     */
    private String resolvePrincipal(HttpServletRequest request) {
        return request.getUserPrincipal() != null
                ? request.getUserPrincipal().getName()
                : null;
    }

    /**
     * Reads the request body for fingerprinting when
     * {@link IdempotencyConfig#includeBodyInFingerprint()} is enabled.
     *
     * <p>{@link ContentCachingRequestWrapper} buffers the body on first read so
     * the downstream controller can still consume it normally via
     * {@code @RequestBody}. When body fingerprinting is disabled this method
     * returns {@code null} immediately (zero overhead).</p>
     *
     * @param request the (possibly wrapped) HTTP request
     * @return the UTF-8 body string, or {@code null} if not applicable
     */
    private String resolveBody(HttpServletRequest request) throws IOException {
        if (!config.includeBodyInFingerprint()) {
            return null;
        }
        if (request instanceof ContentCachingRequestWrapper wrapper) {
            // Force the body to be read and cached by the wrapper
            byte[] bodyBytes = wrapper.getContentAsByteArray();
            if (bodyBytes.length == 0) {
                // Trigger an actual read if not yet cached
                wrapper.getInputStream().readAllBytes();
                bodyBytes = wrapper.getContentAsByteArray();
            }
            return new String(bodyBytes, StandardCharsets.UTF_8);
        }
        return null;
    }
}
