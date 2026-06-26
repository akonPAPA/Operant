-- OP-CAP-51 Production Operations, Access & Maintenance Control Layer Foundation.
--
-- A bounded foundation that lets the Operant-owner company support and maintain tenants SAFELY:
--   * a separate internal staff principal (staff_user) — NOT a tenant customer/operator, and holding a
--     staff role grants no tenant access on its own;
--   * scoped, reasoned, EXPIRING support access grants (support_access_grant) — the sole authorization to
--     observe/diagnose a specific tenant for a specific scope, and only until expiry;
--   * maintenance/update audit records (maintenance_action_record) — record-only; they never trigger a
--     deployment, never run a migration, and never call an external system;
--   * controlled data-repair requests (data_repair_request) — DRY-RUN ONLY; execution is disabled and
--     there is no arbitrary SQL/script/raw-target field.
--
-- Safety model: support can observe and diagnose; support cannot silently mutate customer business truth;
-- everything is scoped; everything expires; unknown support/maintenance access is denied. No business
-- order/quote/inventory/customer/price table is touched by this migration.

CREATE TABLE IF NOT EXISTS staff_user (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  handle      VARCHAR(160) NOT NULL,
  role        VARCHAR(40) NOT NULL,
  status      VARCHAR(20) NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL,
  CONSTRAINT ux_staff_user_handle UNIQUE (handle)
);

CREATE TABLE IF NOT EXISTS support_access_grant (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  staff_user_id     UUID NOT NULL REFERENCES staff_user(id),
  tenant_id         UUID NOT NULL REFERENCES tenant(id),
  scope             VARCHAR(40) NOT NULL,
  support_case_ref  VARCHAR(200) NOT NULL,
  status            VARCHAR(20) NOT NULL,
  expires_at        TIMESTAMPTZ NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL,
  created_by        UUID NULL,
  revoked_at        TIMESTAMPTZ NULL,
  revoked_by        UUID NULL
);

-- Authorization lookup: by (staff, tenant, scope, status) newest-expiry-first; and tenant registry listing.
CREATE INDEX IF NOT EXISTS idx_support_access_grant_lookup
  ON support_access_grant(staff_user_id, tenant_id, scope, status, expires_at DESC);
CREATE INDEX IF NOT EXISTS idx_support_access_grant_tenant
  ON support_access_grant(tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS maintenance_action_record (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      UUID NOT NULL REFERENCES tenant(id),
  action_type    VARCHAR(40) NOT NULL,
  staff_user_id  UUID NULL,
  reason         VARCHAR(500) NOT NULL,
  target_scope   VARCHAR(120) NOT NULL,
  status         VARCHAR(20) NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_maintenance_action_record_tenant
  ON maintenance_action_record(tenant_id, created_at DESC);

CREATE TABLE IF NOT EXISTS data_repair_request (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         UUID NOT NULL REFERENCES tenant(id),
  target_type       VARCHAR(40) NOT NULL,
  requested_by      UUID NULL,
  reason            VARCHAR(500) NOT NULL,
  status            VARCHAR(30) NOT NULL,
  execution_status  VARCHAR(30) NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_data_repair_request_tenant
  ON data_repair_request(tenant_id, created_at DESC);
