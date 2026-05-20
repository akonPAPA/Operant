CREATE TABLE change_request (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  target_system VARCHAR(40) NOT NULL,
  target_entity VARCHAR(60) NOT NULL,
  requested_action VARCHAR(40) NOT NULL,
  source_type VARCHAR(60) NOT NULL,
  source_id UUID NOT NULL,
  request_payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  validation_status VARCHAR(40) NOT NULL DEFAULT 'PENDING_VALIDATION',
  approval_status VARCHAR(40) NOT NULL DEFAULT 'PENDING_APPROVAL',
  execution_status VARCHAR(40) NOT NULL DEFAULT 'EXECUTION_DISABLED',
  idempotency_key VARCHAR(160) NULL,
  created_by_user_id UUID NULL,
  approved_by_user_id UUID NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  validated_at TIMESTAMPTZ NULL,
  approved_at TIMESTAMPTZ NULL,
  rejected_at TIMESTAMPTZ NULL,
  executed_at TIMESTAMPTZ NULL,
  external_reference VARCHAR(255) NULL,
  failure_reason TEXT NULL,
  CONSTRAINT ck_change_request_validation_status CHECK (validation_status IN ('PENDING_VALIDATION', 'VALIDATED', 'VALIDATION_FAILED')),
  CONSTRAINT ck_change_request_approval_status CHECK (approval_status IN ('NOT_REQUIRED', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED')),
  CONSTRAINT ck_change_request_execution_status CHECK (execution_status IN ('NOT_EXECUTABLE', 'QUEUED_INTERNAL_ONLY', 'EXECUTION_DISABLED', 'EXECUTED', 'FAILED')),
  CONSTRAINT ck_change_request_stage10c_no_external_execution CHECK (execution_status <> 'EXECUTED' AND executed_at IS NULL AND external_reference IS NULL)
);

CREATE TABLE outbox_event (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  aggregate_type VARCHAR(80) NOT NULL,
  aggregate_id UUID NOT NULL,
  event_type VARCHAR(120) NOT NULL,
  payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ NULL,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT NULL,
  CONSTRAINT ck_outbox_event_status CHECK (status IN ('PENDING', 'PUBLISHED_INTERNAL_ONLY', 'FAILED', 'SKIPPED_EXTERNAL_DISABLED'))
);

CREATE UNIQUE INDEX uq_change_request_tenant_idempotency_key
  ON change_request(tenant_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_change_request_tenant_status
  ON change_request(tenant_id, validation_status, approval_status, execution_status, created_at DESC);

CREATE INDEX idx_change_request_tenant_source
  ON change_request(tenant_id, source_type, source_id, created_at DESC);

CREATE INDEX idx_outbox_event_tenant_aggregate
  ON outbox_event(tenant_id, aggregate_type, aggregate_id, created_at DESC);

CREATE INDEX idx_outbox_event_tenant_status
  ON outbox_event(tenant_id, status, created_at DESC);
