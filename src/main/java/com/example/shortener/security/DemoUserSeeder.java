package com.example.shortener.security;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.AppUser;
import com.example.shortener.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Creates the two accounts the README documents so a reviewer can exercise the API
 * immediately.
 *
 * <p>Passwords are hashed here rather than shipped as literals in a migration, so no usable
 * credential is ever committed to the repository. The seeder is behind a flag and announces
 * itself loudly, because the one thing worse than a demo account is a demo account nobody
 * remembers is there.
 */
@Configuration
@ConditionalOnProperty(name = "shortener.security.seed-demo-users", havingValue = "true", matchIfMissing = true)
public class DemoUserSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);

    @Bean
    public ApplicationRunner seedDemoUsers(AppUserRepository users,
                                           PasswordEncoder passwordEncoder,
                                           ShortenerProperties properties) {
        return args -> {
            if (!properties.getSecurity().isSeedDemoUsers()) {
                return;
            }
            create(users, passwordEncoder, "alice", "alice-password", "USER");
            create(users, passwordEncoder, "bob", "bob-password", "USER");
            create(users, passwordEncoder, "admin", "admin-password", "USER,ADMIN");

            log.warn("Demo users are enabled with well-known passwords. "
                    + "Set SHORTENER_SEED_DEMO_USERS=false for any deployment that is not a local demo.");
        };
    }

    private void create(AppUserRepository users, PasswordEncoder encoder,
                        String username, String password, String roles) {
        if (users.findByUsername(username).isPresent()) {
            return;
        }
        users.save(new AppUser(username, encoder.encode(password), roles));
    }
}
