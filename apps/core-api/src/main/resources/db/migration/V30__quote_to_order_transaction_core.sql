ALTER TABLE draft_quote
  ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(180);

CREATE UNIQUE INDEX IF NOT EXISTS uq_draft_quote_tenant_idempotency
  ON draft_quote(tenant_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS quote_approval_request (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  draft_quote_id UUID NOT NULL REFERENCES draft_quote(id),
  draft_quote_line_id UUID NULL REFERENCES draft_quote_line(id),
  request_type VARCHAR(80) NOT NULL,
  severity VARCHAR(20) NOT NULL,
  reason_code VARCHAR(120) NOT NULL,
  reason TEXT NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_quote_approval_request_tenant_quote
  ON quote_approval_request(tenant_id, draft_quote_id, created_at ASC);

CREATE INDEX IF NOT EXISTS idx_quote_approval_request_tenant_status
  ON quote_approval_request(tenant_id, status, created_at DESC);
