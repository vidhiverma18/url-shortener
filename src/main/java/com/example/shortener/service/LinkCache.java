package com.example.shortener.service;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.ShortLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Read-through cache for the redirect path.
 *
 * <p>Three properties matter more than raw hit rate:
 *
 * <ul>
 *   <li><b>It fails open.</b> Every Redis interaction is wrapped so a cache outage
 *       degrades the service to "slower", never to "down". Postgres can serve the
 *       redirect path unaided; the cache exists to keep it comfortable.
 *   <li><b>It caches misses.</b> Unknown codes are the most common request under
 *       scanning traffic. Without a negative entry, every bogus code is a database
 *       round trip and a scanner becomes a denial-of-service tool.
 *   <li><b>It never outlives an expiry.</b> The entry TTL is capped at the link's own
 *       remaining lifetime, so an expired link cannot keep redirecting from cache.
 * </ul>
 */
@Component
public class LinkCache {

    private static final Logger log = LoggerFactory.getLogger(LinkCache.class);
    private static final String KEY_PREFIX = "link:";
    /** Sentinel for a code known not to exist. Not a valid encoded entry, so it cannot collide. */
    private static final String MISS_SENTINEL = "\u0000miss";
    private static final char SEPARATOR = '|';

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ShortenerProperties properties;

    public LinkCache(ObjectProvider<StringRedisTemplate> redisProvider, ShortenerProperties properties) {
        this.redisProvider = redisProvider;
        this.properties = properties;
    }

    public Optional<CacheEntry> lookup(String code) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return Optional.empty();
        }
        try {
            String value = redis.opsForValue().get(KEY_PREFIX + code);
            if (value == null) {
                return Optional.empty();
            }
            if (MISS_SENTINEL.equals(value)) {
                return Optional.of(CacheEntry.miss());
            }
            int separator = value.indexOf(SEPARATOR);
            if (separator <= 0) {
                return Optional.empty();
            }
            long linkId = Long.parseLong(value.substring(0, separator));
            return Optional.of(CacheEntry.hit(linkId, value.substring(separator + 1)));
        } catch (RuntimeException e) {
            log.warn("Cache lookup failed for code {}, falling back to database: {}", code, e.toString());
            return Optional.empty();
        }
    }

    public void put(ShortLink link) {
        Duration ttl = properties.getCacheTtl();
        if (link.getExpiresAt() != null) {
            Duration untilExpiry = Duration.between(Instant.now(), link.getExpiresAt());
            if (untilExpiry.isNegative() || untilExpiry.isZero()) {
                return;
            }
            if (untilExpiry.compareTo(ttl) < 0) {
                ttl = untilExpiry;
            }
        }
        write(link.getCode(), link.getId() + String.valueOf(SEPARATOR) + link.getOriginalUrl(), ttl);
    }

    public void putMiss(String code) {
        write(code, MISS_SENTINEL, properties.getNegativeCacheTtl());
    }

    /**
     * Removes an entry after the underlying link changes. Invalidation is best effort:
     * if it fails the stale entry expires on its own within the cache TTL, which is why
     * that TTL is kept short rather than indefinite.
     */
    public void evict(String code) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.delete(KEY_PREFIX + code);
        } catch (RuntimeException e) {
            log.warn("Cache eviction failed for code {}: {}", code, e.toString());
        }
    }

    private void write(String code, String value, Duration ttl) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().set(KEY_PREFIX + code, value, ttl);
        } catch (RuntimeException e) {
            log.warn("Cache write failed for code {}: {}", code, e.toString());
        }
    }

    public record CacheEntry(Long linkId, String url, boolean knownMiss) {
        public static CacheEntry hit(long linkId, String url) {
            return new CacheEntry(linkId, url, false);
        }

        public static CacheEntry miss() {
            return new CacheEntry(null, null, true);
        }
    }
}
