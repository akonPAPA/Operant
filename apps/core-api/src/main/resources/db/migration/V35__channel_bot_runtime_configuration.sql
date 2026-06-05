-- OP-CAP-06B Controlled Bot Runtime Configuration / Bot Builder Lite (in-app).
-- Tenant-scoped, per-channel-connection configuration that constrains the existing controlled
-- bot runtime. Additive and non-destructive: no table renames, no dropped columns, no deleted
-- data, no unrelated tables touched. Stores deterministic policy only -- never tokens, secrets,
-- secret references, provider credentials, raw payloads, or executable rules.

CREATE TABLE IF NOT EXISTS channel_bot_runtime_configuration (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  channel_connection_id UUID NOT NULL REFERENCES channel_connection(id),
  enabled BOOLEAN NOT NULL DEFAULT true,
  greeting_enabled BOOLEAN NOT NULL DEFAULT true,
  availability_check_enabled BOOLEAN NOT NULL DEFAULT true,
  price_check_mode VARCHAR(40) NOT NULL DEFAULT 'OPERATOR_REVIEW_ONLY',
  rfq_capture_mode VARCHAR(40) NOT NULL DEFAULT 'OPERATOR_REVIEW_ONLY',
  substitute_suggestion_mode VARCHAR(40) NOT NULL DEFAULT 'OPERATOR_REVIEW_ONLY',
  order_status_mode VARCHAR(40) NOT NULL DEFAULT 'DISABLED',
  unknown_customer_mode VARCHAR(40) NOT NULL DEFAULT 'HANDOFF',
  human_handoff_enabled BOOLEAN NOT NULL DEFAULT true,
  handoff_queue_key VARCHAR(80) NOT NULL DEFAULT 'BOT_REVIEW',
  inventory_freshness_max_minutes INTEGER NOT NULL DEFAULT 1440,
  inventory_freshness_policy VARCHAR(40) NOT NULL DEFAULT 'WARN_AND_HANDOFF',
  price_visibility_policy VARCHAR(40) NOT NULL DEFAULT 'IDENTIFIED_CUSTOMER_ONLY',
  safe_greeting_template VARCHAR(500) NOT NULL DEFAULT 'Hello. Send a part number, quantity, or RFQ and we will route it through operator-controlled OrderPilot workflows.',
  safe_fallback_template VARCHAR(500) NOT NULL DEFAULT 'I cannot handle that automatically. I routed it to a human operator.',
  handoff_template VARCHAR(500) NOT NULL DEFAULT 'I routed this conversation to a human operator who will follow up.',
  revision INTEGER NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_channel_bot_runtime_configuration UNIQUE (tenant_id, channel_connection_id)
);

CREATE INDEX IF NOT EXISTS idx_channel_bot_runtime_config_tenant_connection
  ON channel_bot_runtime_configuration(tenant_id, channel_connection_id);

CREATE INDEX IF NOT EXISTS idx_channel_bot_runtime_config_tenant_enabled
  ON channel_bot_runtime_configuration(tenant_id, enabled);

CREATE INDEX IF NOT EXISTS idx_channel_bot_runtime_config_tenant_updated
  ON channel_bot_runtime_configuration(tenant_id, updated_at DESC);
