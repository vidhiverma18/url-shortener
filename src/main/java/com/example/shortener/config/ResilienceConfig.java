package com.example.shortener.config;

import com.example.shortener.resilience.CircuitBreaker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResilienceConfig {

    /**
     * One breaker for Redis, shared by the cache and the rate limiter.
     *
     * <p>Scoped to the dependency rather than the call site on purpose. Both components
     * talk to the same server, so a failure observed by either is evidence about that
     * server, and pooling it means the second component never has to rediscover an outage
     * the first has already established.
     */
    @Bean
    public CircuitBreaker redisCircuitBreaker(ShortenerProperties properties) {
        return new CircuitBreaker("redis",
                properties.getCacheCircuitFailureThreshold(),
                properties.getCacheCircuitCooldown());
    }
}
