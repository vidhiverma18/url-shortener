package com.example.shortener.api;

import com.example.shortener.domain.AuditEvent;
import com.example.shortener.domain.BlockedDomain;
import com.example.shortener.repository.AuditEventRepository;
import com.example.shortener.repository.BlockedDomainRepository;
import com.example.shortener.security.audit.AuditAction;
import com.example.shortener.security.audit.AuditLog;
import com.example.shortener.service.screening.BlocklistReputationChecker;
import com.example.shortener.service.screening.LinkRescanJob;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Operator surface for the security controls.
 *
 * <p>Every route requires {@code ROLE_ADMIN} through {@link PreAuthorize} rather than relying
 * only on the path rule in the filter chain. Two independent checks means a future edit to
 * either one cannot quietly expose the audit trail on its own.
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Administration", description = "Audit trail and blocklist management")
public class AdminController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AuditEventRepository auditEvents;
    private final BlockedDomainRepository blockedDomains;
    private final BlocklistReputationChecker blocklist;
    private final AuditLog audit;
    private final LinkRescanJob rescanJob;

    public AdminController(AuditEventRepository auditEvents,
                           BlockedDomainRepository blockedDomains,
                           BlocklistReputationChecker blocklist,
                           AuditLog audit,
                           LinkRescanJob rescanJob) {
        this.auditEvents = auditEvents;
        this.blockedDomains = blockedDomains;
        this.blocklist = blocklist;
        this.audit = audit;
        this.rescanJob = rescanJob;
    }

    @GetMapping("/audit")
    @Operation(summary = "Read the audit trail, newest first, optionally filtered by action or actor")
    public List<AuditEntry> auditTrail(@RequestParam(required = false) String action,
                                       @RequestParam(required = false) String actor,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));

        Page<AuditEvent> results;
        if (action != null && !action.isBlank()) {
            results = auditEvents.findByActionOrderByOccurredAtDesc(action, pageable);
        } else if (actor != null && !actor.isBlank()) {
            results = auditEvents.findByActorOrderByOccurredAtDesc(actor.toLowerCase(Locale.ROOT), pageable);
        } else {
            results = auditEvents.findByOrderByOccurredAtDesc(pageable);
        }
        return results.getContent().stream().map(AuditEntry::from).toList();
    }

    @PostMapping("/blocked-domains")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Block a destination host and every subdomain of it, effective within a minute")
    public BlockedDomainResponse block(@Valid @RequestBody BlockDomainRequest request,
                                       Authentication authentication) {
        String domain = request.domain().toLowerCase(Locale.ROOT).trim();
        BlockedDomain saved = blockedDomains.save(
                new BlockedDomain(domain, request.reason(), authentication.getName()));

        // Applied immediately rather than waiting for the next refresh: an operator blocking a
        // domain mid-incident should not have to wonder whether it has taken effect yet.
        blocklist.refresh();

        audit.record(AuditAction.DOMAIN_BLOCKED, AuditAction.OUTCOME_APPLIED,
                AuditAction.TARGET_DOMAIN, domain, request.reason());
        return new BlockedDomainResponse(saved.getDomain(), saved.getReason(), saved.getAddedBy(), saved.getAddedAt());
    }

    @GetMapping("/blocked-domains")
    @Operation(summary = "List blocked destination hosts")
    public List<BlockedDomainResponse> blockedDomains() {
        return blockedDomains.findAll().stream()
                .map(d -> new BlockedDomainResponse(d.getDomain(), d.getReason(), d.getAddedBy(), d.getAddedAt()))
                .toList();
    }

    @PostMapping("/rescan")
    @Operation(summary = "Run the screening sweep now, quarantining links whose destination has turned hostile")
    public RescanResponse rescan(@RequestParam(defaultValue = "false") boolean all) {
        // The scheduled sweep only examines links older than the rescan interval, so the
        // moment an operator most needs a sweep — just after blocking a domain — is exactly
        // when it would skip the links that matter. `all` overrides the age filter.
        int quarantined = all ? rescanJob.rescanAll() : rescanJob.rescanBatch();
        audit.record(AuditAction.LINK_QUARANTINED, AuditAction.OUTCOME_OBSERVED,
                null, null, "manual sweep quarantined " + quarantined + " link(s)");
        return new RescanResponse(quarantined);
    }

    public record RescanResponse(int quarantined) {
    }

    public record BlockDomainRequest(
            @NotBlank(message = "domain is required")
            @Size(max = 255, message = "domain must be at most 255 characters")
            String domain,

            @Size(max = 255, message = "reason must be at most 255 characters")
            String reason) {
    }

    public record BlockedDomainResponse(String domain, String reason, String addedBy, Instant addedAt) {
    }

    public record AuditEntry(Instant occurredAt, String actor, String action, String targetType,
                             String targetId, String outcome, String clientIp, String detail) {

        static AuditEntry from(AuditEvent event) {
            return new AuditEntry(event.getOccurredAt(), event.getActor(), event.getAction(),
                    event.getTargetType(), event.getTargetId(), event.getOutcome(),
                    event.getClientIp(), event.getDetail());
        }
    }
}
