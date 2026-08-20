package com.example.shortener.api;

import com.example.shortener.api.dto.CreateLinkRequest;
import com.example.shortener.api.dto.LinkResponse;
import com.example.shortener.api.dto.LinkStatsResponse;
import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.ShortLink;
import com.example.shortener.service.RateLimiter;
import com.example.shortener.service.ShortLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/links")
@Tag(name = "Links", description = "Create, inspect and retire short links")
public class LinkController {

    private final ShortLinkService service;
    private final ShortenerProperties properties;
    private final RateLimiter rateLimiter;

    public LinkController(ShortLinkService service,
                          ShortenerProperties properties,
                          RateLimiter rateLimiter) {
        this.service = service;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    @Operation(summary = "Create a short link")
    public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request,
                                               Authentication authentication) {
        // Now keyed by the authenticated principal rather than the client address. This
        // closes the gap noted in ADR-005: an address-keyed bucket is bypassed with a pool
        // of IPs and over-restricts everyone behind shared NAT.
        String owner = authentication.getName();
        RateLimiter.Decision decision = rateLimiter.tryAcquire(owner);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }

        ShortLink link = service.create(
                request.url(), request.alias(), request.expiresAt(), owner);
        LinkResponse body = LinkResponse.from(link, properties.getBaseUrl());

        ResponseEntity.BodyBuilder response = ResponseEntity
                .created(URI.create(body.shortUrl()));
        if (decision.remainingTokens() >= 0) {
            response.header("X-RateLimit-Limit", String.valueOf(decision.capacity()));
            response.header("X-RateLimit-Remaining", String.valueOf(decision.remainingTokens()));
        }
        return response.body(body);
    }

    @GetMapping("/{code}")
    @Operation(summary = "Fetch a short link's metadata without redirecting. Owner or administrator only.")
    public LinkResponse get(@PathVariable String code, Authentication authentication) {
        return LinkResponse.from(service.requireOwned(code, principalOf(authentication)),
                properties.getBaseUrl());
    }

    @GetMapping("/{code}/stats")
    @Operation(summary = "Aggregated click analytics for a short link. Owner or administrator only.")
    public LinkStatsResponse stats(@PathVariable String code,
                                   @RequestParam(defaultValue = "30") int windowDays,
                                   Authentication authentication) {
        if (windowDays < 1 || windowDays > 365) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "windowDays must be between 1 and 365");
        }
        return LinkStatsResponse.from(
                service.stats(code, windowDays, principalOf(authentication)), properties.getBaseUrl());
    }

    @DeleteMapping("/{code}")
    @Operation(summary = "Retire a short link. Owner or administrator only. Analytics are retained.")
    public ResponseEntity<Void> deactivate(@PathVariable String code, Authentication authentication) {
        service.deactivate(code, principalOf(authentication));
        return ResponseEntity.noContent().build();
    }

    private ShortLinkService.Principal principalOf(Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        return new ShortLinkService.Principal(authentication.getName(), admin);
    }
}
