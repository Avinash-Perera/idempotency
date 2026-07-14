# spring-boot-idempotency

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Build](https://img.shields.io/badge/build-passing-brightgreen.svg)]()

**Prevent duplicate request processing (e.g. double-charges on payments) in Spring Boot REST APIs using Idempotency Keys.**

---

## ✨ Features

| Feature | Detail |
|---|---|
| **Dual activation modes** | Global URL-pattern filter **and** per-endpoint `@Idempotent` annotation — they coexist seamlessly |
| **Pluggable storage** | In-memory LRU store (zero config) or Redis for distributed deployments |
| **SHA-256 fingerprinting** | Scopes keys by method + URI + optional authenticated user |
| **Atomic locking** | `ConcurrentHashMap.putIfAbsent` (in-memory) or Redis `SET NX EX` (Redis) |
| **Automatic lock release** | 5xx responses and exceptions release the lock so clients can safely retry |
| **Security** | `Set-Cookie` / `Set-Cookie2` headers are stripped before caching |
| **Java 21** | Uses records, sealed interfaces, and pattern matching |
| **Spring Boot Auto-Configuration** | Zero XML — drop the dependency in and go |

---

## ⚠️ Disclaimer of Liability

**This software is provided "AS IS", without warranty of any kind.** 

By using this library, you acknowledge and agree that the author(s) and contributors are **NOT liable** for any direct, indirect, incidental, special, or consequential damages, including but not limited to financial losses, data corruption, double-charges, or system downtime resulting from the use, misuse, or inability to use this software. 

Financial and payment systems require rigorous end-to-end testing. You are solely responsible for verifying that this idempotency implementation meets your application's specific safety and compliance requirements before deploying to production.

---

## 📦 Installation

```xml
<dependency>
    <groupId>io.github.avinashperera</groupId>
    <artifactId>spring-boot-idempotency</artifactId>
    <version>1.0.0</version>
</dependency>
```

For Redis-backed storage, also add:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

---

## 🚀 Quick Start

### Mode A — Global URL Pattern (Filter)

Register the filter for entire route groups. Every request to those patterns that includes an `Idempotency-Key` header is automatically protected.

```java
@Configuration
public class IdempotencyConfiguration {

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(
            IdempotencyStore store,
            IdempotencyConfig config,
            IdempotencyProcessor processor) {

        FilterRegistrationBean<IdempotencyFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new IdempotencyFilter(store, config, processor));
        bean.addUrlPatterns("/payments/*", "/orders/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }
}
```

### Mode B — Per-Endpoint Annotation

Annotate individual controller methods. Override the TTL per endpoint.

```java
@RestController
@RequestMapping("/payments")
public class PaymentController {

    // Uses global default TTL (24h)
    @PostMapping("/charge")
    @Idempotent
    public ResponseEntity<PaymentResponse> charge(@RequestBody PaymentRequest req) {
        return ResponseEntity.ok(paymentService.charge(req));
    }

    // Custom 7-day TTL for large transfers
    @PostMapping("/transfer")
    @Idempotent(ttlSeconds = 604_800)
    public ResponseEntity<TransferResponse> transfer(@RequestBody TransferRequest req) {
        return ResponseEntity.ok(paymentService.transfer(req));
    }
}
```

> Both modes can be used simultaneously in the same application. Requests matching a filter URL-pattern use Mode A; requests hitting annotated endpoints use Mode B.

---

## ⚙️ Configuration

Override any auto-configured bean by declaring your own `@Bean`:

```java
@Configuration
public class MyIdempotencyConfig {

    // Change the header name and default TTL
    @Bean
    public IdempotencyConfig idempotencyConfig() {
        return new IdempotencyConfig(
            "X-Request-Id",  // header name
            3_600L,          // default TTL: 1 hour
            50_000           // max in-memory capacity
        );
    }

    // Swap to Redis store (requires spring-boot-starter-data-redis)
    @Bean
    public IdempotencyStore idempotencyStore(StringRedisTemplate redisTemplate) {
        return new RedisIdempotencyStore(redisTemplate);
    }
}
```

### Configuration Reference

| Parameter | Default | Description |
|---|---|---|
| `headerName` | `Idempotency-Key` | HTTP request header carrying the idempotency key |
| `defaultTtlSeconds` | `86400` (24h) | Cache TTL used when `@Idempotent` has no override |
| `maxCapacity` | `10000` | Max entries for the in-memory LRU store |

---

## 🔄 How It Works

```
Client Request
      │
      ▼
┌─────────────────────────────────────────┐
│ 1. Read Idempotency-Key header          │
│    No header? → pass through            │
└──────────────────┬──────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────┐
│ 2. Generate SHA-256 fingerprint         │
│    = Hash(key | METHOD | URI | user)    │
└──────────────────┬──────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────┐
│ 3. Check IdempotencyStore               │
│                                         │
│  IN_PROGRESS? → 409 Conflict            │
│  COMPLETED?   → Replay cached response  │
│               + Idempotency-Cache: HIT  │
│  NOT FOUND?   → Save IN_PROGRESS lock   │
│               → Process request         │
│               → On success: COMPLETED   │
│               → On 5xx/ex: DELETE lock  │
└─────────────────────────────────────────┘
```

### 📖 Detailed Request Flow Documentation

For a complete class-by-class trace of every request scenario (with Mermaid sequence diagrams), see:

| Document | Description |
|----------|-------------|
| [Mode A — Global Filter Flow](docs/MODE_A_REQUEST_FLOW.md) | 5 scenarios: no header, new request, duplicate completed, duplicate in-progress, controller crash |
| [Mode B — Interceptor Flow](docs/MODE_B_REQUEST_FLOW.md) | 6 scenarios: no header, no annotation, new request, duplicate completed, duplicate in-progress, controller crash |
| [Edge Cases Handled](docs/EDGE_CASES.md) | Comprehensive list of edge cases handled across security, concurrency, failure, and configuration |

---

## 🔒 Security

- **Header stripping**: `Set-Cookie` and `Set-Cookie2` are removed before caching to prevent session fixation.
- **User scoping**: when Spring Security is present, the authenticated principal is mixed into the fingerprint so users cannot replay each other's idempotency records.
- **TTL enforcement**: all cached entries have an absolute expiry time; expired entries are treated as absent.

---

## 📐 Architecture

The library is built on classic Design Patterns:

| Pattern | Where |
|---|---|
| **Strategy** | `IdempotencyStore` interface — swap InMemory ↔ Redis without changing callers |
| **Chain of Responsibility** | `IdempotencyFilter` sits in the Servlet filter chain |
| **Decorator** | `ContentCachingResponseWrapper` captures the response without modifying it |
| **Template Method** | `IdempotencyProcessor` defines the algorithm; filter/interceptor are transport adapters |
| **Sealed Hierarchy** | `ProcessResult` — exhaustive, compile-time-safe outcome modelling |

---

## 🧪 Running Tests

```bash
./mvnw test
```

Test coverage includes:

| Test | What is tested |
|---|---|
| `InMemoryIdempotencyStoreTest` | LRU eviction, TTL expiry, concurrent lock acquisition (20 threads) |
| `RedisIdempotencyStoreTest` | Atomic SET NX EX, concurrent contention, deserialisation failure |
| `FingerprintGeneratorTest` | Determinism, per-field uniqueness, null principal handling |
| `IdempotencyProcessorTest` | All 4 state-machine branches + completeSuccess + releaseLock |
| `IdempotencyFilterIntegrationTest` | Pass-through, cache HIT, 409 concurrency (10 threads), 5xx release |
| `IdempotencyInterceptorIntegrationTest` | Annotation detection, TTL override, HIT replay, 5xx release, key isolation |

---

## 🏗️ Building for Maven Central

```bash
# Build with sources + Javadoc JARs
./mvnw package -DskipTests

# Sign and deploy to OSSRH staging
./mvnw deploy -P release
```

Requires `~/.m2/settings.xml` with OSSRH credentials and a GPG key configured.

---

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feat/my-feature`)
3. Write tests for your change
4. Open a Pull Request

---

## 📄 License

Licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
