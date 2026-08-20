package com.example.shortener.service;

import com.example.shortener.config.ShortenerProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;

/**
 * Derives a pseudonymous visitor identifier from a client address.
 *
 * <p>Raw IP addresses are personal data in most jurisdictions this would run in, and
 * a shortener has no business keeping a durable record of who opened which link. The
 * salt is rotated daily, which keeps "unique visitors today" answerable while making
 * the identifier useless for tracking a person across days or correlating them
 * between links.
 */
@Component
public class VisitorHasher {

    private final String secret;

    public VisitorHasher(ShortenerProperties properties) {
        this.secret = properties.getCodeSecret();
    }

    public String hash(String clientAddress) {
        if (clientAddress == null || clientAddress.isBlank()) {
            return null;
        }
        String dailySalt = LocalDate.now(ZoneOffset.UTC).toString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(dailySalt.getBytes(StandardCharsets.UTF_8));
            digest.update(secret.getBytes(StandardCharsets.UTF_8));
            digest.update(clientAddress.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
