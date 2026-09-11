-- M18-01 AI bot draft/generation/preview foundation.
-- Declarative bot definitions and durable AiJob records. No channel delivery, no executable tools.

CREATE TABLE aibot_bot_definition (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  public_id VARCHAR(40) NOT NULL,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  name VARCHAR(120) NOT NULL,
  description VARCHAR(2000) NOT NULL DEFAULT '',
  created_by UUID NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_aibot_bot_definition_public_id UNIQUE (public_id),
  CONSTRAINT uq_aibot_bot_definition_tenant_name UNIQUE (tenant_id, name),
  CONSTRAINT uq_aibot_bot_definition_tenant_id UNIQUE (tenant_id, id)
);

CREATE INDEX idx_aibot_bot_definition_tenant
  ON aibot_bot_definition(tenant_id, updated_at DESC);

CREATE TABLE aibot_bot_definition_version (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  public_id VARCHAR(40) NOT NULL,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  bot_definition_id UUID NOT NULL,
  version_number INT NOT NULL,
  state VARCHAR(20) NOT NULL,
  schema_version VARCHAR(64) NOT NULL,
  configuration_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  validation_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  provider_provenance_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_aibot_bot_definition_version_public_id UNIQUE (public_id),
  CONSTRAINT uq_aibot_bot_definition_version_number UNIQUE (bot_definition_id, version_number),
  CONSTRAINT uq_aibot_bot_definition_version_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT fk_aibot_bot_definition_version_tenant_bot
    FOREIGN KEY (tenant_id, bot_definition_id)
    REFERENCES aibot_bot_definition(tenant_id, id),
  CONSTRAINT chk_aibot_bot_definition_version_state
    CHECK (state IN ('DRAFT', 'GENERATING', 'VALIDATING', 'VALIDATED', 'INVALID')),
  CONSTRAINT chk_aibot_bot_definition_version_number CHECK (version_number >= 1)
);

CREATE INDEX idx_aibot_bot_definition_version_tenant
  ON aibot_bot_definition_version(tenant_id, bot_definition_id, version_number);

CREATE TABLE aibot_ai_job (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  public_id VARCHAR(40) NOT NULL,
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  purpose VARCHAR(64) NOT NULL,
  bot_definition_version_id UUID NOT NULL,
  status VARCHAR(32) NOT NULL,
  provider_key VARCHAR(64) NOT NULL DEFAULT 'none',
  model_key VARCHAR(64) NOT NULL DEFAULT 'none',
  request_schema_version VARCHAR(64) NULL,
  response_schema_version VARCHAR(64) NULL,
  input_hash VARCHAR(128) NULL,
  output_hash VARCHAR(128) NULL,
  request_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  request_fingerprint VARCHAR(128) NULL,
  input_classification VARCHAR(64) NULL,
  result_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  failure_class VARCHAR(80) NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  lease_owner VARCHAR(128) NULL,
  lease_until TIMESTAMPTZ NULL,
  fencing_token BIGINT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at TIMESTAMPTZ NULL,
  completed_at TIMESTAMPTZ NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_aibot_ai_job_public_id UNIQUE (public_id),
  CONSTRAINT uq_aibot_ai_job_idempotency UNIQUE (tenant_id, purpose, idempotency_key),
  CONSTRAINT uq_aibot_ai_job_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT fk_aibot_ai_job_tenant_version
    FOREIGN KEY (tenant_id, bot_definition_version_id)
    REFERENCES aibot_bot_definition_version(tenant_id, id),
  CONSTRAINT chk_aibot_ai_job_status
    CHECK (status IN (
      'REQUESTED','ADMITTED','REJECTED','LEASED','RUNNING','OUTPUT_RECEIVED',
      'VALIDATED','INVALID','STALE','SUGGESTION_READY','FAILED','CANCELLED'
    )),
  CONSTRAINT chk_aibot_ai_job_purpose
    CHECK (purpose IN ('BOT_DEFINITION_GENERATION', 'BOT_INTENT_CLASSIFICATION'))
);

CREATE INDEX idx_aibot_ai_job_tenant_status
  ON aibot_ai_job(tenant_id, status, created_at DESC);

CREATE INDEX idx_aibot_ai_job_claimable
  ON aibot_ai_job(status, next_attempt_at, lease_until, created_at, id);

CREATE INDEX idx_aibot_ai_job_lease_until
  ON aibot_ai_job(lease_until)
  WHERE lease_until IS NOT NULL;
