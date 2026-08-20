package com.example.shortener.service;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.service.error.InvalidUrlException;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Gatekeeper for the one genuinely dangerous input this service accepts.
 *
 * <p>A URL shortener is an open redirector by design, which makes it attractive as a
 * laundering step for phishing and as a probe against private networks. Validation
 * happens once at creation time rather than on every redirect so the read path stays
 * a pure key lookup.
 */
@Component
public class UrlValidator {

    private static final int MAX_URL_LENGTH = 2048;
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /**
     * Hostnames that resolve to infrastructure rather than to the public internet.
     * The cloud metadata endpoints are listed explicitly because they are the highest
     * value SSRF target and are reachable by name in several environments.
     */
    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost",
            "metadata.google.internal",
            "metadata.goog",
            "instance-data",
            "169.254.169.254"
    );

    private static final Set<String> BLOCKED_SUFFIXES = Set.of(
            ".localhost", ".internal", ".local", ".localdomain"
    );

    private final String selfHost;

    public UrlValidator(ShortenerProperties properties) {
        this.selfHost = hostOf(properties.getBaseUrl());
    }

    /** @return the normalized URL to persist */
    public String validateAndNormalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new InvalidUrlException("URL must not be empty");
        }
        String trimmed = rawUrl.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new InvalidUrlException("URL exceeds " + MAX_URL_LENGTH + " characters");
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("URL is not well formed: " + e.getReason());
        }

        if (!uri.isAbsolute() || uri.getScheme() == null) {
            throw new InvalidUrlException("URL must be absolute and include a scheme");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new InvalidUrlException("Only http and https URLs may be shortened, got: " + scheme);
        }
        // Credentials embedded in a URL are almost always an attempt to make a hostile
        // host look like a trusted one (https://paypal.com@evil.example).
        if (uri.getUserInfo() != null) {
            throw new InvalidUrlException("URL must not contain embedded credentials");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidUrlException("URL must contain a host");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);

        if (normalizedHost.equalsIgnoreCase(selfHost)) {
            throw new InvalidUrlException("Refusing to shorten a link that points back at this service");
        }
        if (BLOCKED_HOSTS.contains(normalizedHost)) {
            throw new InvalidUrlException("Target host is not publicly routable");
        }
        for (String suffix : BLOCKED_SUFFIXES) {
            if (normalizedHost.endsWith(suffix)) {
                throw new InvalidUrlException("Target host is not publicly routable");
            }
        }
        if (isLiteralPrivateAddress(normalizedHost)) {
            throw new InvalidUrlException("Target host is not publicly routable");
        }

        return trimmed;
    }

    /**
     * Rejects literal IP addresses in reserved ranges. Deliberately does <em>not</em>
     * perform DNS resolution: a resolve-then-fetch check is defeated by DNS rebinding
     * and by records that change between creation and redirect, so it buys confidence
     * without buying safety. Egress-level controls are the real defence; see
     * docs/decisions/ADR-006-url-safety.md.
     */
    private boolean isLiteralPrivateAddress(String host) {
        String candidate = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        if (!looksLikeIpLiteral(candidate)) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(candidate);
            return address.isLoopbackAddress()
                    || address.isAnyLocalAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress();
        } catch (Exception e) {
            return true;
        }
    }

    private boolean looksLikeIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        return host.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.');
    }

    private static String hostOf(String baseUrl) {
        try {
            String host = new URI(baseUrl).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return "";
        }
    }
}
