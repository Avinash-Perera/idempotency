# Mode B — Interceptor Request Flow

> This document traces every class involved in a request when using **Mode B** (`@Idempotent` annotation + `IdempotencyInterceptor`), which is the default auto-configured mode.

## Classes Involved

| Class | Role |
|-------|------|
| `IdempotencyResponseWrapFilter` | The "Stage Crew". Wraps the response early so the Interceptor can read it later. |
| `IdempotencyWebMvcConfigurer` | Registers the Interceptor with Spring MVC at startup (not called per-request). |
| `IdempotencyInterceptor` | The main workhorse. Checks for `@Idempotent`, manages locking, captures responses. |
| `FingerprintGenerator` | Generates the SHA-256 fingerprint from key + method + URL + principal. |
| `IdempotencyProcessor` | The "Brain". Executes the state machine logic (check, lock, complete, release). |
| `IdempotencyStore` | The storage interface (Memory, Redis, or Postgres). |
| `SizeLimitedResponseWrapper` | The "Wiretap". Secretly captures the response body in memory. |
| `ResponseCapture` | The "Photographer". Takes a snapshot of the captured response, stripping forbidden headers. |
| `IdempotencyFilter` | **NOT USED in Mode B.** Mode A's filter is not registered in auto-configuration. |

---

## Scenario 1: No `Idempotency-Key` Header

The user sends a normal request without any idempotency header.

```mermaid
sequenceDiagram
    participant Client
    participant IdempotencyResponseWrapFilter
    participant DispatcherServlet
    participant IdempotencyInterceptor
    participant Controller

    Client->>IdempotencyResponseWrapFilter: POST /api/pay (no header)
    Note over IdempotencyResponseWrapFilter: Header is null → skip wrapping
    IdempotencyResponseWrapFilter->>DispatcherServlet: chain.doFilter(request, response)
    DispatcherServlet->>IdempotencyInterceptor: preHandle(request, response, handler)
    Note over IdempotencyInterceptor: Checks @Idempotent → yes, but no header → skip
    IdempotencyInterceptor-->>DispatcherServlet: return true
    DispatcherServlet->>Controller: invoke controller method
    Controller-->>Client: JSON response (untouched)
```

### Step-by-step:

1. **`IdempotencyResponseWrapFilter`** — Reads the header → `null`. Calls `chain.doFilter(request, response)` without wrapping.
2. **`IdempotencyInterceptor`** (`preHandle`) — Checks for `@Idempotent` annotation → found. Reads the header → `null`. Returns `true` immediately.
3. **Controller** — Runs normally. Zero overhead from the library.

> **💡 Tip:** No fingerprint is generated. No store is contacted. No wrapper is created. Both the filter and the interceptor skip instantly.

---

## Scenario 2: Controller Method Does NOT Have `@Idempotent`

The user sends a request with `Idempotency-Key` to an endpoint that is not annotated.

```mermaid
sequenceDiagram
    participant Client
    participant IdempotencyResponseWrapFilter
    participant DispatcherServlet
    participant IdempotencyInterceptor
    participant Controller

    Client->>IdempotencyResponseWrapFilter: POST /api/health + Idempotency-Key: abc-123
    Note over IdempotencyResponseWrapFilter: Header exists → creates wrapper
    IdempotencyResponseWrapFilter->>DispatcherServlet: chain.doFilter(request, wrapper)
    DispatcherServlet->>IdempotencyInterceptor: preHandle(request, wrapper, handler)
    Note over IdempotencyInterceptor: Checks @Idempotent → NOT found → skip
    IdempotencyInterceptor-->>DispatcherServlet: return true
    DispatcherServlet->>Controller: invoke controller method
    Controller-->>IdempotencyResponseWrapFilter: JSON written into wrapper
    Note over IdempotencyResponseWrapFilter: finally block: copyBodyToResponse()
    IdempotencyResponseWrapFilter-->>Client: JSON response
```

### Step-by-step:

1. **`IdempotencyResponseWrapFilter`** — Sees the header exists. Creates a `SizeLimitedResponseWrapper`. Passes the request down with the wrapper.
2. **`IdempotencyInterceptor`** (`preHandle`) — Checks the controller method for `@Idempotent` → annotation is missing. Returns `true` immediately.
3. **Controller** — Runs normally. Writes JSON into the wrapper.
4. **`IdempotencyResponseWrapFilter`** (`finally`) — Flushes the wrapper to the real network socket.

> **📝 Note:** The wrapper was created unnecessarily in this case because the filter cannot know at that point whether the destination controller has `@Idempotent`. This is a minor trade-off — creating a wrapper costs very little memory (starts at 1KB), and it gets cleaned up immediately.

