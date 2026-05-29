ALTER TABLE quote_approval_request
  ADD COLUMN IF NOT EXISTS decision_comment TEXT,
  ADD COLUMN IF NOT EXISTS decided_by UUID NULL,
  ADD COLUMN IF NOT EXISTS decided_at TIMESTAMPTZ NULL;

CREATE TABLE IF NOT EXISTS quote_approval_decision (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  draft_quote_id UUID NOT NULL REFERENCES draft_quote(id),
  approval_request_id UUID NULL REFERENCES quote_approval_request(id),
  decision VARCHAR(40) NOT NULL,
  decision_comment TEXT NULL,
  decided_by UUID NULL,
  decided_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  previous_quote_status VARCHAR(40) NOT NULL,
  new_quote_status VARCHAR(40) NOT NULL,
  resolved_reasons_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  blocking_reasons_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  audit_correlation_id UUID NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_quote_approval_decision_tenant_quote
  ON quote_approval_decision(tenant_id, draft_quote_id, decided_at DESC);

CREATE INDEX IF NOT EXISTS idx_draft_quote_tenant_status_created
  ON draft_quote(tenant_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_quote_validation_issue_tenant_quote_status
  ON quote_validation_issue(tenant_id, draft_quote_id, status, blocking);

CREATE TABLE IF NOT EXISTS quote_internal_order_boundary (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  draft_quote_id UUID NOT NULL REFERENCES draft_quote(id),
  status VARCHAR(60) NOT NULL,
  external_execution_status VARCHAR(60) NOT NULL,
  change_request_id UUID NULL REFERENCES change_request(id),
  idempotency_key VARCHAR(180) NOT NULL,
  created_by UUID NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_quote_internal_order_boundary_external_disabled CHECK (external_execution_status = 'EXTERNAL_EXECUTION_DISABLED')
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_quote_internal_order_boundary_tenant_quote
  ON quote_internal_order_boundary(tenant_id, draft_quote_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_quote_internal_order_boundary_tenant_idempotency
  ON quote_internal_order_boundary(tenant_id, idempotency_key);

CREATE INDEX IF NOT EXISTS idx_quote_internal_order_boundary_tenant_status
  ON quote_internal_order_boundary(tenant_id, status, created_at DESC);
