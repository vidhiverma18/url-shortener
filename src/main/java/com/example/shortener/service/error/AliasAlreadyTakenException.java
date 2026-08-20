package com.example.shortener.service.error;

public class AliasAlreadyTakenException extends RuntimeException {
    public AliasAlreadyTakenException(String alias) {
        super("Alias '" + alias + "' is already in use");
    }
}
