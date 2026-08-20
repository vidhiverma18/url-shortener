package com.example.shortener.api;

import com.example.shortener.service.screening.LinkRescanJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Screening, quarantine, the audit trail and response hardening.
 *
 * <p>Redis is deliberately unreachable in this profile, so anything depending on it degrades
 * here rather than being exercised. That is the point for revocation: the outage path is the
 * one with a security consequence, so it is asserted directly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityControlsIntegrationTest {

    private static PostgreSQLContainer<?> postgres;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String suppliedUrl = System.getenv("TEST_DATASOURCE_URL");
        if (suppliedUrl != null && !suppliedUrl.isBlank()) {
            registry.add("spring.datasource.url", () -> suppliedUrl);
            registry.add("spring.datasource.username",
                    () -> System.getenv().getOrDefault("TEST_DATASOURCE_USERNAME", "shortener"));
            registry.add("spring.datasource.password",
                    () -> System.getenv().getOrDefault("TEST_DATASOURCE_PASSWORD", "shortener_local_dev"));
            return;
        }
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LinkRescanJob rescanJob;

    private String alice;
    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("TRUNCATE click_events, short_links RESTART IDENTITY CASCADE");
        jdbcTemplate.execute("DELETE FROM blocked_domains");
        alice = tokenFor("alice", "alice-password");
        admin = tokenFor("admin", "admin-password");
    }

    // --- destination screening --------------------------------------------------------

    @Test
    @DisplayName("a blocklisted destination is refused with 422 and never reaches the database")
    void blockedDestinationIsRefused() throws Exception {
        mockMvc.perform(create(alice, "https://malware-test.example/payload"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Destination refused"));

        // No id burned, no row written: a refused request should cost nothing to clean up.
        assertThat(countLinks()).isZero();
    }

    @Test
    @DisplayName("blocking a domain covers every subdomain of it")
    void subdomainsAreCovered() throws Exception {
        mockMvc.perform(create(alice, "https://login.malware-test.example/x"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("the refusal says nothing about which check caught it")
    void refusalLeaksNoScreeningDetail() throws Exception {
        String body = mockMvc.perform(create(alice, "https://phishing-test.example/x"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();

        // Naming the feed would turn screening into an oracle for tuning the next attempt.
        assertThat(body).doesNotContain("blocklist").doesNotContain("local-blocklist");
    }

    @Test
    @DisplayName("an unlisted destination is allowed and recorded as screened")
    void cleanDestinationIsAllowed() throws Exception {
        String code = codeOf(mockMvc.perform(create(alice, "https://example.com/fine"))
                .andExpect(status().isCreated()).andReturn());

        String status = jdbcTemplate.queryForObject(
                "SELECT screening_status FROM short_links WHERE code = ?", String.class, code);
        assertThat(status).isEqualTo("CLEAN");
    }

    // --- runtime blocklist and quarantine ---------------------------------------------

    @Test
    @DisplayName("an operator can block a domain at runtime without a redeploy")
    void operatorCanBlockADomainAtRuntime() throws Exception {
        mockMvc.perform(create(alice, "https://later-bad.example/x"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/admin/blocked-domains")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"later-bad.example\",\"reason\":\"reported\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(create(alice, "https://later-bad.example/another"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("a link whose destination turns hostile is quarantined by the sweep and answers 410")
    void rescanQuarantinesLinksThatTurnHostile() throws Exception {
        String code = codeOf(mockMvc.perform(create(alice, "https://turns-bad.example/landing"))
                .andExpect(status().isCreated()).andReturn());

        // Redirects normally while the destination is still considered clean.
        mockMvc.perform(get("/" + code)).andExpect(status().isFound());

        mockMvc.perform(post("/api/v1/admin/blocked-domains")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"turns-bad.example\",\"reason\":\"compromised\"}"))
                .andExpect(status().isCreated());

        // Force the screening timestamp to be stale so the link is due for a rescan.
        jdbcTemplate.update("UPDATE short_links SET screened_at = NOW() - INTERVAL '48 hours' WHERE code = ?", code);
        assertThat(rescanJob.rescanBatch()).isEqualTo(1);

        // 410 rather than 404: the visitor followed a link that was taken down, and telling
        // them so is the difference between a warning and an apparently broken site.
        mockMvc.perform(get("/" + code)).andExpect(status().isGone());
    }

    @Test
    @DisplayName("a quarantine is recorded in the audit trail with its cause")
    void quarantineIsAudited() throws Exception {
        String code = codeOf(mockMvc.perform(create(alice, "https://also-bad.example/x"))
                .andExpect(status().isCreated()).andReturn());
        // Through the endpoint rather than by SQL: the checker answers from a refreshed
        // snapshot, so a direct insert would not be visible until the next refresh.
        mockMvc.perform(post("/api/v1/admin/blocked-domains")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"also-bad.example\",\"reason\":\"test\"}"))
                .andExpect(status().isCreated());
        jdbcTemplate.update("UPDATE short_links SET screened_at = NOW() - INTERVAL '48 hours' WHERE code = ?", code);

        rescanJob.rescanBatch();

        assertThat(auditActionsFor(code)).contains("LINK_QUARANTINED");
    }

    // --- audit trail ------------------------------------------------------------------

    @Test
    @DisplayName("an administrator reading someone else's link is recorded")
    void adminAccessIsAudited() throws Exception {
        String code = codeOf(mockMvc.perform(create(alice, "https://example.com/watched"))
                .andExpect(status().isCreated()).andReturn());

        mockMvc.perform(get("/api/v1/links/" + code)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk());

        assertThat(auditActionsFor(code)).contains("ADMIN_LINK_ACCESS");
    }

    @Test
    @DisplayName("an owner reading their own link is not recorded")
    void ordinaryAccessIsNotAudited() throws Exception {
        String code = codeOf(mockMvc.perform(create(alice, "https://example.com/mine"))
                .andExpect(status().isCreated()).andReturn());

        mockMvc.perform(get("/api/v1/links/" + code)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isOk());

        // An audit trail that records everything records nothing, because nobody reads it.
        assertThat(auditActionsFor(code)).doesNotContain("ADMIN_LINK_ACCESS");
    }

    @Test
    @DisplayName("retiring a link is recorded")
    void retirementIsAudited() throws Exception {
        String code = codeOf(mockMvc.perform(create(alice, "https://example.com/retire-me"))
                .andExpect(status().isCreated()).andReturn());

        mockMvc.perform(delete("/api/v1/links/" + code)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isNoContent());

        assertThat(auditActionsFor(code)).contains("LINK_RETIRED");
    }

    @Test
    @DisplayName("a failed login is recorded without revealing whether the account exists")
    void failedLoginIsAudited() throws Exception {
        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT target_id, outcome, detail FROM audit_events WHERE action = 'AUTH_FAILED'");
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).get("target_id")).isEqualTo("alice");
        assertThat(rows.get(0).get("outcome")).isEqualTo("DENIED");
        // The username tried is retained for forensics; whether it exists is not.
        assertThat(rows.get(0).get("detail")).isNull();
    }

    @Test
    @DisplayName("the audit trail cannot be edited or deleted, even by the application")
    void auditTrailIsAppendOnly() {
        jdbcTemplate.update("INSERT INTO audit_events (occurred_at, actor, action, outcome) "
                + "VALUES (NOW(), 'alice', 'LINK_RETIRED', 'APPLIED')");

        // Enforced by the database, not by convention: application code can be changed by
        // whoever is covering their tracks.
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE audit_events SET actor = 'someone-else'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM audit_events"))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("only administrators can read the audit trail")
    void auditTrailIsAdminOnly() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/audit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk());
    }

    // --- revocation under outage ------------------------------------------------------

    @Test
    @DisplayName("revocation reports failure rather than claiming success when the store is down")
    void revocationFailureIsHonest() throws Exception {
        // Redis is unreachable in this profile, which is exactly the case worth asserting:
        // answering 200 would tell the caller a leaked token is dead when it is still valid.
        mockMvc.perform(post("/api/v1/auth/revoke")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.revoked").value(false));
    }

    @Test
    @DisplayName("tokens still work while revocation is unavailable")
    void revocationOutageDoesNotBreakAuthentication() throws Exception {
        // The documented trade-off: a Redis outage suspends revocation, not the service.
        mockMvc.perform(create(alice, "https://example.com/still-working"))
                .andExpect(status().isCreated());
    }

    // --- response hardening -----------------------------------------------------------

    @Test
    @DisplayName("security headers are present on API responses")
    void apiResponsesCarrySecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Permissions-Policy",
                        org.hamcrest.Matchers.containsString("geolocation=()")));
    }

    @Test
    @DisplayName("the public redirect is also framed-protected")
    void redirectCarriesFrameProtection() throws Exception {
        String code = codeOf(mockMvc.perform(create(alice, "https://example.com/framed"))
                .andExpect(status().isCreated()).andReturn());

        // The redirect is the one endpoint an attacker would want to frame, to harvest the
        // click without the address bar showing where it went.
        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
    }

    @Test
    @DisplayName("HSTS is withheld over plain HTTP and would be sent behind TLS")
    void hstsFollowsTheRequestScheme() throws Exception {
        // Spring only emits HSTS on secure requests. Asserting its absence over HTTP records
        // why forward-headers-strategy matters: without the proxy's X-Forwarded-Proto the
        // header is configured and never sent.
        mockMvc.perform(get("/api/v1/admin/audit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));

        mockMvc.perform(get("/api/v1/admin/audit")
                        .secure(true)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(header().string("Strict-Transport-Security",
                        org.hamcrest.Matchers.containsString("max-age=31536000")));
    }

    // --- helpers ----------------------------------------------------------------------

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(
            String token, String url) {
        return post("/api/v1/links")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + url + "\"}");
    }

    private List<String> auditActionsFor(String targetId) {
        return jdbcTemplate.queryForList(
                "SELECT action FROM audit_events WHERE target_id = ?", String.class, targetId);
    }

    private int countLinks() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM short_links", Integer.class);
        return count == null ? 0 : count;
    }

    private String tokenFor(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String codeOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asText();
    }
}
