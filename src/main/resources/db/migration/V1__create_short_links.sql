-- Sequence is the sole source of short-code identity. It is consumed with nextval()
-- before insert so the code can be derived and returned in the same request, and it
-- is deliberately independent of the table's own storage so a code is never reused
-- after a delete.
CREATE SEQUENCE short_link_id_seq AS BIGINT START WITH 1 INCREMENT BY 1 NO CYCLE;

CREATE TABLE short_links (
    id            BIGINT       PRIMARY KEY,
    code          VARCHAR(32)  NOT NULL,
    original_url  TEXT         NOT NULL,
    custom_alias  BOOLEAN      NOT NULL DEFAULT FALSE,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by    VARCHAR(128),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ
);

-- Unique index doubles as the lookup index for the redirect path, which is the only
-- query that matters for latency.
CREATE UNIQUE INDEX ux_short_links_code ON short_links (code);

CREATE INDEX ix_short_links_created_by ON short_links (created_by) WHERE created_by IS NOT NULL;
