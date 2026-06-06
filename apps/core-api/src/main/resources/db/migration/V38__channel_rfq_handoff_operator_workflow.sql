-- OP-CAP-06C RFQ Handoff Operator Workflow.
-- Adds operator-review workflow metadata to the existing channel_rfq_handoff table (V37).
-- Additive and backward-compatible: all new columns are nullable, no existing column/constraint is
-- changed, no data is deleted, no unrelated table is touched. The handoff remains a reviewable draft
-- request only -- these columns never represent a quote/order/approval or an external/ERP write.

ALTER TABLE channel_rfq_handoff
  ADD COLUMN IF NOT EXISTS reviewer_user_id UUID NULL,
  ADD COLUMN IF NOT EXISTS review_started_at TIMESTAMPTZ NULL,
  ADD COLUMN IF NOT EXISTS dismissed_at TIMESTAMPTZ NULL,
  ADD COLUMN IF NOT EXISTS dismiss_reason VARCHAR(1000) NULL,
  ADD COLUMN IF NOT EXISTS converted_at TIMESTAMPTZ NULL,
  ADD COLUMN IF NOT EXISTS conversion_note VARCHAR(1000) NULL;
