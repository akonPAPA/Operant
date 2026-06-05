-- OP-CAP-07A AI Agent Work Layer
-- Stores AI Work Assistant suggestions as UNTRUSTED, advisory-only artifacts.
-- These rows are never a source of trusted business state: accepting a suggestion only
-- records operator intent. Any final quote/order/inventory/pricing/customer mutation must
-- still flow through the existing typed backend command services.
-- Additive and non-destructive: new table only, no renames, no data deletion.

CREATE TABLE IF NOT EXISTS ai_work_suggestion (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  work_type VARCHAR(40) NOT NULL,
  source_type VARCHAR(40) NOT NULL,
  source_id UUID NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'GENERATED',
  strategy_version VARCHAR(60) NOT NULL,
  risk_level VARCHAR(16) NOT NULL DEFAULT 'LOW',
  confidence NUMERIC(4,3) NULL,
  generated_text TEXT NOT NULL,
  structured_payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  evidence_refs_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  idempotency_key VARCHAR(180) NULL,
  created_by_user_id UUID NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  decided_by_user_id UUID NULL,
  decided_at TIMESTAMPTZ NULL,
  decision_reason VARCHAR(500) NULL,
  CONSTRAINT chk_ai_work_suggestion_status
    CHECK (status IN ('GENERATED', 'ACCEPTED', 'REJECTED')),
  CONSTRAINT chk_ai_work_suggestion_work_type
    CHECK (work_type IN ('REQUEST_SUMMARY', 'VALIDATION_EXPLANATION', 'CUSTOMER_REPLY_DRAFT',
                         'NEXT_ACTION_SUGGESTION', 'SOURCE_CONTEXT_DIGEST')),
  CONSTRAINT chk_ai_work_suggestion_risk
    CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH'))
);

-- Hot path: list advisory suggestions for one source object, newest first, tenant-scoped.
CREATE INDEX IF NOT EXISTS idx_ai_work_suggestion_source
  ON ai_work_suggestion(tenant_id, source_type, source_id, created_at DESC);

-- Operator queue view: open vs decided suggestions per tenant.
CREATE INDEX IF NOT EXISTS idx_ai_work_suggestion_tenant_status
  ON ai_work_suggestion(tenant_id, status, created_at DESC);

-- Retry-safe create: a client-supplied idempotency key dedupes generation per tenant.
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_work_suggestion_idempotency
  ON ai_work_suggestion(tenant_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;
