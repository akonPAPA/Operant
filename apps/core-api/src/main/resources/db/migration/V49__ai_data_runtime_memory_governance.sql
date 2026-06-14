-- OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance.
-- Additive, tenant-scoped storage + governance primitives for reusable, bounded, sanitized AI/runtime
-- knowledge derived from approved OrderPilot workflow events. This is NOT model training, NOT a global
-- memory, and NOT raw prompt/document storage. AI memory is advisory and low-authority — it is never the
-- source of truth for orders, quotes, prices, stock, payments, counterparty trust, or approval status;
-- deterministic backend services remain authoritative. No raw documents, OCR text, prompts, customer
-- messages, secrets, card data, bank credentials, or full PII are ever stored here — only bounded, typed,
-- sanitized facts/signals and safe metadata. Every table is tenant-isolated via tenant_id. All text fields
-- are bounded VARCHAR (no unbounded TEXT). Mutation happens only through the backend governance service.

CREATE TABLE IF NOT EXISTS ai_memory_record (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id            UUID NOT NULL REFERENCES tenant(id),
  namespace            VARCHAR(48) NOT NULL,
  memory_key           VARCHAR(160) NOT NULL,
  memory_type          VARCHAR(24) NOT NULL,
  status               VARCHAR(16) NOT NULL,
  authority_level      VARCHAR(24) NOT NULL,
  source_type          VARCHAR(32) NOT NULL,
  source_id            UUID NULL,
  source_ref           VARCHAR(160) NULL,
  title                VARCHAR(160) NOT NULL,
  summary              VARCHAR(512) NOT NULL,
  normalized_value     VARCHAR(256) NULL,
  confidence           NUMERIC(5,4) NOT NULL,
  weight               INTEGER NOT NULL,
  version              INTEGER NOT NULL,
  expires_at           TIMESTAMPTZ NULL,
  invalidated_at       TIMESTAMPTZ NULL,
  invalidation_reason  VARCHAR(280) NULL,
  created_by           UUID NULL,
  created_at           TIMESTAMPTZ NOT NULL,
  updated_at           TIMESTAMPTZ NOT NULL,
  last_accessed_at     TIMESTAMPTZ NULL,
  access_count         BIGINT NOT NULL,
  CONSTRAINT ux_ai_memory_record_tenant_ns_key_version UNIQUE (tenant_id, namespace, memory_key, version),
  CONSTRAINT chk_ai_memory_record_confidence CHECK (confidence >= 0 AND confidence <= 1),
  CONSTRAINT chk_ai_memory_record_weight CHECK (weight >= 0),
  CONSTRAINT chk_ai_memory_record_version CHECK (version >= 1),
  CONSTRAINT chk_ai_memory_record_access_count CHECK (access_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_ai_memory_record_tenant_ns_status_updated
  ON ai_memory_record(tenant_id, namespace, status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_memory_record_tenant_ns_key
  ON ai_memory_record(tenant_id, namespace, memory_key);

CREATE INDEX IF NOT EXISTS idx_ai_memory_record_tenant_expires
  ON ai_memory_record(tenant_id, expires_at);

CREATE TABLE IF NOT EXISTS ai_memory_evidence_ref (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id            UUID NOT NULL REFERENCES tenant(id),
  ai_memory_record_id  UUID NOT NULL REFERENCES ai_memory_record(id),
  evidence_type        VARCHAR(24) NOT NULL,
  evidence_ref         VARCHAR(160) NOT NULL,
  source_type          VARCHAR(32) NULL,
  source_id            UUID NULL,
  field_key            VARCHAR(64) NULL,
  confidence           NUMERIC(5,4) NULL,
  created_at           TIMESTAMPTZ NOT NULL,
  CONSTRAINT chk_ai_memory_evidence_confidence CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);

CREATE INDEX IF NOT EXISTS idx_ai_memory_evidence_ref_tenant_record
  ON ai_memory_evidence_ref(tenant_id, ai_memory_record_id);

CREATE TABLE IF NOT EXISTS ai_memory_invalidation_event (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id            UUID NOT NULL REFERENCES tenant(id),
  ai_memory_record_id  UUID NOT NULL REFERENCES ai_memory_record(id),
  previous_status      VARCHAR(16) NOT NULL,
  new_status           VARCHAR(16) NOT NULL,
  reason_code          VARCHAR(32) NOT NULL,
  reason               VARCHAR(280) NULL,
  actor_type           VARCHAR(16) NOT NULL,
  actor_id             UUID NULL,
  created_at           TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ai_memory_invalidation_event_tenant_record_created
  ON ai_memory_invalidation_event(tenant_id, ai_memory_record_id, created_at DESC);

CREATE TABLE IF NOT EXISTS ai_runtime_trace (
  id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id              UUID NOT NULL REFERENCES tenant(id),
  workload_type          VARCHAR(48) NOT NULL,
  model_provider         VARCHAR(48) NULL,
  model_name             VARCHAR(80) NULL,
  prompt_version         VARCHAR(48) NOT NULL,
  schema_version         VARCHAR(48) NULL,
  input_token_estimate   INTEGER NULL,
  output_token_estimate  INTEGER NULL,
  cost_units             NUMERIC(12,4) NULL,
  status                 VARCHAR(16) NOT NULL,
  failure_code           VARCHAR(48) NULL,
  source_type            VARCHAR(32) NULL,
  source_id              UUID NULL,
  created_at             TIMESTAMPTZ NOT NULL,
  CONSTRAINT chk_ai_runtime_trace_tokens CHECK (
    (input_token_estimate IS NULL OR input_token_estimate >= 0)
    AND (output_token_estimate IS NULL OR output_token_estimate >= 0)),
  CONSTRAINT chk_ai_runtime_trace_cost CHECK (cost_units IS NULL OR cost_units >= 0)
);

CREATE INDEX IF NOT EXISTS idx_ai_runtime_trace_tenant_workload_created
  ON ai_runtime_trace(tenant_id, workload_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_runtime_trace_tenant_status_created
  ON ai_runtime_trace(tenant_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_runtime_trace_tenant_source
  ON ai_runtime_trace(tenant_id, source_type, source_id);
