CREATE TABLE inbound_event_ledger (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  source VARCHAR(40) NOT NULL,
  external_event_id VARCHAR(255) NULL,
  event_type VARCHAR(80) NOT NULL,
  fingerprint_sha256 VARCHAR(64) NULL,
  status VARCHAR(40) NOT NULL,
  raw_payload_storage_key VARCHAR(500) NULL,
  received_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE channel_message
  ADD COLUMN raw_payload_storage_key VARCHAR(500) NULL,
  ADD COLUMN normalized_text TEXT NULL;

ALTER TABLE webhook_event
  ADD COLUMN raw_payload_storage_key VARCHAR(500) NULL,
  ADD COLUMN fingerprint_sha256 VARCHAR(64) NULL,
  ADD COLUMN event_type VARCHAR(80) NULL;

CREATE INDEX idx_inbound_event_ledger_tenant_source_received
  ON inbound_event_ledger(tenant_id, source, received_at DESC);

CREATE INDEX idx_inbound_event_ledger_tenant_external
  ON inbound_event_ledger(tenant_id, source, external_event_id);

CREATE INDEX idx_inbound_event_ledger_tenant_fingerprint
  ON inbound_event_ledger(tenant_id, fingerprint_sha256);

CREATE INDEX idx_channel_message_tenant_raw_payload_key
  ON channel_message(tenant_id, raw_payload_storage_key);

CREATE INDEX idx_webhook_event_tenant_fingerprint
  ON webhook_event(tenant_id, fingerprint_sha256);
