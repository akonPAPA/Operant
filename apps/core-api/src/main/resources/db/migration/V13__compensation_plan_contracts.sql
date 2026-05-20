CREATE TABLE compensation_plan (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  source_change_request_id UUID NULL REFERENCES change_request(id),
  connector_command_id UUID NULL REFERENCES connector_command(id),
  target_system_type VARCHAR(60) NOT NULL,
  target_external_reference VARCHAR(255) NULL,
  compensation_action_type VARCHAR(60) NOT NULL,
  reason_code VARCHAR(80) NOT NULL,
  human_readable_reason TEXT NOT NULL,
  requires_human_approval BOOLEAN NOT NULL DEFAULT false,
  safe_to_auto_execute BOOLEAN NOT NULL DEFAULT false,
  status VARCHAR(60) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by UUID NULL,
  audit_correlation_id UUID NOT NULL,
  CONSTRAINT ck_compensation_plan_safe_default CHECK (safe_to_auto_execute = false),
  CONSTRAINT ck_compensation_plan_action CHECK (compensation_action_type IN ('NOOP', 'CANCEL_EXTERNAL_DRAFT', 'REVERSE_CONNECTOR_COMMAND', 'CREATE_CORRECTIVE_COMMAND', 'CREATE_MANUAL_REVIEW_TASK', 'MARK_EXTERNAL_SYSTEM_UNCHANGED', 'DOCUMENT_ONLY')),
  CONSTRAINT ck_compensation_plan_reason CHECK (reason_code IN ('EXTERNAL_WRITE_NOT_EXECUTED', 'EXTERNAL_WRITE_PARTIALLY_EXECUTED', 'EXTERNAL_WRITE_FAILED_AFTER_LOCAL_STATE', 'EXTERNAL_TIMEOUT_UNKNOWN_STATE', 'VALIDATION_FAILED_BEFORE_EXECUTION', 'APPROVAL_REVOKED', 'TENANT_POLICY_BLOCKED', 'MANUAL_REVIEW_REQUESTED')),
  CONSTRAINT ck_compensation_plan_status CHECK (status IN ('NOT_REQUIRED', 'PLANNED', 'APPROVAL_REQUIRED', 'MANUAL_REVIEW_REQUIRED', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_compensation_plan_tenant_created
  ON compensation_plan(tenant_id, created_at DESC);

CREATE INDEX idx_compensation_plan_connector_command
  ON compensation_plan(tenant_id, connector_command_id, created_at DESC);

CREATE INDEX idx_compensation_plan_change_request
  ON compensation_plan(tenant_id, source_change_request_id, created_at DESC);
