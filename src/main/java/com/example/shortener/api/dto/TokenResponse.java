package com.example.shortener.api.dto;

import java.util.List;

public record TokenResponse(String accessToken,
                            String tokenType,
                            long expiresIn,
                            List<String> roles) {

    public static TokenResponse bearer(String value, long expiresInSeconds, List<String> roles) {
        return new TokenResponse(value, "Bearer", expiresInSeconds, roles);
    }
}
