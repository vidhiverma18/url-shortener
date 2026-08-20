package com.example.shortener.security;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.AppUser;
import com.example.shortener.domain.BlockedDomain;
import com.example.shortener.repository.AppUserRepository;
import com.example.shortener.repository.BlockedDomainRepository;
import com.example.shortener.service.screening.BlocklistReputationChecker;
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

    /** Reserved by RFC 2606, so it can never resolve to anything real. */
    private static final String DEMO_BLOCKED_DOMAIN = "malware-demo.example";

    @Bean
    public ApplicationRunner seedDemoUsers(AppUserRepository users,
                                           PasswordEncoder passwordEncoder,
                                           BlockedDomainRepository blockedDomains,
                                           BlocklistReputationChecker blocklist,
                                           ShortenerProperties properties) {
        return args -> {
            if (!properties.getSecurity().isSeedDemoUsers()) {
                return;
            }
            create(users, passwordEncoder, "alice", "alice-password", "USER");
            create(users, passwordEncoder, "bob", "bob-password", "USER");
            create(users, passwordEncoder, "admin", "admin-password", "USER,ADMIN");

            // The blocklist ships empty, so without this the console's screening demo would
            // have nothing to refuse and would look as though screening were switched off.
            if (!blockedDomains.existsById(DEMO_BLOCKED_DOMAIN)) {
                blockedDomains.save(new BlockedDomain(
                        DEMO_BLOCKED_DOMAIN, "Seeded so the screening demo has something to refuse", "system"));
            }

            // The checker loads its snapshot at startup, which races this runner: without an
            // explicit refresh the seeded entry can be invisible for a full refresh interval,
            // and a demo that only works sixty seconds in looks like a demo that is broken.
            blocklist.refresh();

            log.warn("Demo users are enabled with well-known passwords. "
                    + "Set SHORTENER_SEED_DEMO_USERS=false for any deployment that is not a local demo.");
        };
    }

    private void create(AppUserRepository users, PasswordEncoder encoder,
                        String username, String password, String roles) {
        AppUser existing = users.findByUsername(username).orElse(null);
        if (existing == null) {
            users.save(new AppUser(username, encoder.encode(password), roles));
            return;
        }

        // Demo accounts can suspend themselves: the abuse monitor disables an account after
        // enough refused creations, and running the screening demo a handful of times is
        // enough to trigger it. That is the control working, but it leaves the demo
        // permanently unusable with no way back that does not involve SQL. Restoring them here
        // makes a restart the reset, and only ever touches accounts this seeder owns.
        if (!existing.isEnabled()) {
            existing.enable();
            users.save(existing);
            log.warn("Re-enabled suspended demo account '{}'.", username);
        }
    }
}
