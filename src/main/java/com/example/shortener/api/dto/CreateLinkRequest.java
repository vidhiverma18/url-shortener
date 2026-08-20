package com.example.shortener.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * @param url       destination to shorten; validated in depth by UrlValidator
 * @param alias     optional caller-chosen code
 * @param expiresAt optional expiry; null means the link never expires
 */
public record CreateLinkRequest(
        @NotBlank(message = "url is required")
        @Size(max = 2048, message = "url must be at most 2048 characters")
        String url,

        @Size(min = 3, max = 32, message = "alias must be between 3 and 32 characters")
        String alias,

        Instant expiresAt) {
}
