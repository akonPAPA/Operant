-- OP-CAP-06A Messenger Chatbot Integration Layer
-- Bridge verified channel intake (inbound_channel_event) to the controlled bot runtime.
-- Additive and non-destructive: no table renames, no data deletion, no unrelated tables touched.
-- Provider-message replay idempotency is already enforced by the existing
-- uq_inbound_channel_event_external_id unique index, so no new unique constraint is added here.

ALTER TABLE inbound_channel_event ADD COLUMN bot_conversation_id UUID NULL;
ALTER TABLE inbound_channel_event ADD COLUMN bot_message_id UUID NULL;
ALTER TABLE inbound_channel_event ADD COLUMN bot_runtime_status VARCHAR(64) NULL;

-- Support tenant-scoped lookup of which inbound channel events were bridged into a bot conversation.
CREATE INDEX idx_inbound_channel_event_bot_conversation
  ON inbound_channel_event(tenant_id, bot_conversation_id);
