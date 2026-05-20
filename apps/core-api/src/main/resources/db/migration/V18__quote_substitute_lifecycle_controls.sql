ALTER TABLE draft_quote_line
  ADD COLUMN IF NOT EXISTS substitute_decision_status VARCHAR(60) NOT NULL DEFAULT 'NO_SUBSTITUTE_REQUIRED',
  ADD COLUMN IF NOT EXISTS substitute_decision_reason_code VARCHAR(120),
  ADD COLUMN IF NOT EXISTS substitute_decided_by UUID,
  ADD COLUMN IF NOT EXISTS substitute_decided_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS substitute_decision_note TEXT;

ALTER TABLE quote_validation_issue
  ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_draft_quote_line_tenant_quote_substitute_decision
  ON draft_quote_line(tenant_id, draft_quote_id, substitute_decision_status);

CREATE INDEX IF NOT EXISTS idx_quote_validation_issue_tenant_quote_status_blocking
  ON quote_validation_issue(tenant_id, draft_quote_id, status, blocking);
