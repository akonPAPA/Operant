-- OP-CAP-17A Document Trust Signal Foundation.
-- Additive, tenant-scoped foundation for deterministic Transaction Trust Intelligence.
-- These tables store only fingerprints (hashes), bounded risk decisions, and explainable signal
-- codes with bounded evidence metadata. No raw document text, large OCR text, extracted values,
-- counterparty identity values, or full document payloads are persisted here. Every table is
-- tenant-isolated via tenant_id. Counters/sizes use BIGINT/INTEGER; money is handled as BigDecimal
-- at the service layer (not stored here).

CREATE TABLE IF NOT EXISTS document_fingerprint (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id           UUID NOT NULL REFERENCES tenant(id),
  source_document_id  UUID NOT NULL,
  content_sha256      VARCHAR(64) NOT NULL,
  content_byte_size   BIGINT NULL,
  created_at          TIMESTAMPTZ NOT NULL
);

-- Tenant-scoped duplicate-content detection. Hash collisions never match across tenants.
CREATE INDEX IF NOT EXISTS idx_document_fingerprint_tenant_hash
  ON document_fingerprint(tenant_id, content_sha256);

CREATE TABLE IF NOT EXISTS document_trust_run (
  id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id              UUID NOT NULL REFERENCES tenant(id),
  source_document_id     UUID NOT NULL,
  validation_run_id      UUID NULL,
  fingerprint_id         UUID NULL,
  content_sha256         VARCHAR(64) NOT NULL,
  idempotency_key        VARCHAR(120) NULL,
  risk_level             VARCHAR(16) NOT NULL,
  risk_score             INTEGER NOT NULL DEFAULT 0,
  decision_state         VARCHAR(32) NOT NULL,
  requires_human_review  BOOLEAN NOT NULL DEFAULT FALSE,
  blocks_automation      BOOLEAN NOT NULL DEFAULT FALSE,
  duplicate_detected     BOOLEAN NOT NULL DEFAULT FALSE,
  signal_count           INTEGER NOT NULL DEFAULT 0,
  active                 BOOLEAN NOT NULL DEFAULT TRUE,
  file_size_bytes        BIGINT NULL,
  page_count             INTEGER NULL,
  created_at             TIMESTAMPTZ NOT NULL,
  CONSTRAINT chk_document_trust_run_risk_score CHECK (risk_score BETWEEN 0 AND 100)
);

CREATE INDEX IF NOT EXISTS idx_document_trust_run_tenant_source
  ON document_trust_run(tenant_id, source_document_id);

CREATE INDEX IF NOT EXISTS idx_document_trust_run_tenant_risk_created
  ON document_trust_run(tenant_id, risk_level, created_at);

-- Idempotency backstops: at most one active run per explicit idempotency key, and at most one active
-- run per (source document + identical content) when no explicit key is supplied.
CREATE UNIQUE INDEX IF NOT EXISTS ux_document_trust_run_idem_key
  ON document_trust_run(tenant_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL AND active;

CREATE UNIQUE INDEX IF NOT EXISTS ux_document_trust_run_idem_natural
  ON document_trust_run(tenant_id, source_document_id, content_sha256)
  WHERE idempotency_key IS NULL AND active;

CREATE TABLE IF NOT EXISTS document_trust_signal (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     UUID NOT NULL REFERENCES tenant(id),
  trust_run_id  UUID NOT NULL REFERENCES document_trust_run(id),
  signal_code   VARCHAR(48) NOT NULL,
  severity      VARCHAR(16) NOT NULL,
  field_key     VARCHAR(64) NULL,
  page_number   INTEGER NULL,
  evidence_ref  VARCHAR(120) NULL,
  explanation   VARCHAR(280) NULL,
  created_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_document_trust_signal_tenant_run
  ON document_trust_signal(tenant_id, trust_run_id);

CREATE INDEX IF NOT EXISTS idx_document_trust_signal_tenant_code_created
  ON document_trust_signal(tenant_id, signal_code, created_at);
