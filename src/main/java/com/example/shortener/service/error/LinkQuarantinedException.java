package com.example.shortener.service.error;

/**
 * The link existed and was disabled because its destination was later found hostile.
 *
 * <p>Answered with 410 rather than the 404 used for an unknown code. The distinction is
 * deliberate: a retired or unknown code should reveal nothing, but a visitor who followed a
 * link that has since been taken down is not the adversary, and telling them the link was
 * removed for safety is the difference between a useful warning and an apparently broken site.
 */
public class LinkQuarantinedException extends RuntimeException {

    public LinkQuarantinedException() {
        super("This link was disabled because its destination was flagged as unsafe");
    }
}
