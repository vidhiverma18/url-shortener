package com.example.shortener.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Determines the caller's network address, for visitor hashing and for throttling requests
 * that have no principal yet.
 *
 * <p>The forwarded header is consulted only for its <em>first</em> entry, and only because
 * this service is expected to sit behind a load balancer that overwrites it. A
 * client-supplied {@code X-Forwarded-For} is trivially spoofed, so this is a convenience for
 * correct deployments rather than a security control — which is why it is no longer used to
 * identify who owns a link. That is now the authenticated principal (ADR-008).
 */
@Component
public class ClientKeyResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    public String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