---

## Scenario 3: Brand New Request (First Time)

The user clicks "Pay" for the first time with `Idempotency-Key: abc-123` on an `@Idempotent` endpoint.

```mermaid
sequenceDiagram
    participant Client
    participant IdempotencyResponseWrapFilter
    participant DispatcherServlet
    participant IdempotencyInterceptor
    participant FingerprintGenerator
    participant IdempotencyProcessor
    participant IdempotencyStore
    participant Controller
    participant ResponseCapture

    Client->>IdempotencyResponseWrapFilter: POST /api/pay + Idempotency-Key: abc-123
    Note over IdempotencyResponseWrapFilter: Header exists → creates SizeLimitedResponseWrapper
    IdempotencyResponseWrapFilter->>DispatcherServlet: chain.doFilter(request, wrapper)
    DispatcherServlet->>IdempotencyInterceptor: preHandle(request, wrapper, handler)
    Note over IdempotencyInterceptor: @Idempotent found ✓ Header found ✓
    IdempotencyInterceptor->>FingerprintGenerator: generate(key, POST, /api/pay, user)
    FingerprintGenerator-->>IdempotencyInterceptor: SHA-256 fingerprint
    IdempotencyInterceptor->>IdempotencyProcessor: checkAndLock(fingerprint)
    IdempotencyProcessor->>IdempotencyStore: get(fingerprint)
    Note over IdempotencyStore: 🌐 FIRST NETWORK CALL
    IdempotencyStore-->>IdempotencyProcessor: Empty (not found)
    IdempotencyProcessor->>IdempotencyStore: saveIfAbsent(IN_PROGRESS)
    Note over IdempotencyStore: 🌐 SECOND NETWORK CALL
    IdempotencyStore-->>IdempotencyProcessor: true (lock acquired)
    IdempotencyProcessor-->>IdempotencyInterceptor: ProcessResult.NotFound
    Note over IdempotencyInterceptor: Saves fingerprint & TTL as request attributes
    IdempotencyInterceptor-->>DispatcherServlet: return true (proceed to controller)
    DispatcherServlet->>Controller: invoke controller method
    Note over Controller: Business logic runs (charge credit card, etc.)
    Controller-->>DispatcherServlet: JSON written into wrapper
    DispatcherServlet->>IdempotencyInterceptor: afterCompletion(request, wrapper, handler, null)
    Note over IdempotencyInterceptor: No exception, status < 500 → save response
    IdempotencyInterceptor->>ResponseCapture: capture(wrapper, config)
    Note over ResponseCapture: Extracts status, strips Set-Cookie, reads body
    ResponseCapture-->>IdempotencyInterceptor: CapturedResponse
    IdempotencyInterceptor->>IdempotencyProcessor: completeSuccess(fingerprint, captured)
    IdempotencyProcessor->>IdempotencyStore: update(COMPLETED record)
    Note over IdempotencyStore: 🌐 THIRD NETWORK CALL
    IdempotencyStore-->>IdempotencyProcessor: saved
    Note over IdempotencyResponseWrapFilter: finally block: copyBodyToResponse()
    IdempotencyResponseWrapFilter-->>Client: JSON response
```

### Step-by-step:

1. **`IdempotencyResponseWrapFilter`** — Sees the header. Creates the `SizeLimitedResponseWrapper`. Passes the request down.
2. **Spring MVC `DispatcherServlet`** — Routes the request to the correct controller.
3. **`IdempotencyInterceptor`** (`preHandle`) — Checks `@Idempotent` → found. Reads the header → valid.
4. **`FingerprintGenerator`** — Hashes key + method + URL + principal. (Local CPU, no network.)
5. **`IdempotencyProcessor`** — Calls `checkAndLock()`.
6. **`IdempotencyStore`** — 🌐 `store.get(fingerprint)` → not found. 🌐 `store.saveIfAbsent(IN_PROGRESS)` → lock acquired.
7. **`IdempotencyInterceptor`** — Saves `fingerprint` and `ttl` as request attributes (sticky notes for `afterCompletion`). Returns `true`.
8. **Controller** — The developer's business logic runs. JSON is written into the wrapper's secret buffer.
9. **`IdempotencyInterceptor`** (`afterCompletion`) — Spring calls this automatically after the controller finishes.
10. **`ResponseCapture`** — Extracts status, strips forbidden headers, reads body from wrapper.
11. **`IdempotencyProcessor`** — Calls `completeSuccess()`. Tells the store to update to `COMPLETED`.
12. **`IdempotencyStore`** — 🌐 Saves the complete record with TTL.
13. **`IdempotencyResponseWrapFilter`** (`finally`) — Flushes the wrapper to Tomcat's real network socket. Client receives the response.

