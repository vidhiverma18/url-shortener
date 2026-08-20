package com.example.shortener.api;

import com.example.shortener.domain.AppUser;
import com.example.shortener.repository.AppUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
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

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authentication and authorization behaviour.
 *
 * <p>The cases worth reading are the negative ones. Anything can be made to work for the
 * authorized caller; what matters is that the unauthorized caller is refused, that the
 * refusal leaks nothing, and that the one endpoint which must stay public still is.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIntegrationTest {

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
    private AppUserRepository appUsers;

    @Autowired
    private ApplicationRunner seedDemoUsers;

    private String alice;
    private String bob;
    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("TRUNCATE click_events, short_links RESTART IDENTITY CASCADE");
        alice = tokenFor("alice", "alice-password");
        bob = tokenFor("bob", "bob-password");
        admin = tokenFor("admin", "admin-password");
    }

    // --- authentication ---------------------------------------------------------------

    @Test
    @DisplayName("the management API refuses anonymous callers with a problem document")
    void anonymousCallersAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(get("/api/v1/links/anything/stats"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a forged or tampered token is refused")
    void invalidTokensAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isUnauthorized());

        // Altering the *first* signature character, not the last. An HS256 signature is 32
        // bytes encoded as 43 base64url characters, which carry 258 bits — so the final
        // character's low 2 bits are padding and decode to nothing. Flipping it produces a
        // different-looking token with a byte-identical signature that verifies correctly,
        // which is exactly how the earlier version of this test managed to pass a forged
        // token and still report success.
        String[] parts = alice.split("\\.");
        String signature = parts[2];
        String resigned = (signature.startsWith("A") ? "B" : "A") + signature.substring(1);
        assertThat(Base64.getUrlDecoder().decode(resigned))
                .as("the tampered signature must differ in decoded bytes, not just in text")
                .isNotEqualTo(Base64.getUrlDecoder().decode(signature));

        mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + parts[0] + "." + parts[1] + "." + resigned)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token whose claims are edited to grant ADMIN is refused")
    void privilegeEscalationByClaimEditingIsRefused() throws Exception {
        String[] parts = alice.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String escalated = payload.replace("\"USER\"", "\"ADMIN\"");
        assertThat(escalated).as("the payload must actually change").isNotEqualTo(payload);

        String forged = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(escalated.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        // The signature covers header and payload, so rewriting roles invalidates it. This is
        // the attack the whole signing scheme exists to stop, so it is worth asserting
        // directly rather than inferring it from a generic "bad token" case.
        mockMvc.perform(get("/actuator/metrics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("bad credentials give the same answer whether or not the user exists")
    void credentialFailuresAreIndistinguishable() throws Exception {
        String wrongPassword = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String noSuchUser = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody-here\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Identical bodies, so the endpoint cannot be used to enumerate usernames.
        assertThat(wrongPassword).isEqualTo(noSuchUser);
    }

    // --- the public surface -----------------------------------------------------------

    @Test
    @DisplayName("the redirect stays public, because authenticating it would break every shared link")
    void redirectRemainsAnonymous() throws Exception {
        String code = codeOf(create(alice, "https://example.com/public"));

        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/public"));
    }

    @Test
    void healthIsPublicButMetricsAreNot() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());

        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk());
    }

    // --- ownership --------------------------------------------------------------------

    @Test
    @DisplayName("a link's analytics are invisible to everyone except its owner")
    void analyticsAreScopedToTheOwner() throws Exception {
        String code = codeOf(create(alice, "https://example.com/alices-campaign"));

        mockMvc.perform(get("/api/v1/links/" + code + "/stats")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isOk());

        // 404 rather than 403: a 403 would confirm the code exists, which is the single
        // bit an enumeration attacker cannot otherwise obtain.
        mockMvc.perform(get("/api/v1/links/" + code + "/stats")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bob))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a non-owner cannot retire someone else's link")
    void retirementIsScopedToTheOwner() throws Exception {
        String code = codeOf(create(alice, "https://example.com/not-bobs"));

        mockMvc.perform(delete("/api/v1/links/" + code)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bob))
                .andExpect(status().isNotFound());

        // Still resolving, so the refusal was real and not merely a different status code.
        mockMvc.perform(get("/" + code)).andExpect(status().isFound());

        mockMvc.perform(delete("/api/v1/links/" + code)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/" + code)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an administrator can inspect and retire any link")
    void administratorsBypassOwnership() throws Exception {
        String code = codeOf(create(alice, "https://example.com/admin-visible"));

        mockMvc.perform(get("/api/v1/links/" + code)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code));

        mockMvc.perform(delete("/api/v1/links/" + code)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("ownerless links from before authentication are administrator-only")
    void legacyLinksAreNotAdoptedByTheFirstCaller() throws Exception {
        String code = codeOf(create(alice, "https://example.com/legacy"));
        jdbcTemplate.update("UPDATE short_links SET created_by = NULL WHERE code = ?", code);

        mockMvc.perform(get("/api/v1/links/" + code)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/links/" + code)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the created link records its owner, which is what ownership checks rely on")
    void creationRecordsTheOwner() throws Exception {
        String code = codeOf(create(alice, "https://example.com/owned"));

        String owner = jdbcTemplate.queryForObject(
                "SELECT created_by FROM short_links WHERE code = ?", String.class, code);
        assertThat(owner).isEqualTo("alice");
    }

    // --- the demo console -------------------------------------------------------------

    @Test
    @DisplayName("the console is served anonymously, since it holds no secrets of its own")
    void consoleIsPublic() throws Exception {
        // Spring Boot answers "/" by forwarding to the welcome page, so MockMvc records the
        // forward target rather than the rendered body. The body itself is asserted on the
        // direct path below.
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));

        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Shortener Console")));

        for (String asset : new String[] {"/console.js", "/console.css", "/favicon.svg"}) {
            mockMvc.perform(get(asset)).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("serving the console did not widen the default-deny rule")
    void unlistedPathsAreStillDenied() throws Exception {
        // The console is permitted by exact filename rather than by a /** wildcard, so a file
        // that happens to land in the static directory is not published by accident. This is
        // the assertion that would fail if someone later reached for the wildcard.
        mockMvc.perform(get("/secrets.txt")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/secrets.txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/console.js.map")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("console filenames fall outside the short-code pattern, so the redirect cannot shadow them")
    void consoleAssetsAreNotMistakenForShortCodes() throws Exception {
        // The redirect endpoint owns the root namespace via [A-Za-z0-9_-]{3,32}. A dot is
        // outside that class, which is the only reason /console.js reaches the static handler
        // at all — a route named /dashboard would be answered as a missing link instead.
        mockMvc.perform(get("/console.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("shortener.links.")));
    }

    @Test
    @DisplayName("the console runs under the strict CSP rather than requiring it be relaxed")
    void consoleIsServedUnderTheStrictPolicy() throws Exception {
        // No inline script and no CDN in the console means script-src can stay at 'self'. If a
        // future change needs 'unsafe-inline' to make the page work, this is where that shows up.
        mockMvc.perform(get("/"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("script-src 'self';")))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("script-src 'self' 'unsafe-inline'"))));
    }

    @Test
    @DisplayName("the seeder restores a demo account that suspended itself")
    void suspendedDemoAccountsAreRestoredOnStartup() throws Exception {
        // Five refused creations in an hour suspends an account and revokes its tokens, which
        // the screening scenario reaches after a handful of runs. Nothing in the product lifts
        // a suspension, so without this the demo works exactly once.
        AppUser suspended = appUsers.findByUsername("bob").orElseThrow();
        suspended.disable();
        appUsers.save(suspended);

        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"bob-password\"}"))
                .andExpect(status().isUnauthorized());

        seedDemoUsers.run(new DefaultApplicationArguments());

        assertThat(appUsers.findByUsername("bob").orElseThrow().isEnabled()).isTrue();
        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"bob-password\"}"))
                .andExpect(status().isOk());
    }

    private MvcResult create(String token, String url) throws Exception {
        return mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
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
