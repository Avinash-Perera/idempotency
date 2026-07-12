package com.avi.idempotency.filter;

import com.avi.idempotency.config.IdempotencyConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Thin servlet filter that wraps responses in a
 * {@link SizeLimitedResponseWrapper}, enabling downstream components
 * (specifically {@link com.avi.idempotency.interceptor.IdempotencyInterceptor})
 * to read the response body after it has been written by the controller.
 *
 * <p>To avoid buffering unnecessary responses, it only wraps the response if
 * the request contains a valid {@code Idempotency-Key} header.</p>
 */
public class IdempotencyResponseWrapFilter extends OncePerRequestFilter {

    private final IdempotencyConfig config;

    public IdempotencyResponseWrapFilter(IdempotencyConfig config) {
        this.config = config;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String key = request.getHeader(config.headerName());
        
        // Skip wrapping if no key or key is suspiciously long (protects against DoS)
        if (key == null || key.isBlank() || key.length() > 255) {
            chain.doFilter(request, response);
            return;
        }

        // Avoid double-wrapping
        if (response instanceof SizeLimitedResponseWrapper) {
            chain.doFilter(request, response);
            return;
        }

        SizeLimitedResponseWrapper wrapper = new SizeLimitedResponseWrapper(response, config.maxBodySizeBytes());
        try {
            chain.doFilter(request, wrapper);
        } finally {
            // Always flush the cached body to the real response
            wrapper.copyBodyToResponse();
        }
    }
}