---

## Scenario 4: Duplicate Request (Already Completed)

The user's app retries the same payment 5 minutes later with the same `Idempotency-Key: abc-123`.

```mermaid
sequenceDiagram
    participant Client
    participant IdempotencyResponseWrapFilter
    participant DispatcherServlet
    participant IdempotencyInterceptor
    participant FingerprintGenerator
    participant IdempotencyProcessor
    participant IdempotencyStore

    Client->>IdempotencyResponseWrapFilter: POST /api/pay + Idempotency-Key: abc-123
    Note over IdempotencyResponseWrapFilter: Header exists → creates wrapper
    IdempotencyResponseWrapFilter->>DispatcherServlet: chain.doFilter(request, wrapper)
    DispatcherServlet->>IdempotencyInterceptor: preHandle(request, wrapper, handler)
    IdempotencyInterceptor->>FingerprintGenerator: generate(key, POST, /api/pay, user)
    FingerprintGenerator-->>IdempotencyInterceptor: SHA-256 fingerprint (same as before)
    IdempotencyInterceptor->>IdempotencyProcessor: checkAndLock(fingerprint)
    IdempotencyProcessor->>IdempotencyStore: get(fingerprint)
    Note over IdempotencyStore: 🌐 ONLY NETWORK CALL
    IdempotencyStore-->>IdempotencyProcessor: IdempotencyRecord (COMPLETED)
    IdempotencyProcessor-->>IdempotencyInterceptor: ProcessResult.Completed(record)
    Note over IdempotencyInterceptor: replayResponse() — status + headers + body + "Idempotency-Cache: HIT"
    IdempotencyInterceptor-->>DispatcherServlet: return false (skip controller)
    Note over IdempotencyInterceptor: afterCompletion runs but fingerprint is null → exits immediately
    Note over IdempotencyResponseWrapFilter: finally block: copyBodyToResponse()
    IdempotencyResponseWrapFilter-->>Client: Cached JSON response (identical to original)
```

### Step-by-step:

1. **`IdempotencyResponseWrapFilter`** — Creates wrapper (it cannot know the request is a duplicate yet).
2. **`IdempotencyInterceptor`** (`preHandle`) — Checks `@Idempotent` → found. Reads header → valid.
3. **`FingerprintGenerator`** — Generates the same fingerprint as before.
4. **`IdempotencyProcessor`** — Calls `checkAndLock()`.
5. **`IdempotencyStore`** — 🌐 `store.get(fingerprint)` → found with status `COMPLETED`.
6. **`IdempotencyInterceptor`** — Calls `replayResponse()`. Sets status, adds `Idempotency-Cache: HIT`, replays all headers, writes saved JSON body. Returns `false`.
7. **Controller NEVER runs.** No business logic. No database queries. No payment charged.
8. **`IdempotencyInterceptor`** (`afterCompletion`) — Spring calls it, but `fingerprint` attribute is `null` (was never saved), so it returns immediately.
9. **`IdempotencyResponseWrapFilter`** (`finally`) — Flushes the replayed response to the client.

> **⚠️ Important:** Only 1 network call to the store. The controller is completely skipped. The client receives an identical response to the original.

---

## Scenario 5: Duplicate Request (Still In Progress — Double Click)

The user double-clicks "Pay". The first click is still being processed by another thread.

```mermaid
sequenceDiagram
    participant Client
    participant IdempotencyResponseWrapFilter
    participant DispatcherServlet
    participant IdempotencyInterceptor
    participant FingerprintGenerator
    participant IdempotencyProcessor
    participant IdempotencyStore

    Client->>IdempotencyResponseWrapFilter: POST /api/pay + Idempotency-Key: abc-123
    Note over IdempotencyResponseWrapFilter: Header exists → creates wrapper
    IdempotencyResponseWrapFilter->>DispatcherServlet: chain.doFilter(request, wrapper)
    DispatcherServlet->>IdempotencyInterceptor: preHandle(request, wrapper, handler)
    IdempotencyInterceptor->>FingerprintGenerator: generate(...)
    FingerprintGenerator-->>IdempotencyInterceptor: fingerprint
    IdempotencyInterceptor->>IdempotencyProcessor: checkAndLock(fingerprint)
    IdempotencyProcessor->>IdempotencyStore: get(fingerprint)
    Note over IdempotencyStore: 🌐 ONLY NETWORK CALL
    IdempotencyStore-->>IdempotencyProcessor: IdempotencyRecord (IN_PROGRESS)
    IdempotencyProcessor-->>IdempotencyInterceptor: ProcessResult.InProgress
    Note over IdempotencyInterceptor: writeConflict() → 409 + Retry-After
    IdempotencyInterceptor-->>DispatcherServlet: return false (skip controller)
    Note over IdempotencyResponseWrapFilter: finally block: copyBodyToResponse()
    IdempotencyResponseWrapFilter-->>Client: 409 Conflict + "Please retry"
```

