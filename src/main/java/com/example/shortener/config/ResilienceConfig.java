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

    /**
     * A separate breaker for the reputation provider.
     *
     * <p>Not shared with Redis for the same reason the Redis one is shared between cache and
     * limiter: a breaker is evidence about one dependency. A third party being slow says
     * nothing about Redis, and pooling them would let an outage at Google disable the local
     * cache, converting someone else's incident into ours.
     */
    @Bean
    public CircuitBreaker screeningCircuitBreaker(ShortenerProperties properties) {
        return new CircuitBreaker("url-reputation",
                properties.getCacheCircuitFailureThreshold(),
                properties.getCacheCircuitCooldown());
    }
}
