package com.example.shortener.service.error;

/**
 * The destination failed reputation screening and the link was refused.
 *
 * <p>Carries no detail about which feed flagged it or why. That belongs in the audit trail,
 * not in the response: telling someone probing the service exactly which check caught them
 * turns the screening layer into an oracle for tuning the next attempt.
 */
public class UrlBlockedException extends RuntimeException {

    public UrlBlockedException() {
        super("This destination has been flagged as unsafe and cannot be shortened");
    }
}
