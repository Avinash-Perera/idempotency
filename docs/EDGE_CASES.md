# Edge Cases Handled

This library is designed for enterprise resilience. It automatically handles a wide variety of edge cases across security, failure modes, concurrency, and configuration.

## 🔒 Security Edge Cases

| Edge Case | How It's Handled | Where |
|-----------|-----------------|-------|
| **Session fixation attack** | `Set-Cookie` and `Set-Cookie2` headers are stripped before caching so replayed responses can't inject stale session cookies | `ResponseCapture` |
| **Cross-user replay attack** | The authenticated user principal is mixed into the SHA-256 fingerprint, so User A's key can never collide with User B's | `FingerprintGenerator` |
| **Cross-method collision** | HTTP method (GET/POST/PUT) is part of the fingerprint, so `GET /pay` and `POST /pay` with the same key produce different fingerprints | `FingerprintGenerator` |
| **Cross-endpoint collision** | The request URI is part of the fingerprint, so `/payments` and `/orders` with the same key are isolated | `FingerprintGenerator` |
| **Custom sensitive headers** | Developers can configure `additionalStrippedHeaders` to block custom headers like `X-CSRF-Token` from being cached | `IdempotencyConfig` + `ResponseCapture` |
| **Maliciously long keys (DoS)** | Keys longer than 255 characters are rejected immediately to prevent hash flooding attacks | `IdempotencyFilter` + `IdempotencyInterceptor` |

## 💥 Failure Edge Cases

| Edge Case | How It's Handled | Where |
|-----------|-----------------|-------|
| **Controller throws exception** | The `IN_PROGRESS` lock is deleted so the user can safely retry | `IdempotencyFilter` (catch block) + `IdempotencyInterceptor` (afterCompletion) |
| **Controller returns 5xx** | Same as above — lock is released on any status >= 500 | Both filter and interceptor |
| **OOM from huge response body** | `SizeLimitedResponseWrapper` tracks byte count and throws `413 Payload Too Large` if it exceeds the configured max (default 2MB) | `SizeLimitedResponseWrapper.checkSize()` |

## ⚡ Concurrency Edge Cases

| Edge Case | How It's Handled | Where |
|-----------|-----------------|-------|
| **Double-click (two threads racing)** | `saveIfAbsent` is atomic. The first thread wins the lock; the second gets `409 Conflict` | `IdempotencyProcessor.checkAndLock()` |
| **Lost race condition** | If `store.get()` returns empty but `saveIfAbsent()` fails (another thread beat us by microseconds), we return `InProgress` instead of crashing | `IdempotencyProcessor.checkAndLock()` |

## 🔧 Configuration Edge Cases

| Edge Case | How It's Handled | Where |
|-----------|-----------------|-------|
| **No `Idempotency-Key` header** | Request passes through completely untouched with zero overhead | Both filter and interceptor |
| **Blank/empty header value** | Treated the same as missing — request passes through | `key.isBlank()` check |
| **Mode A + Mode B both active** | The filter sets `request.setAttribute("idempotency.checked", true)`. The interceptor checks for this and skips if already handled | `IdempotencyFilter` + `IdempotencyInterceptor` |
| **Double response wrapping** | `IdempotencyResponseWrapFilter` checks `response instanceof SizeLimitedResponseWrapper` and skips if already wrapped | `IdempotencyResponseWrapFilter` |
| **No `@Idempotent` annotation** | The interceptor checks for the annotation via reflection and skips if missing | `IdempotencyInterceptor.preHandle()` |
| **Handler is not a controller method** | The interceptor checks `handler instanceof HandlerMethod` and skips for static resources, WebSocket handlers, etc. | `IdempotencyInterceptor.preHandle()` |
| **No wrapper available in afterCompletion** | Falls back to saving the status code with an empty body — still blocks duplicates even without the full response | `IdempotencyInterceptor.afterCompletion()` |
| **Per-endpoint TTL override** | `@Idempotent(ttlSeconds = 604800)` overrides the global TTL for specific endpoints | `IdempotencyInterceptor.preHandle()` |
| **Anonymous users (no auth)** | `resolvePrincipal()` returns `null`, and `FingerprintGenerator` handles `null` principal gracefully by omitting it from the hash | `resolvePrincipal()` + `FingerprintGenerator` |
