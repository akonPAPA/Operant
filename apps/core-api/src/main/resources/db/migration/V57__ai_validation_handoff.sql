-- OP-CAP-07F AI Validation Decision Handoff Foundation.
-- Materializes a deterministic AI advisory validation routing decision (OP-CAP-07E
-- ai_extraction_validation) into ONE tenant-scoped, operator-reviewable handoff work item.
-- This row is advisory routing metadata only: it is NEVER a quote/order, carries no business
-- authority, and creating/refreshing it mutates no master data and triggers no external/ERP write.
-- Only READY_FOR_DRAFT_REVIEW is draft-eligible. Additive and non-destructive: new table only.

CREATE TABLE IF NOT EXISTS ai_validation_handoff (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  validation_id UUID NOT NULL REFERENCES ai_extraction_validation(id) ON DELETE CASCADE,
  extraction_result_id UUID NOT NULL,
  extraction_run_id UUID NULL,
  processing_job_id UUID NULL,
  source_type VARCHAR(40) NOT NULL,
  source_id UUID NULL,
  status VARCHAR(40) NOT NULL,
  routing_decision VARCHAR(40) NOT NULL,
  risk_level VARCHAR(16) NOT NULL,
  intent VARCHAR(120) NULL,
  customer_ref VARCHAR(160) NULL,
  line_count INTEGER NOT NULL DEFAULT 0,
  issue_count INTEGER NOT NULL DEFAULT 0,
  highest_severity VARCHAR(16) NULL,
  prompt_injection_signal_count INTEGER NOT NULL DEFAULT 0,
  unknown_customer BOOLEAN NOT NULL DEFAULT FALSE,
  draft_eligible BOOLEAN NOT NULL DEFAULT FALSE,
  issue_summary VARCHAR(500) NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_ai_validation_handoff_status
    CHECK (status IN ('READY_FOR_DRAFT_REVIEW', 'NEEDS_HUMAN_REVIEW',
                      'BLOCKED_INVALID_EXTRACTION', 'FAILED_VALIDATION')),
  CONSTRAINT chk_ai_validation_handoff_routing
    CHECK (routing_decision IN ('READY_FOR_DRAFT_REVIEW', 'NEEDS_HUMAN_REVIEW',
                                'BLOCKED_INVALID_EXTRACTION', 'FAILED_VALIDATION')),
  CONSTRAINT chk_ai_validation_handoff_risk
    CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'BLOCKED'))
);

-- Idempotency: at most one handoff per validation per tenant. Re-generation refreshes in place.
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_validation_handoff_validation
  ON ai_validation_handoff(tenant_id, validation_id);

-- Operator queue: list handoffs per tenant by status, most-recently-updated first.
CREATE INDEX IF NOT EXISTS idx_ai_validation_handoff_tenant_status
  ON ai_validation_handoff(tenant_id, status, updated_at DESC);

-- Correlation lookup back to the originating advisory extraction result.
CREATE INDEX IF NOT EXISTS idx_ai_validation_handoff_extraction_result
  ON ai_validation_handoff(tenant_id, extraction_result_id);
