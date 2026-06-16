-- OP-CAP-08B AI validation handoff operator review.
-- Additive only: records operator review lifecycle state for 07F handoffs.
-- This table is not a quote/order/draft table and has no external execution authority.

CREATE TABLE ai_validation_handoff_review (
  id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL,
  handoff_id UUID NOT NULL REFERENCES ai_validation_handoff(id),
  review_status VARCHAR(64) NOT NULL,
  decision VARCHAR(64),
  reason_code VARCHAR(80),
  note VARCHAR(500),
  correction_summary VARCHAR(500),
  corrected_intent VARCHAR(120),
  corrected_customer_ref VARCHAR(160),
  corrected_line_count INTEGER,
  reviewed_by VARCHAR(120),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_ai_handoff_review_tenant_handoff UNIQUE (tenant_id, handoff_id),
  CONSTRAINT ck_ai_handoff_review_status CHECK (review_status IN (
    'PENDING_REVIEW',
    'IN_REVIEW',
    'CORRECTION_REQUESTED',
    'DRAFT_PREPARATION_READY',
    'BLOCKED',
    'DISMISSED',
    'FAILED'
  )),
  CONSTRAINT ck_ai_handoff_review_decision CHECK (
    decision IS NULL OR decision IN (
      'APPROVE_FOR_DRAFT_PREPARATION',
      'REQUEST_CORRECTION',
      'DISMISS_INVALID',
      'BLOCK_RISK',
      'KEEP_FOR_HUMAN_REVIEW'
    )
  ),
  CONSTRAINT ck_ai_handoff_review_corrected_line_count CHECK (
    corrected_line_count IS NULL OR corrected_line_count >= 0
  )
);

CREATE INDEX idx_ai_handoff_review_tenant_status_updated
  ON ai_validation_handoff_review(tenant_id, review_status, updated_at DESC);

CREATE INDEX idx_ai_handoff_review_tenant_updated
  ON ai_validation_handoff_review(tenant_id, updated_at DESC);
