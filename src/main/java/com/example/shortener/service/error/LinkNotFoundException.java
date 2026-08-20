package com.example.shortener.service.error;

public class LinkNotFoundException extends RuntimeException {
    public LinkNotFoundException(String code) {
        super("No active link for code '" + code + "'");
    }
}
