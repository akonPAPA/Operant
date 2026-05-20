ALTER TABLE draft_quote
  ADD COLUMN IF NOT EXISTS source_type VARCHAR(40) NULL,
  ADD COLUMN IF NOT EXISTS source_message_id UUID NULL REFERENCES channel_message(id),
  ADD COLUMN IF NOT EXISTS source_document_id UUID NULL REFERENCES inbound_document(id),
  ADD COLUMN IF NOT EXISTS customer_display_name VARCHAR(255) NULL,
  ADD COLUMN IF NOT EXISTS validation_status VARCHAR(40) NULL,
  ADD COLUMN IF NOT EXISTS requires_human_review BOOLEAN NOT NULL DEFAULT true,
  ADD COLUMN IF NOT EXISTS audit_correlation_id UUID NULL;

ALTER TABLE draft_quote_line
  ADD COLUMN IF NOT EXISTS raw_text TEXT NULL,
  ADD COLUMN IF NOT EXISTS raw_sku VARCHAR(255) NULL,
  ADD COLUMN IF NOT EXISTS normalized_sku VARCHAR(255) NULL,
  ADD COLUMN IF NOT EXISTS product_name VARCHAR(255) NULL,
  ADD COLUMN IF NOT EXISTS requested_location VARCHAR(255) NULL,
  ADD COLUMN IF NOT EXISTS available_stock NUMERIC(18,4) NULL,
  ADD COLUMN IF NOT EXISTS confidence_score NUMERIC(8,4) NULL,
  ADD COLUMN IF NOT EXISTS issue_codes JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE TABLE quote_validation_issue (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  draft_quote_id UUID NOT NULL REFERENCES draft_quote(id),
  draft_quote_line_id UUID NULL REFERENCES draft_quote_line(id),
  issue_code VARCHAR(80) NOT NULL,
  severity VARCHAR(20) NOT NULL,
  blocking BOOLEAN NOT NULL DEFAULT true,
  message TEXT NOT NULL,
  details_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_draft_quote_tenant_source_message
  ON draft_quote(tenant_id, source_message_id);

CREATE INDEX idx_draft_quote_tenant_source_document
  ON draft_quote(tenant_id, source_document_id);

CREATE INDEX idx_draft_quote_tenant_source_type
  ON draft_quote(tenant_id, source_type, created_at DESC);

CREATE INDEX idx_quote_validation_issue_tenant_quote
  ON quote_validation_issue(tenant_id, draft_quote_id, created_at ASC);

CREATE INDEX idx_quote_validation_issue_tenant_code
  ON quote_validation_issue(tenant_id, issue_code, status);
