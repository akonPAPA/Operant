-- OP-CAP-17D Trust Risk Decision Engine.
-- Additive, tenant-scoped deterministic risk decision layer that combines OP-CAP-17A document trust,
-- OP-CAP-17B counterparty trust, OP-CAP-17C payment obligation state, and explicit tenant policy into
-- a single explainable decision with normalized contribution rows. This is NOT a legal fraud verdict
-- and never claims a document is fake. No raw document text, OCR text, prompt text, bank credentials,
-- account numbers, IBAN, PAN/CVV, or secrets are ever stored here. All reason/explanation columns are
-- bounded VARCHAR. Every table is tenant-isolated via tenant_id. Mutation happens only through the
-- backend command service — never AI, bot, frontend, connector, or webhook.

CREATE TABLE IF NOT EXISTS trust_risk_decision (
  id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id               UUID NOT NULL REFERENCES tenant(id),
  subject_type            VARCHAR(32) NOT NULL,
  subject_id              UUID NOT NULL,
  document_trust_run_id   UUID NULL,
  counterparty_id         UUID NULL,
  payment_obligation_id   UUID NULL,
  validation_run_id       UUID NULL,
  idempotency_key         VARCHAR(120) NULL,
  risk_level              VARCHAR(16) NOT NULL,
  risk_score              INTEGER NOT NULL,
  action                  VARCHAR(32) NOT NULL,
  human_review_required   BOOLEAN NOT NULL,
  blocking                BOOLEAN NOT NULL,
  signal_count            INTEGER NOT NULL,
  reason_summary          VARCHAR(280) NULL,
  status                  VARCHAR(16) NOT NULL,
  created_by              UUID NULL,
  correlation_id          VARCHAR(120) NULL,
  created_at              TIMESTAMPTZ NOT NULL,
  updated_at              TIMESTAMPTZ NOT NULL,
  CONSTRAINT chk_trust_risk_decision_score CHECK (risk_score >= 0 AND risk_score <= 100)
);

CREATE INDEX IF NOT EXISTS idx_trust_risk_decision_tenant_subject_created
  ON trust_risk_decision(tenant_id, subject_type, subject_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_trust_risk_decision_tenant_risk_created
  ON trust_risk_decision(tenant_id, risk_level, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_trust_risk_decision_tenant_status_created
  ON trust_risk_decision(tenant_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS trust_risk_signal_contribution (
  id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id                 UUID NOT NULL REFERENCES tenant(id),
  trust_risk_decision_id    UUID NOT NULL REFERENCES trust_risk_decision(id),
  source_type               VARCHAR(24) NOT NULL,
  source_id                 UUID NULL,
  signal_code               VARCHAR(48) NOT NULL,
  severity                  VARCHAR(16) NOT NULL,
  confidence                NUMERIC(5,4) NULL,
  weight                    INTEGER NOT NULL,
  contribution_score        INTEGER NOT NULL,
  forced_level              VARCHAR(16) NULL,
  explanation               VARCHAR(280) NULL,
  evidence_ref              VARCHAR(120) NULL,
  created_at                TIMESTAMPTZ NOT NULL,
  CONSTRAINT chk_trust_risk_contribution_confidence CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);

CREATE INDEX IF NOT EXISTS idx_trust_risk_contribution_tenant_decision
  ON trust_risk_signal_contribution(tenant_id, trust_risk_decision_id);

CREATE TABLE IF NOT EXISTS trust_approval_requirement (
  id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id                 UUID NOT NULL REFERENCES tenant(id),
  trust_risk_decision_id    UUID NOT NULL REFERENCES trust_risk_decision(id),
  required_action           VARCHAR(32) NOT NULL,
  required_permission_code  VARCHAR(48) NULL,
  required_role_code        VARCHAR(48) NULL,
  reason_code               VARCHAR(48) NOT NULL,
  status                    VARCHAR(16) NOT NULL,
  created_at                TIMESTAMPTZ NOT NULL,
  satisfied_at              TIMESTAMPTZ NULL,
  satisfied_by              UUID NULL
);

CREATE INDEX IF NOT EXISTS idx_trust_approval_requirement_tenant_status_created
  ON trust_approval_requirement(tenant_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_trust_approval_requirement_tenant_decision
  ON trust_approval_requirement(tenant_id, trust_risk_decision_id);

CREATE TABLE IF NOT EXISTS trust_decision_override (
  id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id                 UUID NOT NULL REFERENCES tenant(id),
  trust_risk_decision_id    UUID NOT NULL REFERENCES trust_risk_decision(id),
  previous_risk_level       VARCHAR(16) NOT NULL,
  new_risk_level            VARCHAR(16) NOT NULL,
  previous_action           VARCHAR(32) NOT NULL,
  new_action                VARCHAR(32) NOT NULL,
  reason                    VARCHAR(280) NOT NULL,
  overridden_by             UUID NULL,
  overridden_at             TIMESTAMPTZ NOT NULL,
  audit_event_id            UUID NULL
);

CREATE INDEX IF NOT EXISTS idx_trust_decision_override_tenant_decision_overridden
  ON trust_decision_override(tenant_id, trust_risk_decision_id, overridden_at DESC);
