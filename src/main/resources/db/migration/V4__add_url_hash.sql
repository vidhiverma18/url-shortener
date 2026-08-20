-- SHA-256 of the canonical form of original_url, used to find an existing link for a
-- repeated URL. Hashed rather than indexed directly: original_url runs to 2048 characters,
-- which can exceed PostgreSQL's ~2704-byte b-tree entry limit once multi-byte characters
-- are involved, and a fixed 64-byte key keeps the index small.
ALTER TABLE short_links ADD COLUMN url_hash VARCHAR(64);

COMMENT ON COLUMN short_links.url_hash IS
    'SHA-256 of the canonical original_url. NULL excludes the link from deduplication: '
    'custom aliases, links with an explicit expiry, and links created with forceNew.';

-- At most one reusable link per owner per URL. This is the actual arbiter, not the
-- application-side lookup: two concurrent identical requests both find nothing, and the
-- index is what stops them both inserting.
--
-- Partial on `active` so a deactivated link releases its slot. Retiring a link and then
-- shortening the same URL again correctly yields a new code rather than resurrecting the
-- retired one.
CREATE UNIQUE INDEX ux_short_links_owner_url
    ON short_links (created_by, url_hash)
    WHERE url_hash IS NOT NULL AND active;
