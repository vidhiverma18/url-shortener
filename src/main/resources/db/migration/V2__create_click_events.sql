-- Click data lives in its own append-only table rather than as a counter column on
-- short_links. A counter would serialise every redirect for a popular link behind a
-- single row lock, which is the classic way this design falls over under load.
CREATE TABLE click_events (
    id             BIGSERIAL    PRIMARY KEY,
    short_link_id  BIGINT       NOT NULL REFERENCES short_links (id) ON DELETE CASCADE,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    -- Only the referring host is retained, never the full referring URL: the path and
    -- query of a referrer routinely carry session tokens and personal data.
    referrer_host  VARCHAR(255),
    user_agent     VARCHAR(512),
    -- Salted hash rather than the raw address, so repeat-visitor counts remain
    -- possible without the service holding a log of who visited what.
    -- VARCHAR, not CHAR: CHAR right-pads with spaces, which would make two identical
    -- hashes compare unequal depending on how they were written.
    visitor_hash   VARCHAR(64)
);

CREATE INDEX ix_click_events_link_time ON click_events (short_link_id, occurred_at DESC);
