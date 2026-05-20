CREATE TABLE connector_command (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  change_request_id UUID NOT NULL REFERENCES change_request(id),
  outbox_event_id UUID NULL REFERENCES outbox_event(id),
  connector_type VARCHAR(40) NOT NULL,
  operation_type VARCHAR(60) NOT NULL,
  idempotency_key VARCHAR(180) NOT NULL,
  payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  status VARCHAR(40) NOT NULL DEFAULT 'EXECUTION_DISABLED',
  attempt_count INTEGER NOT NULL DEFAULT 0,
  max_attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NULL,
  last_error TEXT NULL,
  dead_letter_reason TEXT NULL,
  retryable BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_connector_command_status CHECK (status IN ('CREATED', 'READY_INTERNAL_ONLY', 'EXECUTION_DISABLED', 'SKIPPED_EXTERNAL_DISABLED', 'FAILED_VALIDATION', 'DEAD_LETTERED'))
);

CREATE UNIQUE INDEX uq_connector_command_tenant_idempotency
  ON connector_command(tenant_id, idempotency_key);

CREATE INDEX idx_connector_command_tenant_status
  ON connector_command(tenant_id, status, created_at ASC);

CREATE INDEX idx_connector_command_change_request
  ON connector_command(tenant_id, change_request_id, created_at DESC);
