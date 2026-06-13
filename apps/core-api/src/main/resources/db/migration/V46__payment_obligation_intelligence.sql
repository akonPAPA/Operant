-- OP-CAP-17C Payment Obligation Intelligence Foundation.
-- Additive, tenant-scoped internal payment obligation model + deterministic state engine. This is NOT
-- a payment processor, PSP, or bank integration. No raw bank credentials, IBAN, routing numbers,
-- account numbers, PAN/CVV, card numbers, NFC payloads, raw bank statement payloads, raw document text,
-- or prompt text are ever stored here. Amounts are NUMERIC(19,4) (never floating point); business
-- counters live on the OP-CAP-17B counterparty trust profile as BIGINT. Every table is tenant-isolated
-- via tenant_id and FK-scoped to customer_account. The event table is append-only evidence.

CREATE TABLE IF NOT EXISTS payment_obligation (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id            UUID NOT NULL REFERENCES tenant(id),
  customer_account_id  UUID NOT NULL REFERENCES customer_account(id),
  source_type          VARCHAR(24) NOT NULL,
  source_ref_id        UUID NULL,
  external_reference   VARCHAR(120) NULL,
  obligation_number    VARCHAR(80) NULL,
  amount_total         NUMERIC(19,4) NOT NULL,
  amount_paid          NUMERIC(19,4) NOT NULL DEFAULT 0,
  amount_remaining     NUMERIC(19,4) NOT NULL,
  currency             CHAR(3) NOT NULL,
  due_date             DATE NULL,
  issued_at            TIMESTAMPTZ NULL,
  status               VARCHAR(16) NOT NULL,
  risk_level           VARCHAR(16) NOT NULL,
  last_payment_at      TIMESTAMPTZ NULL,
  disputed_at          TIMESTAMPTZ NULL,
  closed_at            TIMESTAMPTZ NULL,
  created_at           TIMESTAMPTZ NOT NULL,
  updated_at           TIMESTAMPTZ NOT NULL,
  CONSTRAINT chk_payment_obligation_amount_total CHECK (amount_total >= 0),
  CONSTRAINT chk_payment_obligation_amount_paid CHECK (amount_paid >= 0),
  CONSTRAINT chk_payment_obligation_amount_remaining CHECK (amount_remaining >= 0)
);

-- Idempotency guard: at most one obligation per (tenant, source type, source ref) when a ref is present.
CREATE UNIQUE INDEX IF NOT EXISTS ux_payment_obligation_tenant_source_ref
  ON payment_obligation(tenant_id, source_type, source_ref_id)
  WHERE source_ref_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_payment_obligation_tenant_account_status
  ON payment_obligation(tenant_id, customer_account_id, status);

CREATE INDEX IF NOT EXISTS idx_payment_obligation_tenant_account_due
  ON payment_obligation(tenant_id, customer_account_id, due_date);

CREATE INDEX IF NOT EXISTS idx_payment_obligation_tenant_risk_updated
  ON payment_obligation(tenant_id, risk_level, updated_at DESC);

CREATE TABLE IF NOT EXISTS payment_allocation (
  id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id              UUID NOT NULL REFERENCES tenant(id),
  payment_obligation_id  UUID NOT NULL REFERENCES payment_obligation(id),
  customer_account_id    UUID NOT NULL REFERENCES customer_account(id),
  source_type            VARCHAR(24) NOT NULL,
  source_ref_id          UUID NULL,
  allocated_amount       NUMERIC(19,4) NOT NULL,
  currency               CHAR(3) NOT NULL,
  allocation_status      VARCHAR(16) NOT NULL,
  allocated_at           TIMESTAMPTZ NOT NULL,
  reversed_at            TIMESTAMPTZ NULL,
  reason_code            VARCHAR(80) NULL,
  created_at             TIMESTAMPTZ NOT NULL,
  CONSTRAINT chk_payment_allocation_amount CHECK (allocated_amount > 0)
);

CREATE INDEX IF NOT EXISTS idx_payment_allocation_tenant_obligation
  ON payment_allocation(tenant_id, payment_obligation_id);

CREATE INDEX IF NOT EXISTS idx_payment_allocation_tenant_account_allocated
  ON payment_allocation(tenant_id, customer_account_id, allocated_at DESC);

CREATE TABLE IF NOT EXISTS payment_obligation_event (
  id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id                  UUID NOT NULL REFERENCES tenant(id),
  payment_obligation_id      UUID NOT NULL REFERENCES payment_obligation(id),
  customer_account_id        UUID NOT NULL REFERENCES customer_account(id),
  event_type                 VARCHAR(32) NOT NULL,
  previous_status            VARCHAR(16) NULL,
  new_status                 VARCHAR(16) NOT NULL,
  new_risk_level             VARCHAR(16) NOT NULL,
  previous_amount_paid       NUMERIC(19,4) NULL,
  new_amount_paid            NUMERIC(19,4) NOT NULL,
  previous_amount_remaining  NUMERIC(19,4) NULL,
  new_amount_remaining       NUMERIC(19,4) NOT NULL,
  reason_summary             VARCHAR(280) NULL,
  source_type                VARCHAR(24) NOT NULL,
  source_ref_id              UUID NULL,
  created_at                 TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_payment_obligation_event_tenant_obligation_created
  ON payment_obligation_event(tenant_id, payment_obligation_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_payment_obligation_event_tenant_account_created
  ON payment_obligation_event(tenant_id, customer_account_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_payment_obligation_event_tenant_type_created
  ON payment_obligation_event(tenant_id, event_type, created_at DESC);
