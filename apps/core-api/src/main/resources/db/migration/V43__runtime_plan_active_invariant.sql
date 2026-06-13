-- OP-CAP-16J Runtime Entitlement Admin Hardening
-- DB-level integrity for the runtime governance plan surface (NOT billing): enforce at most one
-- open-ended, currently-effective ACTIVE runtime plan per tenant. This backs the service-level
-- conflict check in RuntimeEntitlementAdminService with a real database invariant.
--
-- Additive and non-destructive: index only, no table/column change, no data deletion, no renames.
-- No seed rows exist in tenant_runtime_plan (the table was introduced empty in V42), so this index
-- cannot conflict with existing data.
--
-- Scope of the invariant (deliberately narrow, matching the 16J preferred invariant):
--   * Blocks a SECOND open-ended (effective_until IS NULL) ACTIVE plan for the same tenant.
--   * Does NOT block historical ACTIVE plans that have a closed effective_until (bounded windows).
--   * Does NOT block SUSPENDED / EXPIRED / DISABLED plans.
-- Overlapping bounded-but-currently-active windows remain a service-level concern (no btree_gist
-- range exclusion is introduced in this stage).
--
-- Postgres partial unique index (same style as V39/V42). The H2 test schema is generated from JPA
-- entities with Flyway disabled, so this index is not applied there; the service-level conflict
-- check (covered by tests) is the enforcement path under H2.
CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_runtime_plan_active_open
  ON tenant_runtime_plan(tenant_id)
  WHERE status = 'ACTIVE' AND effective_until IS NULL;
