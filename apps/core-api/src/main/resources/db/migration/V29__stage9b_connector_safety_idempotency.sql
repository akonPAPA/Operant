ALTER TABLE change_request
  ADD COLUMN IF NOT EXISTS connector_idempotency_key VARCHAR(255),
  ADD COLUMN IF NOT EXISTS connector_attempt_count INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS connector_max_attempts INTEGER NOT NULL DEFAULT 3,
  ADD COLUMN IF NOT EXISTS connector_last_attempt_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS connector_next_retry_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS connector_failure_type VARCHAR(40),
  ADD COLUMN IF NOT EXISTS connector_retryable BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX IF NOT EXISTS idx_change_request_tenant_connector_idempotency
  ON change_request(tenant_id, connector_idempotency_key)
  WHERE connector_idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_change_request_tenant_retry
  ON change_request(tenant_id, connector_retryable, connector_next_retry_at DESC);

CREATE TABLE IF NOT EXISTS connector_credential_ref (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  integration_connection_id UUID NOT NULL REFERENCES integration_connection(id),
  secret_ref VARCHAR(255) NOT NULL,
  status VARCHAR(40) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_connector_credential_ref_tenant_connection
  ON connector_credential_ref(tenant_id, integration_connection_id, created_at DESC);
