CREATE TABLE channel_identity (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  channel_type VARCHAR(40) NOT NULL,
  external_sender_id VARCHAR(160) NOT NULL,
  external_conversation_id VARCHAR(160) NULL,
  sender_phone VARCHAR(80) NULL,
  sender_display_name VARCHAR(160) NULL,
  customer_account_id UUID NULL REFERENCES customer_account(id),
  customer_contact_id UUID NULL,
  identity_status VARCHAR(40) NOT NULL DEFAULT 'UNLINKED',
  match_confidence NUMERIC(5,4) NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  linked_at TIMESTAMPTZ NULL,
  linked_by_user_id UUID NULL,
  notes TEXT NULL,
  CONSTRAINT ck_channel_identity_status CHECK (identity_status IN ('UNLINKED', 'SUGGESTED_MATCH', 'LINKED', 'BLOCKED', 'NEEDS_REVIEW'))
);

CREATE UNIQUE INDEX uq_channel_identity_active_sender
  ON channel_identity(tenant_id, channel_type, external_sender_id);

CREATE INDEX idx_channel_identity_tenant_status
  ON channel_identity(tenant_id, identity_status, updated_at DESC);

ALTER TABLE channel_message
  ADD COLUMN channel_identity_id UUID NULL REFERENCES channel_identity(id),
  ADD COLUMN customer_contact_id UUID NULL,
  ADD COLUMN signature_verification_mode VARCHAR(60) NULL;

CREATE INDEX idx_channel_message_identity
  ON channel_message(tenant_id, channel_identity_id, received_at DESC);
