package com.example.shortener.api;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

        // Same token with its final signature character altered: a valid structure that
        // must fail verification.
        String tampered = alice.substring(0, alice.length() - 1)
                + (alice.endsWith("A") ? "B" : "A");
        mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
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
