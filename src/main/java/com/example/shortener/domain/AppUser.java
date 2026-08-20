package com.example.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 72)
    private String passwordHash;

    @Column(nullable = false, length = 255)
    private String roles = "USER";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AppUser() {
    }

    public AppUser(String username, String passwordHash, String roles) {
        this.username = username.toLowerCase(Locale.ROOT);
        this.passwordHash = passwordHash;
        this.roles = roles;
        this.enabled = true;
        this.createdAt = Instant.now();
    }

    /** Role names without the {@code ROLE_} prefix; the security layer applies it. */
    public List<String> roleList() {
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .toList();
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRoles() {
        return roles;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Suspends the account. Blocks future logins only — tokens already issued stay valid
     * until they expire, so a caller suspending an account must revoke its tokens too.
     */
    public void disable() {
        this.enabled = false;
    }

    public void enable() {
        this.enabled = true;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
