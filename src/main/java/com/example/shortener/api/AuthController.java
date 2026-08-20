package com.example.shortener.api;

import com.example.shortener.api.dto.TokenRequest;
import com.example.shortener.api.dto.TokenResponse;
import com.example.shortener.security.JwtIssuer;
import com.example.shortener.security.TokenRevocationService;
import com.example.shortener.security.audit.AuditAction;
import com.example.shortener.security.audit.AuditLog;
import com.example.shortener.service.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Exchange credentials for a bearer token, and withdraw tokens")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final JwtIssuer jwtIssuer;
    private final RateLimiter rateLimiter;
    private final ClientKeyResolver clientKeyResolver;
    private final TokenRevocationService revocations;
    private final AuditLog audit;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtIssuer jwtIssuer,
                          RateLimiter rateLimiter,
                          ClientKeyResolver clientKeyResolver,
                          TokenRevocationService revocations,
                          AuditLog audit) {
        this.authenticationManager = authenticationManager;
        this.jwtIssuer = jwtIssuer;
        this.rateLimiter = rateLimiter;
        this.clientKeyResolver = clientKeyResolver;
        this.revocations = revocations;
        this.audit = audit;
    }

    @PostMapping("/token")
    @Operation(summary = "Exchange a username and password for a bearer token")
    public TokenResponse token(@Valid @RequestBody TokenRequest request, HttpServletRequest httpRequest) {
        // Throttled by address, not by username: limiting per username lets an attacker
        // lock a known account out simply by failing to log in as them.
        String address = clientKeyResolver.clientAddress(httpRequest);
        if (!rateLimiter.tryAcquireLogin(address).allowed()) {
            throw new RateLimitExceededException(1);
        }

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));

            JwtIssuer.IssuedToken token = jwtIssuer.issue(authentication);
            audit.recordAs(authentication.getName(), AuditAction.TOKEN_ISSUED, AuditAction.OUTCOME_APPLIED,
                    AuditAction.TARGET_TOKEN, token.tokenId(), null);
            return TokenResponse.bearer(token.value(), token.expiresInSeconds(), token.roles());
        } catch (AuthenticationException e) {
            // Logged without the reason and without the password, and answered with a
            // single generic message so the response cannot be used to tell "no such user"
            // apart from "wrong password".
            log.info("Failed authentication attempt for user '{}'", request.username());
            // The audit trail does record which username was tried, because reconstructing a
            // credential-stuffing run afterwards is impossible without it. It never records
            // whether that username exists, which is the part that would leak.
            audit.recordQuietly(AuditAction.AUTH_FAILED, AuditAction.OUTCOME_DENIED,
                    AuditAction.TARGET_USER, request.username(), null);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
    }

    @PostMapping("/revoke")
    @Operation(summary = "Withdraw the bearer token used to make this request")
    public ResponseEntity<Map<String, Object>> revoke(Authentication authentication) {
        Jwt jwt = jwtOf(authentication);
        boolean stored = revocations.revokeToken(jwt.getId(), jwt.getExpiresAt());
        audit.record(AuditAction.TOKEN_REVOKED,
                stored ? AuditAction.OUTCOME_APPLIED : AuditAction.OUTCOME_DENIED,
                AuditAction.TARGET_TOKEN, jwt.getId(), stored ? null : "revocation store unavailable");

        if (!stored) {
            // Answering 200 here would be a lie with security consequences: the caller would
            // believe a leaked token is dead when it is still valid until it expires.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "revoked", false,
                    "detail", "The revocation store is unavailable. This token remains valid until it expires.",
                    "expiresAt", String.valueOf(jwt.getExpiresAt())));
        }
        return ResponseEntity.ok(Map.of("revoked", true, "tokenId", jwt.getId()));
    }

    @PostMapping("/revoke-all")
    @Operation(summary = "Withdraw every token currently issued to the calling principal")
    public ResponseEntity<Map<String, Object>> revokeAll(Authentication authentication) {
        String subject = authentication.getName();
        boolean stored = revocations.revokeAllFor(subject);
        audit.record(AuditAction.ALL_TOKENS_REVOKED,
                stored ? AuditAction.OUTCOME_APPLIED : AuditAction.OUTCOME_DENIED,
                AuditAction.TARGET_USER, subject, stored ? null : "revocation store unavailable");

        if (!stored) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "revoked", false,
                    "detail", "The revocation store is unavailable. Existing tokens remain valid until they expire."));
        }
        return ResponseEntity.ok(Map.of("revoked", true, "subject", subject));
    }

    private Jwt jwtOf(Authentication authentication) {
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This endpoint requires a bearer token");
    }
}
