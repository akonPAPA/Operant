-- OP-CAP-17E Trust Analytics Read Models.
-- Additive, tenant-scoped CQRS-lite projection layer. These are DERIVED read models rebuilt from the
-- OP-CAP-17A/17B/17C/17D operational write models (which remain the system of record). They exist only
-- to serve fast, bounded, explainable cockpit/analytics queries without heavy per-request joins over the
-- operational tables. Every row carries tenant_id and is tenant-isolated. Rebuildable views carry a
-- unique natural key so projection is idempotent (rebuild updates/replaces, never appends). No raw
-- document text, OCR text, prompt text, bank credentials, account numbers, IBAN, PAN/CVV, or secrets are
-- ever stored here. All reason/explanation/identifier columns are bounded VARCHAR. Mutation happens only
-- through the backend projection service — never AI, bot, frontend, connector, or webhook.

-- 1) Fast operator queue for high/critical trust decisions and pending approval requirements.
CREATE TABLE IF NOT EXISTS trust_review_queue_view (
  id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id                UUID NOT NULL REFERENCES tenant(id),
  trust_risk_decision_id   UUID NOT NULL,
  subject_type             VARCHAR(32) NOT NULL,
  subject_id               UUID NOT NULL,
  counterparty_id          UUID NULL,
  document_trust_run_id    UUID NULL,
  payment_obligation_id    UUID NULL,
  risk_level               VARCHAR(16) NOT NULL,
  risk_score               INTEGER NOT NULL,
  action                   VARCHAR(32) NOT NULL,
  blocking                 BOOLEAN NOT NULL,
  human_review_required    BOOLEAN NOT NULL,
  approval_status          VARCHAR(16) NULL,
  top_reason_code          VARCHAR(48) NULL,
  reason_summary           VARCHAR(280) NULL,
  created_at               TIMESTAMPTZ NOT NULL,
  updated_at               TIMESTAMPTZ NOT NULL,
  last_projected_at        TIMESTAMPTZ NOT NULL,
  CONSTRAINT ux_trust_review_queue_view_decision UNIQUE (tenant_id, trust_risk_decision_id),
  CONSTRAINT chk_trust_review_queue_view_score CHECK (risk_score >= 0 AND risk_score <= 100)
);

