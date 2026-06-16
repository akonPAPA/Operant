-- OP-CAP-22 Order Journey & Fulfillment Visibility Foundation.
-- Additive, tenant-scoped, derived projection layer that shows the operational lifecycle of a
-- commercial transaction (quote/order/validation/reconciliation) plus internal fulfillment signals.
-- These rows are REBUILDABLE from the operational write models (which remain the system of record).
-- This is NOT a WMS/TMS/carrier/GPS/payment platform. No raw carrier/GPS/bank/PSP/card/payment data,
-- no raw document/OCR/prompt text, and no secrets are ever stored here. Mutation happens only through
-- the backend journey service — never AI, bot, frontend, connector, or webhook directly. No external
-- writes are performed.

-- 1) One derived journey per stable source object (tenant-scoped).
CREATE TABLE IF NOT EXISTS order_journey (
  id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id                UUID NOT NULL REFERENCES tenant(id),
  source_type              VARCHAR(32) NOT NULL,
  source_id                UUID NOT NULL,
  customer_account_id      UUID NULL,
  customer_display_name    VARCHAR(255) NULL,
  current_stage            VARCHAR(40) NOT NULL,
  current_status           VARCHAR(120) NOT NULL,
  risk_level               VARCHAR(16) NOT NULL,
  blocked                  BOOLEAN NOT NULL DEFAULT FALSE,
  customer_visible_status  VARCHAR(120) NOT NULL,
  internal_status          VARCHAR(255) NOT NULL,
  last_signal_at           TIMESTAMPTZ NULL,
  created_at               TIMESTAMPTZ NOT NULL,
  updated_at               TIMESTAMPTZ NOT NULL,
  CONSTRAINT ux_order_journey_source UNIQUE (tenant_id, source_type, source_id)
);

CREATE INDEX IF NOT EXISTS idx_order_journey_tenant_stage_updated
  ON order_journey(tenant_id, current_stage, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_journey_tenant_blocked_updated
  ON order_journey(tenant_id, blocked, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_journey_tenant_customer_updated
  ON order_journey(tenant_id, customer_account_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_journey_tenant_updated
  ON order_journey(tenant_id, updated_at DESC);

-- 2) Derived milestone rows (rebuilt on every projection refresh).
CREATE TABLE IF NOT EXISTS order_journey_milestone (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         UUID NOT NULL REFERENCES tenant(id),
  journey_id        UUID NOT NULL,
  milestone_code    VARCHAR(40) NOT NULL,
  milestone_label   VARCHAR(120) NOT NULL,
  milestone_state   VARCHAR(16) NOT NULL,
  evidence_level    VARCHAR(16) NOT NULL,
  occurred_at       TIMESTAMPTZ NULL,
  estimated_at      TIMESTAMPTZ NULL,
  source_type       VARCHAR(32) NULL,
  source_ref        VARCHAR(255) NULL,
  customer_visible  BOOLEAN NOT NULL DEFAULT FALSE,
  sort_order        INTEGER NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL,
  CONSTRAINT ux_order_journey_milestone UNIQUE (tenant_id, journey_id, milestone_code)
);

CREATE INDEX IF NOT EXISTS idx_order_journey_milestone_journey_sort
  ON order_journey_milestone(tenant_id, journey_id, sort_order);

-- 3) Append-only journey events. No raw payloads; bounded message text only.
CREATE TABLE IF NOT EXISTS order_journey_event (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         UUID NOT NULL REFERENCES tenant(id),
  journey_id        UUID NOT NULL,
  event_type        VARCHAR(48) NOT NULL,
  event_status      VARCHAR(48) NULL,
  evidence_level    VARCHAR(16) NOT NULL,
  message           VARCHAR(280) NOT NULL,
  source_type       VARCHAR(32) NULL,
  source_ref        VARCHAR(255) NULL,
  actor_type        VARCHAR(16) NOT NULL,
  actor_id          UUID NULL,
  customer_visible  BOOLEAN NOT NULL DEFAULT FALSE,
  occurred_at       TIMESTAMPTZ NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_order_journey_event_journey_occurred
  ON order_journey_event(tenant_id, journey_id, occurred_at DESC);

-- 4) Bounded fulfillment signals. raw_payload_ref is an out-of-band object reference only — never the
-- raw carrier/GPS/payment payload itself.
CREATE TABLE IF NOT EXISTS fulfillment_signal (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         UUID NOT NULL REFERENCES tenant(id),
  journey_id        UUID NULL,
  source_type       VARCHAR(24) NOT NULL,
  signal_type       VARCHAR(24) NOT NULL,
  signal_status     VARCHAR(48) NULL,
  confidence        NUMERIC(4,3) NULL,
  source_ref        VARCHAR(255) NULL,
  raw_payload_ref   VARCHAR(255) NULL,
  customer_visible  BOOLEAN NOT NULL DEFAULT FALSE,
  received_at       TIMESTAMPTZ NOT NULL,
  processed_at      TIMESTAMPTZ NULL,
  created_at        TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_fulfillment_signal_journey_received
  ON fulfillment_signal(tenant_id, journey_id, received_at DESC);
