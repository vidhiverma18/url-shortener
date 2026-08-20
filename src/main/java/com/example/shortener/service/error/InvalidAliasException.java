package com.example.shortener.service.error;

public class InvalidAliasException extends RuntimeException {
    public InvalidAliasException(String message) {
        super(message);
    }
}
