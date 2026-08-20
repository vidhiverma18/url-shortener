package com.example.shortener.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects tokens that have been withdrawn since they were issued.
 *
 * <p>Runs as a token validator rather than a filter so it sits inside the same decode step as
 * signature and expiry checking. A separate filter could be reordered or bypassed by a
 * differently configured chain; this cannot be reached without also verifying the signature.
 */
public class RevocationValidator implements OAuth2TokenValidator<Jwt> {

    private static final Logger log = LoggerFactory.getLogger(RevocationValidator.class);

    private final TokenRevocationService revocations;

    public RevocationValidator(TokenRevocationService revocations) {
        this.revocations = revocations;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        TokenRevocationService.Check check = revocations.check(token);

        if (check.revoked()) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "The token is no longer valid: " + check.reason(), null));
        }

        if (!check.storeAvailable()) {
            if (revocations.failClosed()) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token",
                        "Revocation status cannot be established and this deployment is configured to fail closed",
                        null));
            }
            // Logged every time rather than sampled: this is a security control silently not
            // running, and the operator needs the outage to be as visible as an error would be.
            log.warn("Accepting token for '{}' without a revocation check — store unavailable", token.getSubject());
        }

        return OAuth2TokenValidatorResult.success();
    }
}
