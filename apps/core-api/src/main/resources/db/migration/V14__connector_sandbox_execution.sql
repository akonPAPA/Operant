CREATE TABLE connector_sandbox_execution (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  connector_command_id UUID NOT NULL REFERENCES connector_command(id),
  change_request_id UUID NULL REFERENCES change_request(id),
  target_system_type VARCHAR(60) NOT NULL,
  requested_by_actor_id UUID NULL,
  execution_mode VARCHAR(20) NOT NULL DEFAULT 'DRY_RUN',
  status VARCHAR(40) NOT NULL,
  dry_run_key VARCHAR(220) NOT NULL,
  generated_payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  simulated_provider_response_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  validation_summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  warnings_json JSONB NOT NULL DEFAULT '[]'::jsonb,
  error_code VARCHAR(120) NULL,
  error_message TEXT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at TIMESTAMPTZ NULL,
  completed_at TIMESTAMPTZ NULL,
  audit_correlation_id UUID NULL,
  CONSTRAINT ck_connector_sandbox_execution_mode CHECK (execution_mode = 'DRY_RUN'),
  CONSTRAINT ck_connector_sandbox_execution_status CHECK (status IN ('REQUESTED', 'POLICY_BLOCKED', 'VALIDATION_FAILED', 'READY', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
);

CREATE UNIQUE INDEX uq_connector_sandbox_execution_tenant_key
  ON connector_sandbox_execution(tenant_id, dry_run_key);

CREATE INDEX idx_connector_sandbox_execution_tenant_command
  ON connector_sandbox_execution(tenant_id, connector_command_id);

CREATE INDEX idx_connector_sandbox_execution_tenant_status
  ON connector_sandbox_execution(tenant_id, status);

CREATE INDEX idx_connector_sandbox_execution_tenant_created
  ON connector_sandbox_execution(tenant_id, created_at DESC);
