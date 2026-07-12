package com.avi.idempotency.config;

import com.avi.idempotency.core.IdempotencyProcessor;
import com.avi.idempotency.filter.IdempotencyFilter;
import com.avi.idempotency.filter.IdempotencyResponseWrapFilter;
import com.avi.idempotency.interceptor.IdempotencyInterceptor;
import com.avi.idempotency.interceptor.IdempotencyWebMvcConfigurer;
import com.avi.idempotency.store.IdempotencyStore;
import com.avi.idempotency.store.InMemoryIdempotencyStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot Auto-Configuration for the idempotency library.
 *
 * <p>Registers all necessary beans with {@code @ConditionalOnMissingBean} so that
 * any bean declared by the consuming application takes precedence — giving full
 * control to the user without requiring them to exclude this auto-configuration.</p>
 *
 * <h2>What is auto-configured</h2>
 * <ul>
 *   <li>{@link IdempotencyConfig} — defaults (header name, TTL, capacity)</li>
 *   <li>{@link IdempotencyStore} — in-memory LRU store (no Redis required)</li>
 *   <li>{@link IdempotencyProcessor} — stateless processing engine</li>
 *   <li>{@link IdempotencyInterceptor} — Mode B interceptor</li>
 *   <li>{@link IdempotencyWebMvcConfigurer} — registers the interceptor with MVC</li>
 * </ul>
 *
 * <p><strong>Note:</strong> {@link IdempotencyFilter} (Mode A) is <em>not</em> auto-registered
 * as a servlet filter because URL patterns are application-specific.
 * Users register it manually via {@code FilterRegistrationBean}.</p>
 *
 * <h2>Overriding defaults</h2>
 * <pre>{@code
 * @Bean
 * public IdempotencyConfig myConfig() {
 *     return new IdempotencyConfig("X-Request-Id", 3600, 5000);
 * }
 *
 * // Or swap to Redis store:
 * @Bean
 * public IdempotencyStore redisStore(StringRedisTemplate t) {
 *     return new RedisIdempotencyStore(t);
 * }
 * }</pre>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "idempotency", name = "enabled", matchIfMissing = true)
public class IdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyConfig idempotencyConfig() {
        return IdempotencyConfig.defaults();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyStore idempotencyStore(IdempotencyConfig config) {
        return new InMemoryIdempotencyStore(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyProcessor idempotencyProcessor() {
        return new IdempotencyProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyInterceptor idempotencyInterceptor(IdempotencyStore store,
                                                          IdempotencyConfig config,
                                                          IdempotencyProcessor processor) {
        return new IdempotencyInterceptor(store, config, processor);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyWebMvcConfigurer idempotencyWebMvcConfigurer(
            IdempotencyInterceptor interceptor) {
        return new IdempotencyWebMvcConfigurer(interceptor);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyResponseWrapFilter idempotencyResponseWrapFilter(IdempotencyConfig config) {
        return new IdempotencyResponseWrapFilter(config);
    }
}
