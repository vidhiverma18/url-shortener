package com.example.shortener.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Identifies the caller for rate limiting and link ownership.
 *
 * <p>Prefers an API key, falling back to the client address. The forwarded header is
 * only consulted for its <em>first</em> entry and only because this service is
 * expected to sit behind a load balancer that overwrites it; a client-supplied
 * {@code X-Forwarded-For} is trivially spoofed, so this is a convenience for correct
 * deployments rather than a security control.
 */
@Component
public class ClientKeyResolver {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String FORWARDED_FOR = "X-Forwarded-For";

    public String resolve(HttpServletRequest request) {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            return "key:" + apiKey.trim();
        }
        return "ip:" + clientAddress(request);
    }

    public String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
