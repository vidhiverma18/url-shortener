package com.example.shortener.service;

import com.example.shortener.codec.Base62;
import com.example.shortener.codec.FeistelPermutation;
import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.service.error.InvalidAliasException;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns an allocated id into a public short code, and vets user-supplied aliases.
 *
 * <p>Generated codes are collision-free by construction: the id sequence is unique,
 * the Feistel permutation is a bijection, and Base62 encoding is injective. That is
 * what removes the read-before-write that hash-truncation designs need.
 */
@Component
public class ShortCodeFactory {

    public static final int CODE_LENGTH = 7;

    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");

    /**
     * Path prefixes the application itself owns. Without this, a user could claim the
     * alias "actuator" or "api" and shadow, or merely confuse, real routes.
     */
    private static final Set<String> RESERVED = Set.of(
            "api", "actuator", "health", "metrics", "admin", "login", "logout",
            "static", "assets", "docs", "swagger-ui", "v3", "favicon.ico", "robots.txt"
    );

    private final FeistelPermutation permutation;

    public ShortCodeFactory(ShortenerProperties properties) {
        this.permutation = new FeistelPermutation(properties.getCodeSecret());
    }

    public String fromId(long id) {
        if (id >= FeistelPermutation.DOMAIN_SIZE) {
            throw new IllegalStateException(
                    "short code space exhausted at id " + id + "; widen the domain before minting more links");
        }
        return Base62.encode(permutation.apply(id), CODE_LENGTH);
    }

    public String validateAlias(String alias) {
        if (alias == null || !ALIAS_PATTERN.matcher(alias).matches()) {
            throw new InvalidAliasException(
                    "Alias must be 3-32 characters of letters, digits, hyphen or underscore");
        }
        if (RESERVED.contains(alias.toLowerCase(Locale.ROOT))) {
            throw new InvalidAliasException("Alias '" + alias + "' is reserved");
        }
        return alias;
    }
}
