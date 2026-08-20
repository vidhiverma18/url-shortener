package com.example.shortener.api.dto;

import jakarta.validation.constraints.NotBlank;

public record TokenRequest(
        @NotBlank(message = "username is required") String username,
        @NotBlank(message = "password is required") String password) {

    /** Keeps credentials out of logs and crash dumps that print request objects. */
    @Override
    public String toString() {
        return "TokenRequest[username=" + username + ", password=***]";
    }
}
