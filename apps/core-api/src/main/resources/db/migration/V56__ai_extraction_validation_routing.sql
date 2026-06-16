-- OP-CAP-07E Deterministic Validation & Risk Routing for advisory AI extraction results.
-- Persists the deterministic validation/risk/routing outcome computed over an UNTRUSTED advisory
-- AI extraction result (OP-CAP-07D). These rows describe risk/routing only; they are never a quote,
-- order, or any business mutation, and the routing decision never authorizes an external/ERP write.
-- Additive and non-destructive: new tables only, no renames, no data deletion.

CREATE TABLE IF NOT EXISTS ai_extraction_validation (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  extraction_result_id UUID NOT NULL,
  extraction_run_id UUID NULL,
  processing_job_id UUID NULL,
  source_type VARCHAR(40) NOT NULL,
  source_id UUID NULL,
  risk_level VARCHAR(16) NOT NULL,
  routing_decision VARCHAR(40) NOT NULL,
  status VARCHAR(20) NOT NULL,
  issue_count INTEGER NOT NULL DEFAULT 0,
  highest_severity VARCHAR(16) NULL,
  prompt_injection_signal_count INTEGER NOT NULL DEFAULT 0,
  unknown_product_count INTEGER NOT NULL DEFAULT 0,
  unknown_customer BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_ai_extraction_validation_risk
    CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'BLOCKED')),
  CONSTRAINT chk_ai_extraction_validation_routing
    CHECK (routing_decision IN ('READY_FOR_DRAFT_REVIEW', 'NEEDS_HUMAN_REVIEW',
                                'BLOCKED_INVALID_EXTRACTION', 'FAILED_VALIDATION'))
);

-- Idempotency: at most one advisory validation per extraction result per tenant. Re-validation
-- replaces issues and recomputes this header row in place.
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_extraction_validation_result
  ON ai_extraction_validation(tenant_id, extraction_result_id);

CREATE TABLE IF NOT EXISTS ai_extraction_validation_issue (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  ai_extraction_validation_id UUID NOT NULL REFERENCES ai_extraction_validation(id) ON DELETE CASCADE,
  extraction_result_id UUID NOT NULL,
  source_type VARCHAR(40) NOT NULL,
  source_id UUID NULL,
  issue_code VARCHAR(40) NOT NULL,
  severity VARCHAR(16) NOT NULL,
  field_name VARCHAR(80) NULL,
  line_index INTEGER NULL,
  message VARCHAR(500) NOT NULL,
  evidence_ref VARCHAR(200) NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Hot path: list the issues for one advisory validation, tenant-scoped.
CREATE INDEX IF NOT EXISTS idx_ai_extraction_validation_issue_parent
  ON ai_extraction_validation_issue(tenant_id, ai_extraction_validation_id, created_at);
