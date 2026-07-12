package com.avi.idempotency.util;

import jakarta.servlet.http.HttpServletResponse;
import com.avi.idempotency.config.IdempotencyConfig;
import com.avi.idempotency.filter.SizeLimitedResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Extracts a sanitised snapshot of an HTTP response from a
 * {@link SizeLimitedResponseWrapper} (Decorator Pattern).
 *
 * <h2>Header Sanitisation</h2>
 * <p>The following security-sensitive headers are stripped before caching to
 * prevent session fixation and CSRF token leakage on replayed responses:</p>
 * <ul>
 *   <li>{@code Set-Cookie}</li>
 *   <li>{@code Set-Cookie2}</li>
 * </ul>
 *
 * <h2>Body Capture</h2>
 * <p>The wrapper's internal byte buffer is read <em>without</em> consuming the
 * stream, then flushed to the real response via {@link SizeLimitedResponseWrapper#copyBodyToResponse()}.
 * Callers must ensure {@code copyBodyToResponse()} is called after this method.</p>
 */
public final class ResponseCapture {

    /** Headers stripped before caching for security reasons. */
    private static final Set<String> STRIPPED_HEADERS = Set.of(
            "set-cookie", "set-cookie2"
    );

    private ResponseCapture() { /* utility class */ }

    /**
     * Extracts the status code, sanitised headers, and UTF-8 body from the wrapper.
     *
     * @param wrapper the Spring response wrapper (must have already passed through the filter chain)
     * @return an immutable {@link CapturedResponse} snapshot
     * @throws IOException if reading the body buffer fails
     */
    public static CapturedResponse capture(SizeLimitedResponseWrapper wrapper,
                                           IdempotencyConfig config) throws IOException {
        int status = wrapper.getStatus();
        Map<String, List<String>> headers = captureHeaders(wrapper, config);
        String body = new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
        return new CapturedResponse(status, headers, body);
    }

    private static Map<String, List<String>> captureHeaders(HttpServletResponse response,
                                                             IdempotencyConfig config) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : response.getHeaderNames()) {
            String lower = name.toLowerCase(Locale.ROOT);
            boolean isBuiltInStripped = STRIPPED_HEADERS.contains(lower);
            boolean isUserStripped = config.additionalStrippedHeaders().stream()
                    .anyMatch(h -> h.equalsIgnoreCase(lower));
            if (!isBuiltInStripped && !isUserStripped) {
                headers.put(name, new ArrayList<>(response.getHeaders(name)));
            }
        }
        return Collections.unmodifiableMap(headers);
    }

    /**
     * Value object holding the snapshot of a captured HTTP response.
     *
     * @param status  HTTP status code
     * @param headers sanitised response headers (unmodifiable)
     * @param body    response body as UTF-8 string
     */
    public record CapturedResponse(
            int status,
            Map<String, List<String>> headers,
            String body
    ) {}
}
