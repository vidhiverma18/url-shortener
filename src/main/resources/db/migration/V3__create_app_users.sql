CREATE TABLE app_users (
    id            BIGSERIAL    PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    -- BCrypt output, always 60 characters. Never a reversible encoding: a shortener
    -- database is not a high-value target until it turns out to hold reused passwords.
    password_hash VARCHAR(72)  NOT NULL,
    -- Comma-separated role names without the ROLE_ prefix, which the security layer adds.
    -- A join table would be correct with more than a handful of roles; with two, it would
    -- be structure for its own sake.
    roles         VARCHAR(255) NOT NULL DEFAULT 'USER',
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX ux_app_users_username ON app_users (LOWER(username));

-- Links created before authentication existed have no owner. They stay readable by
-- administrators only, rather than being silently reassigned to whoever asks first.
COMMENT ON COLUMN short_links.created_by IS
    'Username of the creating principal; NULL for links created before authentication was introduced';
