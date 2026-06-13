-- OP-CAP-17B Counterparty Trust Profile Foundation.
-- Additive, tenant-scoped accumulation of counterparty (customer account) trust context fed by the
-- OP-CAP-17A document trust layer. Stores deterministic scores, bounded behaviour counters, and
-- explainable signals/snapshots only. No raw document text, account numbers, IBAN, routing numbers,
-- bank credentials, PAN/CVV, or prompt text are ever stored here. Every table is tenant-isolated via
-- tenant_id and FK-scoped to customer_account. Business counters use BIGINT (long).

CREATE TABLE IF NOT EXISTS counterparty_trust_profile (
  id                            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id                     UUID NOT NULL REFERENCES tenant(id),
  customer_account_id           UUID NOT NULL REFERENCES customer_account(id),
  trust_score                   INTEGER NOT NULL DEFAULT 50,
  trust_tier                    VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  document_reliability_score    INTEGER NOT NULL DEFAULT 50,
  payment_reliability_score     INTEGER NOT NULL DEFAULT 50,
  order_pattern_score           INTEGER NOT NULL DEFAULT 50,
  total_document_count          BIGINT NOT NULL DEFAULT 0,
  high_risk_document_count      BIGINT NOT NULL DEFAULT 0,
  critical_risk_document_count  BIGINT NOT NULL DEFAULT 0,
  warning_document_count        BIGINT NOT NULL DEFAULT 0,
  manual_review_count           BIGINT NOT NULL DEFAULT 0,
  approved_override_count       BIGINT NOT NULL DEFAULT 0,
  rejected_document_count       BIGINT NOT NULL DEFAULT 0,
  disputed_count                BIGINT NOT NULL DEFAULT 0,
  completed_order_count         BIGINT NOT NULL DEFAULT 0,
  overdue_payment_count         BIGINT NOT NULL DEFAULT 0,
  bank_account_change_count     BIGINT NOT NULL DEFAULT 0,
  last_document_trust_run_id    UUID NULL,
  last_risk_level               VARCHAR(16) NULL,
  last_trust_signal_at          TIMESTAMPTZ NULL,
  last_order_at                 TIMESTAMPTZ NULL,
  last_payment_at               TIMESTAMPTZ NULL,
  created_at                    TIMESTAMPTZ NOT NULL,
  updated_at                    TIMESTAMPTZ NOT NULL,
  CONSTRAINT chk_counterparty_trust_profile_score CHECK (trust_score BETWEEN 0 AND 100)
);

-- One profile per tenant + counterparty.
CREATE UNIQUE INDEX IF NOT EXISTS ux_counterparty_trust_profile_tenant_account
  ON counterparty_trust_profile(tenant_id, customer_account_id);

CREATE INDEX IF NOT EXISTS idx_counterparty_trust_profile_tenant_tier
  ON counterparty_trust_profile(tenant_id, trust_tier);

CREATE INDEX IF NOT EXISTS idx_counterparty_trust_profile_tenant_updated
  ON counterparty_trust_profile(tenant_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS counterparty_trust_snapshot (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id            UUID NOT NULL REFERENCES tenant(id),
  customer_account_id  UUID NOT NULL REFERENCES customer_account(id),
  profile_id           UUID NOT NULL REFERENCES counterparty_trust_profile(id),
  trust_score          INTEGER NOT NULL,
  trust_tier           VARCHAR(16) NOT NULL,
  risk_level           VARCHAR(16) NULL,
  reason_summary       VARCHAR(280) NULL,
  source_type          VARCHAR(32) NOT NULL,
  source_ref_id        UUID NULL,
  created_at           TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_counterparty_trust_snapshot_tenant_account_created
  ON counterparty_trust_snapshot(tenant_id, customer_account_id, created_at DESC);

-- Idempotency guard: at most one snapshot per (counterparty, source type, source ref).
CREATE INDEX IF NOT EXISTS idx_counterparty_trust_snapshot_tenant_source_ref
  ON counterparty_trust_snapshot(tenant_id, customer_account_id, source_type, source_ref_id);

CREATE TABLE IF NOT EXISTS counterparty_trust_signal (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id            UUID NOT NULL REFERENCES tenant(id),
  customer_account_id  UUID NOT NULL REFERENCES customer_account(id),
  signal_code          VARCHAR(48) NOT NULL,
  severity             VARCHAR(16) NOT NULL,
  confidence           NUMERIC(4,3) NULL,
  weight               INTEGER NOT NULL DEFAULT 0,
  source_type          VARCHAR(32) NOT NULL,
  source_ref_id        UUID NULL,
  explanation          VARCHAR(280) NULL,
  created_at           TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_counterparty_trust_signal_tenant_account_created
  ON counterparty_trust_signal(tenant_id, customer_account_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_counterparty_trust_signal_tenant_code_created
  ON counterparty_trust_signal(tenant_id, signal_code, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_counterparty_trust_signal_tenant_severity_created
  ON counterparty_trust_signal(tenant_id, severity, created_at DESC);
