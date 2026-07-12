package com.avi.idempotency.interceptor;

import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link IdempotencyInterceptor} with the Spring MVC interceptor chain.
 *
 * <p>Auto-configured by {@link com.avi.idempotency.config.IdempotencyAutoConfiguration}.
 * Users who require fine-grained URL path control can exclude this bean and register
 * the interceptor manually:</p>
 *
 * <pre>{@code
 * @Configuration
 * public class MyMvcConfig implements WebMvcConfigurer {
 *     @Autowired IdempotencyInterceptor idempotencyInterceptor;
 *
 *     @Override
 *     public void addInterceptors(InterceptorRegistry registry) {
 *         registry.addInterceptor(idempotencyInterceptor)
 *                 .addPathPatterns("/api/**")
 *                 .excludePathPatterns("/api/health");
 *     }
 * }
 * }</pre>
 */
public class IdempotencyWebMvcConfigurer implements WebMvcConfigurer {

    private final IdempotencyInterceptor interceptor;

    public IdempotencyWebMvcConfigurer(IdempotencyInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Registers for all paths — @Idempotent presence determines activation
        registry.addInterceptor(interceptor);
    }
}
