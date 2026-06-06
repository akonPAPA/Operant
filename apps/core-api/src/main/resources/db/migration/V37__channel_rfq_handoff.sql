-- OP-CAP-06B controlled Bot Runtime RFQ Handoff.
-- Reviewable internal RFQ/draft request created from a verified channel/bot message.
-- This is NOT a quote/order/inventory/price/customer record: the bot/channel path may only create a
-- PENDING_REVIEW draft request for an operator to review. Additive and non-destructive: no table
-- renames, no dropped columns, no deleted data, no unrelated tables touched, no external-write trigger.

CREATE TABLE IF NOT EXISTS channel_rfq_handoff (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  inbound_channel_event_id UUID NOT NULL REFERENCES inbound_channel_event(id),
  channel_connection_id UUID NOT NULL REFERENCES channel_connection(id),
  source_channel VARCHAR(64) NOT NULL,
  source_external_event_id VARCHAR(255) NULL,
  source_actor_external_id VARCHAR(255) NULL,
  customer_account_id UUID NULL,
  customer_contact_id UUID NULL,
  request_text VARCHAR(4000) NULL,
  detected_intent VARCHAR(64) NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_channel_rfq_handoff_status
    CHECK (status IN ('PENDING_REVIEW', 'IN_REVIEW', 'CONVERTED', 'DISMISSED')),
  -- Idempotency: one handoff per source channel event within a tenant, so duplicate
  -- webhook/message delivery cannot create duplicate RFQ handoffs.
  CONSTRAINT uq_channel_rfq_handoff_tenant_event UNIQUE (tenant_id, inbound_channel_event_id)
);

-- Operator listing by tenant + status, newest first.
CREATE INDEX IF NOT EXISTS idx_channel_rfq_handoff_tenant_status_created
  ON channel_rfq_handoff (tenant_id, status, created_at DESC);

-- Tenant-scoped lookup by source provider event reference.
CREATE INDEX IF NOT EXISTS idx_channel_rfq_handoff_tenant_source_event
  ON channel_rfq_handoff (tenant_id, source_external_event_id);
