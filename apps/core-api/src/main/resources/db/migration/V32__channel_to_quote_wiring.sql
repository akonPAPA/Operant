CREATE TABLE IF NOT EXISTS quote_conversion_attempt (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  source_type VARCHAR(60) NOT NULL,
  source_id UUID NOT NULL,
  status VARCHAR(60) NOT NULL,
  quote_id UUID NULL REFERENCES draft_quote(id),
  failure_code VARCHAR(120) NULL,
  failure_message TEXT NULL,
  validation_summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  triggered_by UUID NULL,
  triggered_by_type VARCHAR(20) NOT NULL DEFAULT 'SYSTEM',
  idempotency_key VARCHAR(180) NULL,
  request_mode VARCHAR(20) NOT NULL DEFAULT 'CREATE',
  CONSTRAINT ck_quote_conversion_attempt_status CHECK (status IN ('READY_FOR_DRAFT_QUOTE', 'NEEDS_REVIEW', 'REJECTED_INVALID_SOURCE', 'REJECTED_NO_LINE_ITEMS', 'REJECTED_CUSTOMER_UNRESOLVED', 'REJECTED_VALIDATION_FAILED')),
  CONSTRAINT ck_quote_conversion_attempt_actor_type CHECK (triggered_by_type IN ('USER', 'BOT', 'SYSTEM', 'API')),
  CONSTRAINT ck_quote_conversion_attempt_request_mode CHECK (request_mode IN ('DRY_RUN', 'CREATE'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_quote_conversion_attempt_idempotency
  ON quote_conversion_attempt(tenant_id, source_type, source_id, idempotency_key, request_mode)
  WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_quote_conversion_attempt_source
  ON quote_conversion_attempt(tenant_id, source_type, source_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_quote_conversion_attempt_quote
  ON quote_conversion_attempt(tenant_id, quote_id, created_at DESC)
  WHERE quote_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS quote_source_link (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  quote_id UUID NOT NULL REFERENCES draft_quote(id),
  source_type VARCHAR(60) NOT NULL,
  source_id UUID NOT NULL,
  source_channel VARCHAR(80) NULL,
  source_external_ref VARCHAR(255) NULL,
  source_received_at TIMESTAMPTZ NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_by UUID NULL,
  created_by_type VARCHAR(20) NOT NULL DEFAULT 'SYSTEM',
  metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  CONSTRAINT ck_quote_source_link_actor_type CHECK (created_by_type IN ('USER', 'BOT', 'SYSTEM', 'API'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_quote_source_link_tenant_quote_source
  ON quote_source_link(tenant_id, quote_id, source_type, source_id);

CREATE INDEX IF NOT EXISTS idx_quote_source_link_source
  ON quote_source_link(tenant_id, source_type, source_id, source_received_at DESC);

CREATE INDEX IF NOT EXISTS idx_quote_source_link_quote
  ON quote_source_link(tenant_id, quote_id);
