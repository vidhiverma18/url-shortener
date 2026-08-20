package com.example.shortener.api;

import com.example.shortener.service.ShortLinkService;
import com.example.shortener.service.VisitorHasher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * The hot path. Everything here is arranged so that the only work between request and
 * response is one cache lookup, one optional database read, and a header write.
 */
@RestController
@Tag(name = "Redirect", description = "Public resolution of short codes")
public class RedirectController {

    private static final int MAX_USER_AGENT_LENGTH = 512;

    private final ShortLinkService service;
    private final VisitorHasher visitorHasher;
    private final ClientKeyResolver clientKeyResolver;

    public RedirectController(ShortLinkService service,
                              VisitorHasher visitorHasher,
                              ClientKeyResolver clientKeyResolver) {
        this.service = service;
        this.visitorHasher = visitorHasher;
        this.clientKeyResolver = clientKeyResolver;
    }

    /**
     * The path pattern is constrained to the short-code character set and length so
     * this handler cannot swallow requests for {@code /api/**}, {@code /actuator/**}
     * or static resources.
     */
    @GetMapping("/{code:[A-Za-z0-9_-]{3,32}}")
    @Operation(summary = "Resolve a short code and redirect to its destination")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        ShortLinkService.Resolution resolution = service.resolve(code);

        service.recordClick(
                resolution.linkId(),
                referrerHost(request.getHeader(HttpHeaders.REFERER)),
                truncate(request.getHeader(HttpHeaders.USER_AGENT)),
                visitorHasher.hash(clientKeyResolver.clientAddress(request)));

        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, resolution.url())
                // 302 keeps every click observable, so it must not be cached anywhere.
                // Without this, a shared proxy would serve the redirect on our behalf
                // and both the analytics and the ability to retire a link would silently stop working.
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, private")
                .header("X-Robots-Tag", "noindex, nofollow")
                .build();
    }

    /** Keeps only the referring host: referrer paths and queries routinely leak tokens. */
    private String referrerHost(String referer) {
        if (referer == null || referer.isBlank()) {
            return null;
        }
        try {
            String host = new URI(referer).getHost();
            return host == null || host.length() > 255 ? null : host;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_USER_AGENT_LENGTH ? value : value.substring(0, MAX_USER_AGENT_LENGTH);
    }
}
