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

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reuse of an existing short link when the same URL is submitted again.
 *
 * <p>The cases that matter are the ones where reuse must <em>not</em> happen. Returning a
 * link the caller did not ask for is a worse failure than minting a duplicate: it can hand
 * back someone else's code, a code with an expiry the caller never requested, or a
 * destination that merely looks similar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UrlDeduplicationIntegrationTest {

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

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("TRUNCATE click_events, short_links RESTART IDENTITY CASCADE");
        alice = tokenFor("alice", "alice-password");
        bob = tokenFor("bob", "bob-password");
    }

    // --- the point of the feature -----------------------------------------------------

    @Test
    @DisplayName("the same URL twice returns the same code, and 200 rather than 201")
    void repeatedUrlReturnsTheExistingLink() throws Exception {
        MvcResult first = mockMvc.perform(create(alice, "https://example.com/repeat"))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult second = mockMvc.perform(create(alice, "https://example.com/repeat"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(codeOf(second)).isEqualTo(codeOf(first));
        assertThat(countLinks()).isEqualTo(1);
    }

    @Test
    @DisplayName("differences that RFC 3986 calls equivalent still match")
    void canonicallyEqualUrlsMatch() throws Exception {
        String canonical = codeOf(mockMvc.perform(create(alice, "https://example.com/path?a=1"))
                .andExpect(status().isCreated()).andReturn());

        for (String equivalent : new String[]{
                "HTTPS://example.com/path?a=1",
                "https://EXAMPLE.COM/path?a=1",
                "https://example.com:443/path?a=1"}) {
            MvcResult result = mockMvc.perform(create(alice, equivalent))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(codeOf(result)).as(equivalent).isEqualTo(canonical);
        }
        assertThat(countLinks()).isEqualTo(1);
    }

    @Test
    @DisplayName("a bare host and an explicit root path are the same resource")
    void emptyPathMatchesRoot() throws Exception {
        String bare = codeOf(mockMvc.perform(create(alice, "https://example.com"))
                .andExpect(status().isCreated()).andReturn());
        String root = codeOf(mockMvc.perform(create(alice, "https://example.com/"))
                .andExpect(status().isOk()).andReturn());

        assertThat(root).isEqualTo(bare);
    }

    // --- where reuse must not happen --------------------------------------------------

    @Test
    @DisplayName("a trailing slash is significant and must not be collapsed")
    void trailingSlashIsADifferentResource() throws Exception {
        // /a and /a/ are genuinely different resources on plenty of servers. Over-normalising
        // here would redirect a caller somewhere they never asked to go, which is a far worse
        // outcome than storing one extra row.
        String withoutSlash = codeOf(mockMvc.perform(create(alice, "https://example.com/docs"))
                .andExpect(status().isCreated()).andReturn());
        String withSlash = codeOf(mockMvc.perform(create(alice, "https://example.com/docs/"))
                .andExpect(status().isCreated()).andReturn());

        assertThat(withSlash).isNotEqualTo(withoutSlash);
    }

    @Test
    @DisplayName("query parameter order is preserved, not sorted")
    void queryOrderIsSignificant() throws Exception {
        codeOf(mockMvc.perform(create(alice, "https://example.com/q?a=1&b=2"))
                .andExpect(status().isCreated()).andReturn());
        mockMvc.perform(create(alice, "https://example.com/q?b=2&a=1"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("one owner never receives another owner's link")
    void reuseIsScopedToTheOwner() throws Exception {
        String aliceCode = codeOf(mockMvc.perform(create(alice, "https://example.com/shared"))
                .andExpect(status().isCreated()).andReturn());
        String bobCode = codeOf(mockMvc.perform(create(bob, "https://example.com/shared"))
                .andExpect(status().isCreated()).andReturn());

        // Sharing a code across owners would leak that Alice shortened this URL, merge two
        // users' clicks into one series, and give Bob a code he cannot read stats for.
        assertThat(bobCode).isNotEqualTo(aliceCode);
        assertThat(countLinks()).isEqualTo(2);
    }

    @Test
    @DisplayName("forceNew mints a second code for the same destination")
    void forceNewOptsOut() throws Exception {
        String first = codeOf(mockMvc.perform(create(alice, "https://example.com/campaign"))
                .andExpect(status().isCreated()).andReturn());

        MvcResult forced = mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/campaign\",\"forceNew\":true}"))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(codeOf(forced)).isNotEqualTo(first);

        // The forced link stays out of the index, so the original is still the reusable one.
        MvcResult third = mockMvc.perform(create(alice, "https://example.com/campaign"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(codeOf(third)).isEqualTo(first);
    }

    @Test
    @DisplayName("a custom alias is an explicit request and is never satisfied by reuse")
    void aliasRequestsAreNotDeduplicated() throws Exception {
        String generated = codeOf(mockMvc.perform(create(alice, "https://example.com/aliased"))
                .andExpect(status().isCreated()).andReturn());

        MvcResult aliased = mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/aliased\",\"alias\":\"my-alias\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(codeOf(aliased)).isEqualTo("my-alias").isNotEqualTo(generated);
    }

    @Test
    @DisplayName("an expiry request is never satisfied by a link that does not expire")
    void expiringRequestsAreNotDeduplicated() throws Exception {
        String permanent = codeOf(mockMvc.perform(create(alice, "https://example.com/timed"))
                .andExpect(status().isCreated()).andReturn());

        String future = Instant.now().plusSeconds(3600).toString();
        MvcResult expiring = mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/timed\",\"expiresAt\":\"" + future + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(codeOf(expiring)).isNotEqualTo(permanent);
    }

    @Test
    @DisplayName("retiring a link frees its slot, so the URL can be shortened again")
    void retiredLinksAreNotReused() throws Exception {
        String original = codeOf(mockMvc.perform(create(alice, "https://example.com/retire"))
                .andExpect(status().isCreated()).andReturn());

        mockMvc.perform(delete("/api/v1/links/" + original)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isNoContent());

        MvcResult fresh = mockMvc.perform(create(alice, "https://example.com/retire"))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(codeOf(fresh)).isNotEqualTo(original);
    }

    // --- the race the index exists for ------------------------------------------------

    @Test
    @DisplayName("concurrent identical requests converge on one link")
    void concurrentDuplicatesCreateExactlyOneLink() throws Exception {
        int threads = 24;
        Set<String> codes = ConcurrentHashMap.newKeySet();
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    startLine.await();
                    MvcResult result = mockMvc.perform(create(alice, "https://example.com/stampede"))
                            .andReturn();
                    int status = result.getResponse().getStatus();
                    if (status == 200 || status == 201) {
                        codes.add(codeOf(result));
                    }
                } catch (Exception e) {
                    // Recorded by the assertions below rather than failing on a worker thread.
                } finally {
                    finished.countDown();
                }
            });
        }
        startLine.countDown();
        assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // The application-side lookup cannot win this on its own: every thread reads before
        // any writes. The partial unique index is the real arbiter, and the losers resolve
        // into the winner's link instead of erroring.
        assertThat(codes).as("all callers agree on one code").hasSize(1);
        assertThat(countLinks()).as("exactly one row was created").isEqualTo(1);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(
            String token, String url) {
        return post("/api/v1/links")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + url + "\"}");
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
