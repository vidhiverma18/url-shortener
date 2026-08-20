package com.example.shortener.api.dto;

import com.example.shortener.domain.ShortLink;

import java.time.Instant;

public record LinkResponse(String code,
                           String shortUrl,
                           String originalUrl,
                           boolean customAlias,
                           boolean active,
                           Instant createdAt,
                           Instant expiresAt) {

    public static LinkResponse from(ShortLink link, String baseUrl) {
        return new LinkResponse(
                link.getCode(),
                baseUrl + "/" + link.getCode(),
                link.getOriginalUrl(),
                link.isCustomAlias(),
                link.isActive(),
                link.getCreatedAt(),
                link.getExpiresAt());
    }
}
