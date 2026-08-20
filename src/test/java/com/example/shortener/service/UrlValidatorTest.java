package com.example.shortener.service;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.service.error.InvalidUrlException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {

    private UrlValidator validator;

    @BeforeEach
    void setUp() {
        ShortenerProperties properties = new ShortenerProperties();
        properties.setBaseUrl("https://sho.rt");
        validator = new UrlValidator(properties);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",
            "http://example.com/path?query=1#fragment",
            "https://sub.domain.example.co.uk/a/b/c",
            "https://example.com:8443/path"
    })
    void acceptsPubliclyRoutableHttpUrls(String url) {
        assertThat(validator.validateAndNormalize(url)).isEqualTo(url);
    }

    @ParameterizedTest
    @DisplayName("rejects schemes that would let a short link execute code or read files")
    @ValueSource(strings = {
            "javascript:alert(document.cookie)",
            "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            "file:///etc/passwd",
            "ftp://example.com/file.txt"
    })
    void rejectsNonHttpSchemes(String url) {
        assertThatThrownBy(() -> validator.validateAndNormalize(url))
                .isInstanceOf(InvalidUrlException.class);
    }

    @ParameterizedTest
    @DisplayName("rejects targets inside the private network, the classic SSRF pivot")
    @ValueSource(strings = {
            "http://localhost:8080/admin",
            "http://127.0.0.1/",
            "http://0.0.0.0/",
            "http://10.0.0.5/internal",
            "http://192.168.1.1/router",
            "http://172.16.4.4/",
            "http://169.254.169.254/latest/meta-data/",
            "http://metadata.google.internal/computeMetadata/v1/",
            "http://build-server.internal/",
            "http://printer.local/"
    })
    void rejectsPrivateAndMetadataTargets(String url) {
        assertThatThrownBy(() -> validator.validateAndNormalize(url))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("not publicly routable");
    }

    @Test
    @DisplayName("rejects embedded credentials, which are used to disguise a hostile host")
    void rejectsEmbeddedCredentials() {
        assertThatThrownBy(() -> validator.validateAndNormalize("https://paypal.com@evil.example/login"))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    @DisplayName("refuses to shorten its own links, which would allow redirect chains")
    void rejectsSelfReferentialUrls() {
        assertThatThrownBy(() -> validator.validateAndNormalize("https://sho.rt/abc1234"))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("points back at this service");
    }

    @Test
    void rejectsEmptyAndOversizedInput() {
        assertThatThrownBy(() -> validator.validateAndNormalize("  "))
                .isInstanceOf(InvalidUrlException.class);
        assertThatThrownBy(() -> validator.validateAndNormalize("https://example.com/" + "a".repeat(2100)))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("2048");
    }

    @Test
    void rejectsRelativeAndMalformedUrls() {
        assertThatThrownBy(() -> validator.validateAndNormalize("/just/a/path"))
                .isInstanceOf(InvalidUrlException.class);
        assertThatThrownBy(() -> validator.validateAndNormalize("http://"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @DisplayName("scheme comparison is case insensitive, so HTTPS:// is not a bypass")
    void schemeCheckIsCaseInsensitive() {
        assertThat(validator.validateAndNormalize("HTTPS://example.com")).isEqualTo("HTTPS://example.com");
    }
}
