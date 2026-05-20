CREATE TABLE quote_handoff_snapshot (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  draft_quote_id UUID NOT NULL REFERENCES draft_quote(id),
  status VARCHAR(40) NOT NULL,
  payload_version INTEGER NOT NULL,
  payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  payload_hash VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(180) NOT NULL,
  generated_by UUID NULL,
  generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_quote_handoff_snapshot_status CHECK (status IN ('HANDOFF_PREPARED', 'HANDOFF_CANCELLED'))
);

ALTER TABLE change_request
  ADD COLUMN IF NOT EXISTS payload_snapshot_id UUID NULL REFERENCES quote_handoff_snapshot(id),
  ADD COLUMN IF NOT EXISTS payload_hash VARCHAR(128),
  ADD COLUMN IF NOT EXISTS cancellation_reason TEXT;

ALTER TABLE change_request
  DROP CONSTRAINT IF EXISTS ck_change_request_validation_status,
  DROP CONSTRAINT IF EXISTS ck_change_request_approval_status,
  DROP CONSTRAINT IF EXISTS ck_change_request_execution_status,
  DROP CONSTRAINT IF EXISTS ck_change_request_stage10c_no_external_execution;

ALTER TABLE change_request
  ADD CONSTRAINT ck_change_request_validation_status CHECK (validation_status IN ('PENDING_VALIDATION', 'VALIDATED', 'VALIDATION_FAILED', 'VALID', 'INVALID', 'BLOCKED')),
  ADD CONSTRAINT ck_change_request_approval_status CHECK (approval_status IN ('NOT_REQUIRED', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'DRAFT', 'PENDING_APPROVAL', 'APPROVED_INTERNAL', 'CANCELLED')),
  ADD CONSTRAINT ck_change_request_execution_status CHECK (execution_status IN ('NOT_EXECUTABLE', 'QUEUED_INTERNAL_ONLY', 'EXECUTION_DISABLED', 'EXECUTED', 'FAILED', 'NOT_EXECUTABLE_IN_STAGE_11E', 'NOT_EXECUTED')),
  ADD CONSTRAINT ck_change_request_no_external_execution CHECK (execution_status <> 'EXECUTED' AND executed_at IS NULL AND external_reference IS NULL);

CREATE UNIQUE INDEX uq_quote_handoff_snapshot_tenant_idempotency
  ON quote_handoff_snapshot(tenant_id, idempotency_key);

CREATE UNIQUE INDEX uq_quote_handoff_snapshot_tenant_quote_hash
  ON quote_handoff_snapshot(tenant_id, draft_quote_id, payload_hash);

CREATE INDEX idx_quote_handoff_snapshot_tenant_quote
  ON quote_handoff_snapshot(tenant_id, draft_quote_id, generated_at DESC);

CREATE INDEX idx_change_request_tenant_payload_snapshot
  ON change_request(tenant_id, payload_snapshot_id);
