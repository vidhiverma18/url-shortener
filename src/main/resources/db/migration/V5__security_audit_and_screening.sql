-- Security-relevant actions, kept separately from application logs because logs rotate,
-- get sampled, and are writable by whatever can reach the log shipper. An audit trail that
-- the audited party can edit is not evidence.
CREATE TABLE audit_events (
    id           BIGSERIAL    PRIMARY KEY,
    occurred_at  TIMESTAMPTZ  NOT NULL,
    -- Username, or 'anonymous' for pre-authentication events. Not a foreign key: the record
    -- of what an account did has to outlive the account, or deleting the account erases it.
    actor        VARCHAR(128) NOT NULL,
    action       VARCHAR(64)  NOT NULL,
    target_type  VARCHAR(32),
    target_id    VARCHAR(128),
    outcome      VARCHAR(16)  NOT NULL,
    client_ip    VARCHAR(64),
    detail       VARCHAR(512)
);

CREATE INDEX ix_audit_events_time ON audit_events (occurred_at DESC);
CREATE INDEX ix_audit_events_actor ON audit_events (actor, occurred_at DESC);
CREATE INDEX ix_audit_events_action ON audit_events (action, occurred_at DESC);

-- Append-only, enforced by the database rather than by convention. Application code can be
-- changed by whoever is covering their tracks; this cannot, without a schema migration that
-- is itself visible in version control.
--
-- A rule with DO INSTEAD NOTHING was the alternative and is worse: it discards the write
-- silently, so a caller believes it succeeded. Raising makes tampering an error someone sees.
CREATE OR REPLACE FUNCTION audit_events_immutable() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only; % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_events_no_update
    BEFORE UPDATE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_immutable();

CREATE TRIGGER audit_events_no_delete
    BEFORE DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_immutable();

-- Hosts refused at creation. A table rather than configuration alone so an operator can add
-- one mid-incident without a redeploy; the configured list stays as the static baseline.
CREATE TABLE blocked_domains (
    domain      VARCHAR(255) PRIMARY KEY,
    reason      VARCHAR(255),
    added_by    VARCHAR(128),
    added_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Screening state per link. PENDING means no verdict yet — either screening was disabled at
-- creation or the provider was unreachable and the link was let through under fail-open.
-- Those links are the rescan sweep's priority queue.
ALTER TABLE short_links ADD COLUMN screening_status VARCHAR(16) NOT NULL DEFAULT 'PENDING';
ALTER TABLE short_links ADD COLUMN screened_at      TIMESTAMPTZ;
ALTER TABLE short_links ADD COLUMN quarantined_at   TIMESTAMPTZ;

COMMENT ON COLUMN short_links.quarantined_at IS
    'Set when screening disabled the link after creation. Distinct from an owner retiring it: '
    'the redirect answers 410 with a different reason, and the owner did not choose this.';

-- Drives the rescan sweep: oldest screening first, live links only. Partial because retired
-- links are not worth re-screening and would otherwise dominate the index over time.
CREATE INDEX ix_short_links_rescan
    ON short_links (screened_at NULLS FIRST)
    WHERE active;