### Step-by-step:

1. **`IdempotencyResponseWrapFilter`** — Creates wrapper.
2. **`IdempotencyInterceptor`** (`preHandle`) — Checks `@Idempotent` → found.
3. **`FingerprintGenerator`** — Generates fingerprint.
4. **`IdempotencyProcessor`** — Calls `checkAndLock()`.
5. **`IdempotencyStore`** — 🌐 Record exists with status `IN_PROGRESS`.
6. **`IdempotencyInterceptor`** — Calls `writeConflict()`. Sends `409 Conflict` + `Retry-After` header + JSON error body. Returns `false`.
7. **Controller NEVER runs.** The double-click is instantly rejected.
8. **`IdempotencyResponseWrapFilter`** (`finally`) — Flushes the 409 error to the client.

---

## Scenario 6: Controller Crashes (500 Error / Exception)

A new request arrives, the lock is acquired, but the controller throws an exception.

```mermaid
sequenceDiagram
    participant Client
    participant IdempotencyResponseWrapFilter
    participant DispatcherServlet
    participant IdempotencyInterceptor
    participant IdempotencyProcessor
    participant IdempotencyStore
    participant Controller

    Client->>IdempotencyResponseWrapFilter: POST /api/pay + Idempotency-Key: abc-123
    Note over IdempotencyResponseWrapFilter: Creates wrapper
    IdempotencyResponseWrapFilter->>DispatcherServlet: chain.doFilter(request, wrapper)
    DispatcherServlet->>IdempotencyInterceptor: preHandle → true (lock acquired)
    DispatcherServlet->>Controller: invoke controller method
    Note over Controller: 💥 throws NullPointerException!
    Controller-->>DispatcherServlet: Exception propagates
    DispatcherServlet->>IdempotencyInterceptor: afterCompletion(request, wrapper, handler, ex)
    Note over IdempotencyInterceptor: ex != null → release the lock!
    IdempotencyInterceptor->>IdempotencyProcessor: releaseLock(fingerprint)
    IdempotencyProcessor->>IdempotencyStore: delete(fingerprint)
    Note over IdempotencyStore: 🌐 Lock deleted — user can retry safely
    Note over IdempotencyResponseWrapFilter: finally block: copyBodyToResponse()
    IdempotencyResponseWrapFilter-->>Client: 500 Internal Server Error
```

### Step-by-step:

1. Steps 1-7 are the same as Scenario 3 (new request, lock acquired, wrapper created).
2. **Controller** — The developer's code throws an exception.
3. **`IdempotencyInterceptor`** (`afterCompletion`) — Spring passes the exception as `ex`. The interceptor sees `ex != null`, so it calls `processor.releaseLock()`.
4. **`IdempotencyProcessor`** — Calls `store.delete(fingerprint)`.
5. **`IdempotencyStore`** — 🌐 Deletes the `IN_PROGRESS` lock from the store.
6. **`IdempotencyResponseWrapFilter`** (`finally`) — Flushes whatever is in the buffer.
7. Spring handles the exception and sends `500 Internal Server Error` to the client.

> **⚠️ Important:** The lock is released. The user can safely retry the payment. The next retry will be treated as a brand new `NotFound` request.

---

## Mode A vs Mode B — Summary Comparison

| Feature | Mode A (Global Filter) | Mode B (Interceptor) |
|---------|----------------------|---------------------|
| Entry Point | `IdempotencyFilter` | `IdempotencyResponseWrapFilter` |
| Activation | URL patterns (e.g., `/api/*`) | `@Idempotent` annotation per method |
| First store call happens at | Filter layer (very early) | Interceptor layer (after DispatcherServlet) |
| Wrapper created by | `IdempotencyFilter` itself | `IdempotencyResponseWrapFilter` (separate class) |
| Duplicate request efficiency | 🟢 Faster (stops at filter, never reaches Spring MVC) | 🟡 Slightly slower (passes through filter + DispatcherServlet before interceptor stops it) |
| Configuration required | Manual `FilterRegistrationBean` in a `@Configuration` class | Zero — auto-configured, just add `@Idempotent` |
| Best for | Protecting entire URL paths without modifying controllers | Protecting specific endpoints with fine-grained control |
