package com.example.shortener.service;

import com.example.shortener.repository.ClickEventRepository;
import com.example.shortener.repository.IdAllocator;
import com.example.shortener.repository.ShortLinkRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the property that makes the cache worth having during a database outage: a
 * cache hit must reach the database in no way at all, not even to open a transaction.
 *
 * <p>This exists because the opposite shipped. {@code resolve} was annotated
 * {@code @Transactional(readOnly = true)}, so Spring began a transaction — and took a
 * pool connection — before the cache was consulted. With PostgreSQL stopped, a cached
 * redirect returned 500 after blocking for the full connection timeout, while the
 * architecture documentation claimed it would still resolve. A manual failure drill
 * caught it; no test did.
 */
class ShortLinkResolutionTest {

    private final ShortLinkRepository links = mock(ShortLinkRepository.class);
    private final ClickEventRepository clicks = mock(ClickEventRepository.class);
    private final LinkCache cache = mock(LinkCache.class);

    private final ShortLinkService service = new ShortLinkService(
            links, clicks, mock(IdAllocator.class), mock(ShortCodeFactory.class),
            mock(UrlValidator.class), cache, mock(ClickRecorder.class));

    @Test
    @DisplayName("a cache hit resolves without touching the repository at all")
    void cacheHitDoesNotReachTheDatabase() {
        when(cache.lookup("abc1234")).thenReturn(Optional.of(LinkCache.CacheEntry.hit(42L, "https://example.com")));

        ShortLinkService.Resolution resolution = service.resolve("abc1234");

        assertThat(resolution.url()).isEqualTo("https://example.com");
        assertThat(resolution.linkId()).isEqualTo(42L);
        assertThat(resolution.fromCache()).isTrue();
        verify(links, never()).findByCode(anyString());
    }

    @Test
    @DisplayName("resolve must not be transactional, or a cache hit borrows a connection")
    void resolveIsNotTransactional() throws NoSuchMethodException {
        // Asserting on an annotation is unusual. It is done here because the annotation
        // itself was the defect: the behaviour it breaks is only observable with the
        // database actually stopped, which no test in this suite can arrange.
        assertThat(ShortLinkService.class.getMethod("resolve", String.class)
                .isAnnotationPresent(Transactional.class))
                .as("resolve() must stay untransactional so cache hits survive a database outage")
                .isFalse();
    }
}
