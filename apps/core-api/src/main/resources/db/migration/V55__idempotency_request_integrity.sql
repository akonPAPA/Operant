ALTER TABLE idempotency_key
  ADD COLUMN IF NOT EXISTS actor_id UUID NULL,
  ADD COLUMN IF NOT EXISTS command_type VARCHAR(160) NULL,
  ADD COLUMN IF NOT EXISTS target_resource_type VARCHAR(120) NULL,
  ADD COLUMN IF NOT EXISTS target_resource_id VARCHAR(160) NULL,
  ADD COLUMN IF NOT EXISTS status VARCHAR(40) NOT NULL DEFAULT 'SUCCEEDED',
  ADD COLUMN IF NOT EXISTS error_code VARCHAR(120) NULL,
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_idempotency_key_tenant_actor
  ON idempotency_key(tenant_id, actor_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_idempotency_key_tenant_target
  ON idempotency_key(tenant_id, target_resource_type, target_resource_id, created_at DESC);
