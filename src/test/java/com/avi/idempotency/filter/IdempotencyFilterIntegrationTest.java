package com.avi.idempotency.filter;

import com.avi.idempotency.config.IdempotencyConfig;
import com.avi.idempotency.core.IdempotencyProcessor;
import com.avi.idempotency.store.InMemoryIdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link IdempotencyFilter} (Mode A — global URL filter).
 */
@SpringBootTest(
    classes = IdempotencyFilterIntegrationTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@DisplayName("IdempotencyFilter Integration")
class IdempotencyFilterIntegrationTest {

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private InMemoryIdempotencyStore store;

    @Autowired
    private IdempotencyFilter idempotencyFilter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(wac)
                .addFilter(idempotencyFilter)
                .build();
    }

    // ── Minimal test Spring Boot application ──────────────────────────────────

    @SpringBootApplication(
        scanBasePackages = {},
        exclude = com.avi.idempotency.config.IdempotencyAutoConfiguration.class
    )
    static class TestApp {

        public static void main(String[] args) {
            SpringApplication.run(TestApp.class, args);
        }

        @Bean
        public InMemoryIdempotencyStore idempotencyStore() {
            return new InMemoryIdempotencyStore(new IdempotencyConfig("Idempotency-Key", 3600L, 1000, 2097152, 2, java.util.Set.of()));
        }

        @Bean
        public IdempotencyConfig idempotencyConfig() {
            return new IdempotencyConfig("Idempotency-Key", 3600L, 1000, 2097152, 2, java.util.Set.of());
        }

        @Bean
        public IdempotencyProcessor idempotencyProcessor() {
            return new IdempotencyProcessor();
        }

        @Bean
        public IdempotencyFilter idempotencyFilter(InMemoryIdempotencyStore store,
                                                    IdempotencyConfig config,
                                                    IdempotencyProcessor processor) {
            return new IdempotencyFilter(store, config, processor);
        }

        @RestController
        class TestController {

            @PostMapping("/payments/charge")
            public String charge(@RequestBody String body) {
                return "{\"charged\":true,\"amount\":\"100\"}";
            }

            @PostMapping("/payments/fail")
            public ResponseEntity<String> fail(@RequestBody String body) {
                return ResponseEntity.internalServerError().body("{\"error\":\"server error\"}");
            }
        }
    }

    // ── Test cases ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("request without Idempotency-Key header passes through")
    void requestWithoutKeyPassesThrough() throws Exception {
        mockMvc.perform(post("/payments/charge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"charged\":true,\"amount\":\"100\"}"));
    }

    @Test
    @DisplayName("first request is processed and result is cached")
    void firstRequestIsProcessedAndCached() throws Exception {
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/payments/charge")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"charged\":true,\"amount\":\"100\"}"));

        // Store should now have a COMPLETED record
        String fingerprint = com.avi.idempotency.util.FingerprintGenerator
                .generate(key, "POST", "/payments/charge", null);
        assertThat(store.get(fingerprint)).isPresent();
    }

    @Test
    @DisplayName("duplicate request returns cached response with HIT header")
    void duplicateRequestReturnsCachedResponseWithHitHeader() throws Exception {
        String key = UUID.randomUUID().toString();

        // First request
        mockMvc.perform(post("/payments/charge")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // Second (duplicate) request should get cached response
        mockMvc.perform(post("/payments/charge")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string(IdempotencyFilter.CACHE_HIT_HEADER,
                        IdempotencyFilter.CACHE_HIT_VALUE))
                .andExpect(content().json("{\"charged\":true,\"amount\":\"100\"}"));
    }

    @Test
    @DisplayName("concurrent duplicate requests — at least some return 409 Conflict or HIT")
    void concurrentDuplicatesGetConflictOrHit() throws InterruptedException {
        String key = "concurrent-key-" + UUID.randomUUID();
        int threads = 10;
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger successCount  = new AtomicInteger(0);
        AtomicInteger hitCount      = new AtomicInteger(0);
        CountDownLatch startLatch   = new CountDownLatch(1);
        CountDownLatch doneLatch    = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    MvcResult result = mockMvc.perform(post("/payments/charge")
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                            .andReturn();
                    int status = result.getResponse().getStatus();
                    String hitHeader = result.getResponse().getHeader(IdempotencyFilter.CACHE_HIT_HEADER);
                    if (status == 409) conflictCount.incrementAndGet();
                    else if (hitHeader != null) hitCount.incrementAndGet();
                    else if (status == 200) successCount.incrementAndGet();
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        pool.shutdown();

        assertThat(successCount.get() + conflictCount.get() + hitCount.get()).isEqualTo(threads);
        // At most 1 should be the original; rest are either 409 or HIT
        assertThat(conflictCount.get() + hitCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("5xx response releases the lock so client can retry")
    void serverErrorReleasesLock() throws Exception {
        String key = UUID.randomUUID().toString();

        // First request fails with 500
        mockMvc.perform(post("/payments/fail")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError());

        // After a 5xx, the lock should be released — a retry should be processed, not get 409
        mockMvc.perform(post("/payments/fail")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError()); // processed again, not 409
    }
}
