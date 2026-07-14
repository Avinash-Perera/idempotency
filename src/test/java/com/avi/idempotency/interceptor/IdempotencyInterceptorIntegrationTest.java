package com.avi.idempotency.interceptor;

import com.avi.idempotency.annotation.Idempotent;
import com.avi.idempotency.config.IdempotencyConfig;
import com.avi.idempotency.core.IdempotencyProcessor;
import com.avi.idempotency.filter.IdempotencyResponseWrapFilter;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link IdempotencyInterceptor} (Mode B — per-endpoint annotation).
 */
@SpringBootTest(
    classes = IdempotencyInterceptorIntegrationTest.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@DisplayName("IdempotencyInterceptor Integration")
class IdempotencyInterceptorIntegrationTest {

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private InMemoryIdempotencyStore store;

    @Autowired
    private IdempotencyResponseWrapFilter wrapFilter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(wac)
                .addFilter(wrapFilter)
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
            return new InMemoryIdempotencyStore(
                    new IdempotencyConfig("Idempotency-Key", 3600L, 1000, 2097152, 2, java.util.Set.of(), false));
        }

        @Bean
        public IdempotencyConfig idempotencyConfig() {
            return new IdempotencyConfig("Idempotency-Key", 3600L, 1000, 2097152, 2, java.util.Set.of(), false);
        }

        @Bean
        public IdempotencyProcessor idempotencyProcessor() {
            return new IdempotencyProcessor();
        }

        @Bean
        public IdempotencyInterceptor idempotencyInterceptor(InMemoryIdempotencyStore store,
                                                              IdempotencyConfig config,
                                                              IdempotencyProcessor processor) {
            return new IdempotencyInterceptor(store, config, processor);
        }

        @Bean
        public IdempotencyResponseWrapFilter idempotencyResponseWrapFilter(IdempotencyConfig config) {
            return new IdempotencyResponseWrapFilter(config);
        }

        /**
         * Registers the Spring-managed interceptor bean (not a new instance).
         */
        @Bean
        public WebMvcConfigurer idempotencyWebMvcConfigurer(IdempotencyInterceptor interceptor) {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(interceptor);
                }
            };
        }

        @RestController
        class TestController {

            @PostMapping("/orders/create")
            @Idempotent(ttlSeconds = 7200)
            public ResponseEntity<String> createOrder(@RequestBody String body) {
                return ResponseEntity.ok("{\"orderId\":\"42\",\"status\":\"created\"}");
            }

            @PostMapping("/orders/no-annotation")
            public ResponseEntity<String> noAnnotation(@RequestBody String body) {
                return ResponseEntity.ok("{\"processed\":true}");
            }

            @PostMapping("/orders/error")
            @Idempotent(ttlSeconds = 300)
            public ResponseEntity<String> failOrder(@RequestBody String body) {
                return ResponseEntity.internalServerError().body("{\"error\":\"db failure\"}");
            }
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("non-annotated endpoint is unaffected by interceptor")
    void nonAnnotatedEndpointPassesThrough() throws Exception {
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/orders/no-annotation")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"processed\":true}"));

        // No record should be created for non-annotated endpoint
        String fingerprint = com.avi.idempotency.util.FingerprintGenerator
                .generate(key, "POST", "/orders/no-annotation", null);
        assertThat(store.get(fingerprint)).isEmpty();
    }

    @Test
    @DisplayName("annotated endpoint: first request is processed")
    void annotatedEndpointProcessesFirstRequest() throws Exception {
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/orders/create")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"orderId\":\"42\",\"status\":\"created\"}"));
    }

    @Test
    @DisplayName("annotated endpoint: duplicate returns cached response with HIT header")
    void duplicateReturnsHitWithCustomTtl() throws Exception {
        String key = UUID.randomUUID().toString();

        // First request
        mockMvc.perform(post("/orders/create")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // Second (duplicate) request → cached HIT
        mockMvc.perform(post("/orders/create")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string(IdempotencyInterceptor.CACHE_HIT_HEADER,
                        IdempotencyInterceptor.CACHE_HIT_VALUE))
                .andExpect(content().json("{\"orderId\":\"42\",\"status\":\"created\"}"));
    }

    @Test
    @DisplayName("request without Idempotency-Key header on annotated endpoint passes through")
    void requestWithoutKeyOnAnnotatedEndpointPassesThrough() throws Exception {
        mockMvc.perform(post("/orders/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"orderId\":\"42\",\"status\":\"created\"}"));
    }

    @Test
    @DisplayName("5xx response on annotated endpoint releases lock so client can retry")
    void serverErrorReleasesLockOnAnnotatedEndpoint() throws Exception {
        String key = UUID.randomUUID().toString();

        // First request → 500
        mockMvc.perform(post("/orders/error")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError());

        // Second request → also 500 (lock was released, not 409)
        mockMvc.perform(post("/orders/error")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("different keys on same endpoint are independent")
    void differentKeysAreIndependent() throws Exception {
        String key1 = UUID.randomUUID().toString();
        String key2 = UUID.randomUUID().toString();

        mockMvc.perform(post("/orders/create")
                        .header("Idempotency-Key", key1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // key2 should also be processed (not hit the key1 cache)
        mockMvc.perform(post("/orders/create")
                        .header("Idempotency-Key", key2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyInterceptor.CACHE_HIT_HEADER));
    }
}
