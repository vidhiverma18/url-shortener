package com.example.shortener.domain;

/**
 * Reputation verdict for a link's destination.
 *
 * <p>{@link #PENDING} and {@link #UNKNOWN} are kept apart on purpose. Pending means nobody
 * has looked; unknown means someone looked and could not reach an answer. They need the same
 * treatment now — let the link work, check again later — but they are different failures, and
 * collapsing them would hide a provider outage behind what looks like a normal backlog.
 */
public enum ScreeningStatus {

    PENDING,

    CLEAN,

    UNKNOWN,

    BLOCKED
}
