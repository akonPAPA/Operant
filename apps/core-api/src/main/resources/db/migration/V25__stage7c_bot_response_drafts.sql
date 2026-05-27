CREATE TABLE IF NOT EXISTS bot_response_draft (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  conversation_id UUID NOT NULL REFERENCES bot_conversation(id),
  source_message_id UUID NOT NULL REFERENCES bot_message(id),
  channel VARCHAR(40) NOT NULL,
  response_type VARCHAR(80) NOT NULL,
  policy_decision VARCHAR(80) NOT NULL,
  status VARCHAR(40) NOT NULL,
  response_text TEXT NOT NULL,
  requires_operator_review BOOLEAN NOT NULL DEFAULT true,
  reviewed_by UUID NULL,
  reviewed_at TIMESTAMPTZ NULL,
  stub_sent_at TIMESTAMPTZ NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_bot_response_draft_tenant_conversation ON bot_response_draft(tenant_id, conversation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_bot_response_draft_tenant_status ON bot_response_draft(tenant_id, status, created_at DESC);
