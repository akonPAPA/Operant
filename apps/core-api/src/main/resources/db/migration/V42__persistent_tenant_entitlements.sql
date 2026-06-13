-- OP-CAP-16E Persistent Tenant Plans and Feature Entitlements
-- Backend-only runtime governance (NOT billing): persistent, tenant-scoped plan + feature
-- entitlement records that back the runtime feature policy. Additive and non-destructive: new
-- tables only, no renames, no data deletion. No monetary amounts, no payment/subscription data.
--
-- Default behavior (enforced in PersistentRuntimeFeaturePolicy, documented in the stage doc):
--   * tenant with no plan row at all -> allowed (compatibility default, preserves 16D behavior);
--   * tenant with a plan row but none currently active -> denied;
--   * active plan is authoritative: a feature is allowed only when an effective, enabled
--     entitlement row exists for that plan.

-- One runtime plan assignment for a tenant over an effective window.
CREATE TABLE IF NOT EXISTS tenant_runtime_plan (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  plan_code VARCHAR(40) NOT NULL,
  status VARCHAR(20) NOT NULL,
  effective_from TIMESTAMPTZ NOT NULL DEFAULT now(),
  effective_until TIMESTAMPTZ NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_tenant_runtime_plan_status
    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'EXPIRED', 'DISABLED')),
  CONSTRAINT chk_tenant_runtime_plan_code
    CHECK (plan_code IN ('FREE', 'PILOT', 'PRO', 'ENTERPRISE', 'CUSTOM'))
);

-- Hot path: resolve a tenant's current plan, active first, newest effective window first.
CREATE INDEX IF NOT EXISTS idx_tenant_runtime_plan_tenant_status
  ON tenant_runtime_plan(tenant_id, status, effective_from DESC);

-- A per-feature entitlement under a specific plan, over an effective window.
CREATE TABLE IF NOT EXISTS feature_entitlement (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  plan_id UUID NOT NULL REFERENCES tenant_runtime_plan(id),
  feature_type VARCHAR(60) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  reason_code VARCHAR(60) NULL,
  effective_from TIMESTAMPTZ NOT NULL DEFAULT now(),
  effective_until TIMESTAMPTZ NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Hot path: tenant-scoped feature lookup, and the plan-scoped authoritative lookup.
CREATE INDEX IF NOT EXISTS idx_feature_entitlement_tenant_feature
  ON feature_entitlement(tenant_id, feature_type);
CREATE INDEX IF NOT EXISTS idx_feature_entitlement_tenant_plan_feature
  ON feature_entitlement(tenant_id, plan_id, feature_type);

-- Prevent duplicate open-ended entitlement rows for the same tenant/plan/feature (Postgres partial
-- index; not applied under the H2 test schema, where the repository query resolves duplicates
-- deterministically).
CREATE UNIQUE INDEX IF NOT EXISTS uq_feature_entitlement_open
  ON feature_entitlement(tenant_id, plan_id, feature_type)
  WHERE effective_until IS NULL;
