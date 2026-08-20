package com.example.shortener.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * @param url       destination to shorten; validated in depth by UrlValidator
 * @param alias     optional caller-chosen code
 * @param expiresAt optional expiry; null means the link never expires
 * @param forceNew  mint a fresh code even if this owner already has one for this URL.
 *                  Reuse is the default, but a second code for the same destination is a
 *                  legitimate thing to want — separate campaigns pointing at one landing
 *                  page need separate click counts — and without this flag that would have
 *                  become impossible.
 */
public record CreateLinkRequest(
        @NotBlank(message = "url is required")
        @Size(max = 2048, message = "url must be at most 2048 characters")
        String url,

        @Size(min = 3, max = 32, message = "alias must be between 3 and 32 characters")
        String alias,

        Instant expiresAt,

        boolean forceNew) {
}
