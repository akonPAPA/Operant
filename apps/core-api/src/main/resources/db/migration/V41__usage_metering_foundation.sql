-- OP-CAP-16B Usage Metering Foundation
-- Backend-only foundation for measuring tenant usage, quota-relevant activity, and AI/workload
-- consumption. Additive and non-destructive: new tables only, no renames, no data deletion.
--
-- Safety/privacy: these tables never store raw customer message, document, prompt, AI-output, or
-- PII text. Usage events carry only stable taxonomy tokens, safe id references, and a sanitized
-- metadata object built from Stage 16A routing tokens. Counts use BIGINT (long) to avoid overflow.
-- No billing/payment data and no monetary amounts are stored here. Nothing in this migration wires
-- usage metering into a live request path; quota policy is foundation only (enforcement → 16C).

-- Append-only record of one quota-relevant activity for a tenant.
CREATE TABLE IF NOT EXISTS usage_event (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  event_type VARCHAR(40) NOT NULL,
  metric_type VARCHAR(40) NOT NULL,
  workload_type VARCHAR(40) NULL,
  model_tier VARCHAR(20) NULL,
  units BIGINT NOT NULL DEFAULT 0,
  source VARCHAR(40) NOT NULL,
  source_ref VARCHAR(180) NULL,
  idempotency_key VARCHAR(180) NULL,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  metadata_json JSONB NULL,
  CONSTRAINT chk_usage_event_units_non_negative CHECK (units >= 0)
);

-- Hot path: per-tenant usage history for a metric, newest first.
CREATE INDEX IF NOT EXISTS idx_usage_event_tenant_metric
  ON usage_event(tenant_id, metric_type, occurred_at DESC);

-- Idempotent recording: a client-supplied key dedupes per tenant so a retry does not double-count.
CREATE UNIQUE INDEX IF NOT EXISTS uq_usage_event_idempotency
  ON usage_event(tenant_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;

-- Aggregated counter for fast quota checks, unique per (tenant, metric, period).
CREATE TABLE IF NOT EXISTS usage_counter (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  metric_type VARCHAR(40) NOT NULL,
  period_key VARCHAR(32) NOT NULL,
  units_used BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_usage_counter_units_non_negative CHECK (units_used >= 0),
  CONSTRAINT uq_usage_counter_scope UNIQUE (tenant_id, metric_type, period_key)
);

-- Per-metric usage limit foundation. limit_units is a BIGINT count of metric units, never money.
-- Either tenant_id or plan_code scopes the policy; Stage 16B resolves by tenant_id + metric_type.
CREATE TABLE IF NOT EXISTS quota_policy (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NULL REFERENCES tenant(id),
  plan_code VARCHAR(60) NULL,
  metric_type VARCHAR(40) NOT NULL,
  period_type VARCHAR(20) NOT NULL,
  limit_units BIGINT NOT NULL DEFAULT 0,
  enforcement_mode VARCHAR(20) NOT NULL DEFAULT 'MONITOR',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_quota_policy_limit_non_negative CHECK (limit_units >= 0),
  CONSTRAINT chk_quota_policy_enforcement
    CHECK (enforcement_mode IN ('MONITOR', 'ENFORCE'))
);

-- Tenant-scoped policy lookup for a metric.
CREATE INDEX IF NOT EXISTS idx_quota_policy_tenant_metric
  ON quota_policy(tenant_id, metric_type);
