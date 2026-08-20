package com.example.shortener.api;

import com.example.shortener.service.ClickRecorder;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end API tests against a real PostgreSQL instance running the production
 * Flyway migrations.
 *
 * <p>Redis is deliberately absent (the test profile points at a dead port), so every
 * case here also asserts the degraded-mode contract: the service must behave
 * identically, only slower, when its cache and rate limiter are unreachable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShortLinkApiIntegrationTest {

    private static PostgreSQLContainer<?> postgres;

    /**
     * Prefers a database supplied by the environment, and starts a container only when
     * none is given. CI and the local Compose stack both already run PostgreSQL, and
     * reusing it avoids paying container startup on every run; the container path keeps
     * the suite self-contained for anyone with only Docker.
     */
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
    private ClickRecorder clickRecorder;

    private String alice;

    /**
     * Each test starts from an empty corpus. Without this the suite passes once and then
     * fails on re-run against a persistent database, which is the worst kind of flake:
     * it only appears after someone trusts the suite.
     */
    @BeforeEach
    void resetCorpus() throws Exception {
        // Drain first, then truncate. Events left buffered by an earlier test still
        // reference links that truncation is about to remove, and the resulting foreign
        // key violation would abandon the batch that the next test's events land in.
        clickRecorder.flush();
        jdbcTemplate.execute("TRUNCATE click_events, short_links RESTART IDENTITY CASCADE");
        alice = tokenFor("alice", "alice-password");
    }

    @Test
    @DisplayName("creating a link returns 201 with a seven character code and a Location header")
    void createsShortLink() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/a/very/long/path?with=query\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/a/very/long/path?with=query"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.customAlias").value(false))
                .andReturn();

        assertThat(codeOf(result)).hasSize(7).matches("[0-9A-Za-z]{7}");
    }

    @Test
    @DisplayName("consecutive links do not receive adjacent codes")
    void codesAreNotEnumerable() throws Exception {
        String first = codeOf(create("https://example.com/one"));
        String second = codeOf(create("https://example.com/two"));
        assertThat(first).isNotEqualTo(second);
        // A sequential scheme would differ only in the final character.
        assertThat(first.substring(0, 5)).isNotEqualTo(second.substring(0, 5));
    }

    @Test
    @DisplayName("redirect answers 302 with Location and forbids caching so clicks stay observable")
    void redirectsAndForbidsCaching() throws Exception {
        String code = codeOf(create("https://example.com/destination"));

        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/destination"))
                .andExpect(header().string("Cache-Control", "no-store, no-cache, must-revalidate, private"));
    }

    @Test
    @DisplayName("unknown codes return an RFC 9457 problem document, not an empty 404")
    void unknownCodeReturnsProblemDetail() throws Exception {
        mockMvc.perform(get("/zzzzzzz"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Link not found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void rejectsUnsafeTargets() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://169.254.169.254/latest/meta-data/\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not publicly routable")));

        mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingUrl() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("url")));
    }

    @Test
    @DisplayName("custom aliases work once and conflict on reuse")
    void customAliasIsClaimedExactlyOnce() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/docs\",\"alias\":\"team-docs\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("team-docs"))
                .andExpect(jsonPath("$.customAlias").value(true));

        mockMvc.perform(get("/team-docs"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/docs"));

        mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/other\",\"alias\":\"team-docs\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("reserved aliases cannot shadow application routes")
    void reservedAliasesAreRefused() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\",\"alias\":\"actuator\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("reserved")));
    }

    @Test
    @DisplayName("an already expired link never resolves")
    void expiredLinksDoNotResolve() throws Exception {
        String past = Instant.now().minusSeconds(60).toString();
        MvcResult created = mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/gone\",\"expiresAt\":\"" + past + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        mockMvc.perform(get("/" + codeOf(created)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("retiring a link stops redirects but keeps the record queryable")
    void deactivationStopsRedirects() throws Exception {
        String code = codeOf(create("https://example.com/temporary"));

        mockMvc.perform(get("/" + code)).andExpect(status().isFound());
        mockMvc.perform(delete("/api/v1/links/" + code)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/" + code)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/links/" + code)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    @DisplayName("clicks are buffered off the redirect path, then aggregated once flushed")
    void clicksAreAggregatedIntoStats() throws Exception {
        String code = codeOf(create("https://example.com/tracked"));

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/" + code).header("Referer", "https://news.example.org/story/1"))
                    .andExpect(status().isFound());
        }

        // Nothing is durable yet: the redirect returned before the write happened.
        // Asserting this is the point, because it is the contract the redirect path relies on.
        assertThat(clickRecorder.getBufferedEvents()).isEqualTo(3);
        mockMvc.perform(get("/api/v1/links/" + code + "/stats")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(jsonPath("$.totalClicks").value(0));

        clickRecorder.flush();

        mockMvc.perform(get("/api/v1/links/" + code + "/stats")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(3))
                .andExpect(jsonPath("$.uniqueVisitors").value(1))
                .andExpect(jsonPath("$.topReferrers[0].source").value("news.example.org"))
                .andExpect(jsonPath("$.topReferrers[0].clicks").value(3))
                .andExpect(jsonPath("$.accuracyNote").value(
                        org.hamcrest.Matchers.containsString("best-effort")));
    }

    @Test
    void statsRejectAnOutOfRangeWindow() throws Exception {
        String code = codeOf(create("https://example.com/window"));
        mockMvc.perform(get("/api/v1/links/" + code + "/stats?windowDays=0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("health endpoint stays UP even though Redis is unreachable")
    void healthIgnoresTheSoftDependency() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private MvcResult create(String url) throws Exception {
        return mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
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
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("code").asText();
    }
}
