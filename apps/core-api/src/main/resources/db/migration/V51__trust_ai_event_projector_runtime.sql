-- OP-CAP-18 Trust/AI Event Projector Runtime + Operator Correction Learning Loop.
-- Additive, tenant-scoped internal event/projector runtime that connects the OP-CAP-17A–17F deterministic
-- trust/payment/document/risk/analytics layers to the advisory AI memory layer through a controlled,
-- idempotent learning loop. Operational write models remain the source of truth; projectors only DERIVE
-- advisory, low-authority AI memory (via the OP-CAP-17F governance service) and never create/approve
-- orders, quotes, payments, trust decisions, or external/ERP writes. Every row is tenant-isolated via
-- tenant_id. All text columns are bounded VARCHAR (no unbounded TEXT). No raw documents, OCR text,
-- prompts, customer messages, secrets, card data, or bank credentials are stored — only sanitized,
-- bounded summaries and SHA-256 hashes. Dead-lettering is modelled with the event status DEAD_LETTERED
-- plus the checkpoint failure columns (no separate dead_letter table is required for this stage).

CREATE TABLE IF NOT EXISTS trust_ai_domain_event (
  id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id          UUID NOT NULL REFERENCES tenant(id),
  event_type         VARCHAR(48) NOT NULL,
  source_type        VARCHAR(32) NOT NULL,
  source_id          UUID NULL,
  subject_type       VARCHAR(32) NULL,
  subject_id         UUID NULL,
  idempotency_key    VARCHAR(160) NOT NULL,
  status             VARCHAR(16) NOT NULL,
  payload_version    INTEGER NOT NULL,
  payload_summary    VARCHAR(512) NULL,
  occurred_at        TIMESTAMPTZ NOT NULL,
  created_at         TIMESTAMPTZ NOT NULL,
  processed_at       TIMESTAMPTZ NULL,
  failed_at          TIMESTAMPTZ NULL,
  failure_code       VARCHAR(48) NULL,
  failure_message    VARCHAR(512) NULL,
  retry_count        INTEGER NOT NULL,
  next_retry_at      TIMESTAMPTZ NULL,
  CONSTRAINT ux_trust_ai_domain_event_tenant_idem UNIQUE (tenant_id, idempotency_key),
  CONSTRAINT chk_trust_ai_domain_event_retry CHECK (retry_count >= 0),
  CONSTRAINT chk_trust_ai_domain_event_payload_version CHECK (payload_version >= 1)
);

CREATE INDEX IF NOT EXISTS idx_trust_ai_domain_event_tenant_status_occurred
  ON trust_ai_domain_event(tenant_id, status, occurred_at);

CREATE INDEX IF NOT EXISTS idx_trust_ai_domain_event_tenant_type_occurred
  ON trust_ai_domain_event(tenant_id, event_type, occurred_at);

CREATE INDEX IF NOT EXISTS idx_trust_ai_domain_event_tenant_source
  ON trust_ai_domain_event(tenant_id, source_type, source_id);

CREATE INDEX IF NOT EXISTS idx_trust_ai_domain_event_tenant_next_retry
  ON trust_ai_domain_event(tenant_id, next_retry_at);

CREATE TABLE IF NOT EXISTS trust_ai_projection_checkpoint (
  id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id               UUID NOT NULL REFERENCES tenant(id),
  projector_name          VARCHAR(80) NOT NULL,
  event_id                UUID NOT NULL REFERENCES trust_ai_domain_event(id),
  event_type              VARCHAR(48) NOT NULL,
  source_type             VARCHAR(32) NOT NULL,
  source_id               UUID NULL,
  status                  VARCHAR(16) NOT NULL,
  projected_record_type   VARCHAR(48) NULL,
  projected_record_id     UUID NULL,
  idempotency_key         VARCHAR(160) NOT NULL,
  started_at              TIMESTAMPTZ NOT NULL,
  completed_at            TIMESTAMPTZ NULL,
  failed_at               TIMESTAMPTZ NULL,
  failure_code            VARCHAR(48) NULL,
  failure_message         VARCHAR(512) NULL,
  attempt_count           INTEGER NOT NULL,
  created_at              TIMESTAMPTZ NOT NULL,
  updated_at              TIMESTAMPTZ NOT NULL,
  CONSTRAINT ux_trust_ai_checkpoint_tenant_projector_event UNIQUE (tenant_id, projector_name, event_id),
  CONSTRAINT ux_trust_ai_checkpoint_tenant_projector_idem UNIQUE (tenant_id, projector_name, idempotency_key),
  CONSTRAINT chk_trust_ai_checkpoint_attempt CHECK (attempt_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_trust_ai_checkpoint_tenant_projector_status
  ON trust_ai_projection_checkpoint(tenant_id, projector_name, status);

CREATE INDEX IF NOT EXISTS idx_trust_ai_checkpoint_tenant_event_type_status
  ON trust_ai_projection_checkpoint(tenant_id, event_type, status);

CREATE TABLE IF NOT EXISTS operator_correction_learning_record (
  id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id                  UUID NOT NULL REFERENCES tenant(id),
  correction_type            VARCHAR(48) NOT NULL,
  source_type                VARCHAR(32) NOT NULL,
  source_id                  UUID NULL,
  target_type                VARCHAR(48) NOT NULL,
  target_id                  UUID NULL,
  field_key                  VARCHAR(64) NULL,
  previous_value_hash        VARCHAR(64) NULL,
  corrected_value_hash       VARCHAR(64) NULL,
  normalized_correction      VARCHAR(256) NULL,
  correction_summary         VARCHAR(512) NOT NULL,
  confidence                 NUMERIC(5,4) NOT NULL,
  status                     VARCHAR(24) NOT NULL,
  learning_eligible          BOOLEAN NOT NULL,
  linked_ai_memory_record_id UUID NULL,
  created_by                 UUID NULL,
  created_at                 TIMESTAMPTZ NOT NULL,
  reviewed_at                TIMESTAMPTZ NULL,
  rejected_at                TIMESTAMPTZ NULL,
  rejection_reason           VARCHAR(280) NULL,
  CONSTRAINT chk_operator_correction_confidence CHECK (confidence >= 0 AND confidence <= 1)
);

CREATE INDEX IF NOT EXISTS idx_operator_correction_tenant_status_created
  ON operator_correction_learning_record(tenant_id, status, created_at);

CREATE INDEX IF NOT EXISTS idx_operator_correction_tenant_type_status
  ON operator_correction_learning_record(tenant_id, correction_type, status);

CREATE INDEX IF NOT EXISTS idx_operator_correction_tenant_source
  ON operator_correction_learning_record(tenant_id, source_type, source_id);

CREATE INDEX IF NOT EXISTS idx_operator_correction_tenant_linked_memory
  ON operator_correction_learning_record(tenant_id, linked_ai_memory_record_id);
