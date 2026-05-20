CREATE TABLE bot_conversation (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  channel VARCHAR(40) NOT NULL,
  external_chat_id VARCHAR(160) NOT NULL,
  status VARCHAR(40) NOT NULL,
  requires_human_review BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_bot_conversation_tenant_channel_chat UNIQUE (tenant_id, channel, external_chat_id)
);

CREATE TABLE bot_message (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  conversation_id UUID NOT NULL REFERENCES bot_conversation(id),
  channel VARCHAR(40) NOT NULL,
  external_chat_id VARCHAR(160) NOT NULL,
  external_message_id VARCHAR(160) NOT NULL,
  raw_text TEXT NOT NULL,
  detected_intent VARCHAR(60) NOT NULL,
  status VARCHAR(40) NOT NULL,
  requires_human_review BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_bot_message_tenant_channel_chat_message UNIQUE (tenant_id, channel, external_chat_id, external_message_id)
);

CREATE TABLE bot_rfq_request (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  conversation_id UUID NOT NULL REFERENCES bot_conversation(id),
  message_id UUID NOT NULL REFERENCES bot_message(id),
  source VARCHAR(40) NOT NULL,
  raw_text TEXT NOT NULL,
  normalized_request_text TEXT NULL,
  status VARCHAR(40) NOT NULL,
  requires_human_review BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE bot_handoff (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  conversation_id UUID NOT NULL REFERENCES bot_conversation(id),
  message_id UUID NOT NULL REFERENCES bot_message(id),
  channel VARCHAR(40) NOT NULL,
  reason VARCHAR(120) NOT NULL,
  status VARCHAR(40) NOT NULL,
  requires_human_review BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_bot_conversation_tenant_status ON bot_conversation(tenant_id, status, updated_at DESC);
CREATE INDEX idx_bot_message_tenant_conversation ON bot_message(tenant_id, conversation_id, created_at DESC);
CREATE INDEX idx_bot_message_tenant_intent ON bot_message(tenant_id, detected_intent, created_at DESC);
CREATE INDEX idx_bot_rfq_request_tenant_status ON bot_rfq_request(tenant_id, status, created_at DESC);
CREATE INDEX idx_bot_handoff_tenant_status ON bot_handoff(tenant_id, status, created_at DESC);
