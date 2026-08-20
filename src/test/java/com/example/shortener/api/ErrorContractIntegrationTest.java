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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The error contract for malformed requests.
 *
 * <p>Every case here returned {@code 500} before {@code GlobalExceptionHandler} extended
 * {@link org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler}:
 * the catch-all advice intercepted Spring MVC's own exceptions before Spring could assign
 * them a status. A caller cannot tell "I sent something wrong" from "the server is broken"
 * when both are 500, and a monitoring alert on 5xx cannot either.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ErrorContractIntegrationTest {

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

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"alice-password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        token = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    @DisplayName("a malformed JSON body is a client error, not a server error")
    void malformedJsonIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void missingBodyIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a non-numeric query parameter is rejected as a client error")
    void typeMismatchIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/links/abcdefg/stats?windowDays=not-a-number")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedMethodIsMethodNotAllowed() throws Exception {
        mockMvc.perform(put("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void unsupportedContentTypeIsUnsupportedMediaType() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("https://example.com"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("framework errors carry the same problem shape as domain errors")
    void frameworkErrorsUseTheSameContract() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":"))
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    @DisplayName("error bodies never quote parser internals or class names back to the caller")
    void errorsDoNotLeakInternals() throws Exception {
        String body = mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":"))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("com.example", "com.fasterxml", "Exception", "org.springframework");
    }
}
