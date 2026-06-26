-- OP-CAP-53 Break-Glass & Incident Response Foundation.
--
-- A controlled emergency incident-response foundation for Operant-owner-company staff. It adds:
--   * incident records (incident_record) — audit-backed incident lifecycle (OPEN -> CLOSED). A CRITICAL
--     incident can never be silently closed without a closure reason. Records NO business truth.
--   * break-glass access requests (break_glass_access_request) — scoped, reasoned, EXPIRING emergency
--     access tied to an incident. A request is unusable until a SEPARATE approver approves it, and it
--     always expires. The scope is a policy label only — a valid break-glass request mutates no business
--     truth and runs no SQL/script/connector by itself.
--   * record-only alerts (incident_alert_record) — record that a security/platform alert SHOULD be emitted
--     later. This stage performs no external delivery (no email/SMS/Slack, no network call).
--
-- This migration is additive and non-destructive: it only CREATEs new tables/indexes. It rewrites no
-- existing table, drops nothing, and touches no business order/quote/inventory/customer/price table and no
-- existing support/data-repair table.

CREATE TABLE IF NOT EXISTS incident_record (
  id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  -- Nullable is reserved for a future explicitly platform-wide incident; today it is the tenant scope.
  tenant_id               UUID NULL REFERENCES tenant(id),
  title                   VARCHAR(200) NOT NULL,
  reason                  VARCHAR(2000) NOT NULL,
  severity                VARCHAR(20) NOT NULL,
  incident_type           VARCHAR(40) NOT NULL,
  status                  VARCHAR(20) NOT NULL,
  created_by_staff_actor  UUID NULL,
  created_at              TIMESTAMPTZ NOT NULL,
  updated_at              TIMESTAMPTZ NOT NULL,
  closed_at               TIMESTAMPTZ NULL,
  closure_reason          VARCHAR(1000) NULL
);

CREATE INDEX IF NOT EXISTS idx_incident_record_tenant_status
  ON incident_record(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_incident_record_status
  ON incident_record(status);
CREATE INDEX IF NOT EXISTS idx_incident_record_tenant_created
  ON incident_record(tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS break_glass_access_request (
  id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id                 UUID NULL REFERENCES tenant(id),
  incident_id               UUID NOT NULL REFERENCES incident_record(id),
  requested_by_staff_actor  UUID NULL,
  approved_by_staff_actor   UUID NULL,
  rejected_by_staff_actor   UUID NULL,
  scope                     VARCHAR(40) NOT NULL,
  reason                    VARCHAR(1000) NOT NULL,
  status                    VARCHAR(20) NOT NULL,
  requested_at              TIMESTAMPTZ NOT NULL,
  decided_at                TIMESTAMPTZ NULL,
  expires_at                TIMESTAMPTZ NOT NULL,
  revoked_at                TIMESTAMPTZ NULL,
  revocation_reason         VARCHAR(1000) NULL
);

CREATE INDEX IF NOT EXISTS idx_break_glass_tenant_status
  ON break_glass_access_request(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_break_glass_incident
  ON break_glass_access_request(incident_id);
CREATE INDEX IF NOT EXISTS idx_break_glass_expires_at
  ON break_glass_access_request(expires_at);
CREATE INDEX IF NOT EXISTS idx_break_glass_requested_by
  ON break_glass_access_request(requested_by_staff_actor);

CREATE TABLE IF NOT EXISTS incident_alert_record (
  id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id               UUID NULL REFERENCES tenant(id),
  incident_id             UUID NOT NULL REFERENCES incident_record(id),
  break_glass_request_id  UUID NULL REFERENCES break_glass_access_request(id),
  alert_type              VARCHAR(40) NOT NULL,
  status                  VARCHAR(20) NOT NULL,
  detail                  VARCHAR(500) NULL,
  created_at              TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_incident_alert_incident
  ON incident_alert_record(incident_id);
CREATE INDEX IF NOT EXISTS idx_incident_alert_status
  ON incident_alert_record(status);
CREATE INDEX IF NOT EXISTS idx_incident_alert_created
  ON incident_alert_record(created_at DESC);
