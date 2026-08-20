package com.example.shortener.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Produces the key used to recognise a URL that has been shortened before.
 *
 * <p>The fingerprint is <em>only</em> a lookup key. The URL that gets stored and redirected
 * to stays byte-for-byte what the caller supplied, because rewriting a destination is not
 * this service's business: query parameter order can be load-bearing, and a signed or
 * tokenised URL breaks the moment anything reorders or re-encodes it.
 *
 * <p>Normalisation is therefore deliberately conservative — only differences that RFC 3986
 * defines as equivalent for HTTP:
 *
 * <ul>
 *   <li>Scheme and host lowercased. {@code HTTPS://Example.COM} is the same resource as
 *       {@code https://example.com}; both are case-insensitive by definition.
 *   <li>A default port removed. {@code https://example.com:443/x} is
 *       {@code https://example.com/x}.
 *   <li>An empty path treated as {@code /}, which §6.2.3 makes equivalent for HTTP.
 * </ul>
 *
 * <p>Everything else is preserved exactly, and the raw path, query and fragment are used so
 * percent-encoding is never re-written. In particular a trailing slash is <b>significant</b>:
 * {@code /a} and {@code /a/} are different resources on plenty of real servers, and treating
 * them as one would hand a caller a link to somewhere they did not ask for. The cost of
 * under-normalising is a duplicate row; the cost of over-normalising is a wrong redirect.
 */
@Component
public class UrlFingerprint {

    public String of(String url) {
        return sha256(canonical(url));
    }

    String canonical(String url) {
        URI uri = URI.create(url);

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);

        int port = uri.getPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        String authority = host + (port == -1 || defaultPort ? "" : ":" + port);

        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        String fragment = uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment();

        return scheme + "://" + authority + path + query + fragment;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conforming JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