CREATE INDEX IF NOT EXISTS idx_trust_review_queue_view_tenant_risk_created
  ON trust_review_queue_view(tenant_id, risk_level, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_trust_review_queue_view_tenant_approval_created
  ON trust_review_queue_view(tenant_id, approval_status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_trust_review_queue_view_tenant_blocking_created
  ON trust_review_queue_view(tenant_id, blocking, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_trust_review_queue_view_tenant_subject
  ON trust_review_queue_view(tenant_id, subject_type, subject_id);

-- 2) Fast profile summary for the customer/counterparty trust page.
CREATE TABLE IF NOT EXISTS counterparty_trust_dashboard_view (
  id                                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id                         UUID NOT NULL REFERENCES tenant(id),
  counterparty_id                   UUID NOT NULL,
  trust_score                       INTEGER NOT NULL,
  trust_tier                        VARCHAR(16) NOT NULL,
  order_count                       BIGINT NOT NULL,
  completed_order_count             BIGINT NOT NULL,
  paid_on_time_count                BIGINT NOT NULL,
  overdue_count                     BIGINT NOT NULL,
  disputed_count                    BIGINT NOT NULL,
  high_risk_document_count          BIGINT NOT NULL,
  critical_risk_document_count      BIGINT NOT NULL,
  high_risk_decision_count          BIGINT NOT NULL,
  critical_risk_decision_count      BIGINT NOT NULL,
  open_payment_obligation_count     BIGINT NOT NULL,
  overdue_payment_obligation_count  BIGINT NOT NULL,
  outstanding_amount                NUMERIC(19,4) NULL,
  primary_currency                  VARCHAR(8) NULL,
  last_order_at                     TIMESTAMPTZ NULL,
  last_payment_at                   TIMESTAMPTZ NULL,
  last_high_risk_at                 TIMESTAMPTZ NULL,
  last_critical_risk_at             TIMESTAMPTZ NULL,
  updated_at                        TIMESTAMPTZ NOT NULL,
  last_projected_at                 TIMESTAMPTZ NOT NULL,
  CONSTRAINT ux_counterparty_trust_dashboard_view UNIQUE (tenant_id, counterparty_id)
);

CREATE INDEX IF NOT EXISTS idx_counterparty_trust_dashboard_view_tenant_tier
  ON counterparty_trust_dashboard_view(tenant_id, trust_tier);

CREATE INDEX IF NOT EXISTS idx_counterparty_trust_dashboard_view_tenant_score
  ON counterparty_trust_dashboard_view(tenant_id, trust_score);

CREATE INDEX IF NOT EXISTS idx_counterparty_trust_dashboard_view_tenant_outstanding
  ON counterparty_trust_dashboard_view(tenant_id, outstanding_amount);

CREATE INDEX IF NOT EXISTS idx_counterparty_trust_dashboard_view_tenant_last_high_risk
  ON counterparty_trust_dashboard_view(tenant_id, last_high_risk_at DESC);

-- 3) Fast finance/trust view of unpaid, partially paid, overdue and high-risk obligations.
CREATE TABLE IF NOT EXISTS outstanding_debt_view (
  id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id                UUID NOT NULL REFERENCES tenant(id),
  payment_obligation_id    UUID NOT NULL,
  counterparty_id          UUID NOT NULL,
  order_id                 UUID NULL,
  invoice_mirror_id        UUID NULL,
  external_reference       VARCHAR(120) NULL,
  amount_total             NUMERIC(19,4) NOT NULL,
  amount_paid              NUMERIC(19,4) NOT NULL,
  amount_remaining         NUMERIC(19,4) NOT NULL,
  currency                 VARCHAR(3) NOT NULL,
  due_date                 DATE NULL,
  status                   VARCHAR(16) NOT NULL,
  risk_level               VARCHAR(16) NOT NULL,
  days_overdue             INTEGER NOT NULL,
  linked_risk_decision_id  UUID NULL,
  top_reason_code          VARCHAR(48) NULL,
  created_at               TIMESTAMPTZ NOT NULL,
  updated_at               TIMESTAMPTZ NOT NULL,
  last_projected_at        TIMESTAMPTZ NOT NULL,
  CONSTRAINT ux_outstanding_debt_view_obligation UNIQUE (tenant_id, payment_obligation_id)
);

CREATE INDEX IF NOT EXISTS idx_outstanding_debt_view_tenant_status_due
  ON outstanding_debt_view(tenant_id, status, due_date);

CREATE INDEX IF NOT EXISTS idx_outstanding_debt_view_tenant_risk_remaining
  ON outstanding_debt_view(tenant_id, risk_level, amount_remaining);

CREATE INDEX IF NOT EXISTS idx_outstanding_debt_view_tenant_counterparty_status
  ON outstanding_debt_view(tenant_id, counterparty_id, status);

CREATE INDEX IF NOT EXISTS idx_outstanding_debt_view_tenant_remaining
  ON outstanding_debt_view(tenant_id, amount_remaining);

-- 4) Aggregated document trust anomaly counts by period (daily period key yyyy-MM-dd).
CREATE TABLE IF NOT EXISTS document_anomaly_trend_view (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id           UUID NOT NULL REFERENCES tenant(id),
  period_key          VARCHAR(32) NOT NULL,
  period_start        TIMESTAMPTZ NOT NULL,
  period_end          TIMESTAMPTZ NOT NULL,
  signal_code         VARCHAR(48) NOT NULL,
  severity            VARCHAR(16) NOT NULL,
  risk_level          VARCHAR(16) NULL,
  counterparty_id     UUID NULL,
  occurrence_count    BIGINT NOT NULL,
  high_count          BIGINT NOT NULL,
  critical_count      BIGINT NOT NULL,
  latest_seen_at      TIMESTAMPTZ NOT NULL,
  last_projected_at   TIMESTAMPTZ NOT NULL,
  CONSTRAINT ux_document_anomaly_trend_view UNIQUE (tenant_id, period_key, signal_code, severity, counterparty_id)
);

CREATE INDEX IF NOT EXISTS idx_document_anomaly_trend_view_tenant_period
  ON document_anomaly_trend_view(tenant_id, period_key);

CREATE INDEX IF NOT EXISTS idx_document_anomaly_trend_view_tenant_signal_period
  ON document_anomaly_trend_view(tenant_id, signal_code, period_key);

CREATE INDEX IF NOT EXISTS idx_document_anomaly_trend_view_tenant_severity_period
  ON document_anomaly_trend_view(tenant_id, severity, period_key);

-- 5) Fast dashboard metrics for risk decisions by period (daily period key yyyy-MM-dd).
CREATE TABLE IF NOT EXISTS trust_risk_distribution_view (
  id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id                UUID NOT NULL REFERENCES tenant(id),
  period_key               VARCHAR(32) NOT NULL,
  period_start             TIMESTAMPTZ NOT NULL,
  period_end               TIMESTAMPTZ NOT NULL,
  low_count                BIGINT NOT NULL,
  medium_count             BIGINT NOT NULL,
  high_count               BIGINT NOT NULL,
  critical_count           BIGINT NOT NULL,
  approval_required_count  BIGINT NOT NULL,
  blocking_count           BIGINT NOT NULL,
  override_count           BIGINT NOT NULL,
  avg_risk_score           NUMERIC(6,2) NOT NULL,
  last_projected_at        TIMESTAMPTZ NOT NULL,
  CONSTRAINT ux_trust_risk_distribution_view UNIQUE (tenant_id, period_key)
);

CREATE INDEX IF NOT EXISTS idx_trust_risk_distribution_view_tenant_period
  ON trust_risk_distribution_view(tenant_id, period_key);
