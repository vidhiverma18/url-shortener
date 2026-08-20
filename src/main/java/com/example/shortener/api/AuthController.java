package com.example.shortener.api;

import com.example.shortener.api.dto.TokenRequest;
import com.example.shortener.api.dto.TokenResponse;
import com.example.shortener.security.JwtIssuer;
import com.example.shortener.service.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Exchange credentials for a bearer token")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final JwtIssuer jwtIssuer;
    private final RateLimiter rateLimiter;
    private final ClientKeyResolver clientKeyResolver;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtIssuer jwtIssuer,
                          RateLimiter rateLimiter,
                          ClientKeyResolver clientKeyResolver) {
        this.authenticationManager = authenticationManager;
        this.jwtIssuer = jwtIssuer;
        this.rateLimiter = rateLimiter;
        this.clientKeyResolver = clientKeyResolver;
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
            return TokenResponse.bearer(token.value(), token.expiresInSeconds(), token.roles());
        } catch (AuthenticationException e) {
            // Logged without the reason and without the password, and answered with a
            // single generic message so the response cannot be used to tell "no such user"
            // apart from "wrong password".
            log.info("Failed authentication attempt for user '{}'", request.username());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
    }
}
