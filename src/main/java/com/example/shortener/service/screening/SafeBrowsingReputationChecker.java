package com.example.shortener.service.screening;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.resilience.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Checks a destination against Google Safe Browsing.
 *
 * <p>Disabled unless an API key is configured, so the service runs without a third-party
 * dependency and gains one by configuration rather than by code change.
 *
 * <p>Everything here is built around the assumption that this call will eventually be slow or
 * fail, because it is a network call to someone else's service sitting on our creation path.
 * Connect and read timeouts are tight, a circuit breaker stops a sustained outage from paying
 * that timeout on every request, and no failure escapes as an exception — an unreachable
 * provider yields {@code UNKNOWN}, which the policy layer decides what to do with.
 */
@Component
public class SafeBrowsingReputationChecker implements UrlReputationChecker {

    private static final Logger log = LoggerFactory.getLogger(SafeBrowsingReputationChecker.class);

    private static final List<String> THREAT_TYPES =
            List.of("MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE", "POTENTIALLY_HARMFUL_APPLICATION");

    private final ShortenerProperties.Screening config;
    private final CircuitBreaker breaker;
    private final RestClient client;

    public SafeBrowsingReputationChecker(ShortenerProperties properties,
                                         @Qualifier("screeningCircuitBreaker") CircuitBreaker breaker) {
        this.config = properties.getScreening();
        this.breaker = breaker;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) config.getProviderTimeout().toMillis());
        factory.setReadTimeout((int) config.getProviderTimeout().toMillis());
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String name() {
        return "google-safe-browsing";
    }

    @Override
    public boolean enabled() {
        return config.getSafeBrowsingApiKey() != null && !config.getSafeBrowsingApiKey().isBlank();
    }

    @Override
    public Reputation check(String url) {
        if (!enabled()) {
            return Reputation.unknown("provider not configured");
        }
        if (!breaker.allowRequest()) {
            return Reputation.unknown("provider circuit open");
        }

        Map<String, Object> body = Map.of(
                "client", Map.of("clientId", "url-shortener", "clientVersion", "1.0"),
                "threatInfo", Map.of(
                        "threatTypes", THREAT_TYPES,
                        "platformTypes", List.of("ANY_PLATFORM"),
                        "threatEntryTypes", List.of("URL"),
                        "threatEntries", List.of(Map.of("url", url))));

        try {
            Map<?, ?> response = client.post()
                    .uri(config.getSafeBrowsingEndpoint() + "?key=" + config.getSafeBrowsingApiKey())
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            breaker.recordSuccess();

            // An empty body is the API's way of saying "no threats matched", so absence of
            // matches is a clean verdict rather than an inconclusive one.
            Object matches = response == null ? null : response.get("matches");
            if (matches instanceof List<?> list && !list.isEmpty()) {
                return Reputation.blocked("flagged by Safe Browsing: " + describe(list));
            }
            return Reputation.clean();
        } catch (RuntimeException e) {
            breaker.recordFailure();
            log.warn("Safe Browsing lookup failed: {}", e.toString());
            return Reputation.unknown("provider unreachable");
        }
    }

    private String describe(List<?> matches) {
        return matches.stream()
                .filter(Map.class::isInstance)
                .map(match -> String.valueOf(((Map<?, ?>) match).get("threatType")))
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse("unspecified threat");
    }
}
