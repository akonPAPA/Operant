CREATE TABLE IF NOT EXISTS bot_connection (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  channel_type VARCHAR(40) NOT NULL,
  bot_external_id VARCHAR(160) NULL,
  telegram_bot_id VARCHAR(160) NULL,
  enabled BOOLEAN NOT NULL DEFAULT true,
  allowed_flows JSONB NOT NULL DEFAULT '[]'::jsonb,
  default_handoff_queue VARCHAR(80) NOT NULL DEFAULT 'BOT_REVIEW',
  last_seen_at TIMESTAMPTZ NULL,
  metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_bot_connection_tenant_channel
  ON bot_connection(tenant_id, channel_type, updated_at DESC);

CREATE TABLE IF NOT EXISTS bot_flow_config (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  bot_connection_id UUID NOT NULL REFERENCES bot_connection(id),
  flow_name VARCHAR(80) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_bot_flow_config UNIQUE (tenant_id, bot_connection_id, flow_name)
);

CREATE TABLE IF NOT EXISTS bot_rate_limit_event (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  conversation_key VARCHAR(220) NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_bot_rate_limit_tenant_conversation
  ON bot_rate_limit_event(tenant_id, conversation_key, created_at DESC);

ALTER TABLE bot_handoff
  ADD COLUMN IF NOT EXISTS channel_message_id UUID NULL REFERENCES channel_message(id),
  ADD COLUMN IF NOT EXISTS customer_account_id UUID NULL REFERENCES customer_account(id),
  ADD COLUMN IF NOT EXISTS detected_intent VARCHAR(80) NULL,
  ADD COLUMN IF NOT EXISTS assigned_queue VARCHAR(80) NULL,
  ADD COLUMN IF NOT EXISTS extracted_hints_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  ADD COLUMN IF NOT EXISTS risk_flags_json JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX IF NOT EXISTS idx_bot_handoff_tenant_queue_status
  ON bot_handoff(tenant_id, assigned_queue, status, created_at DESC);
