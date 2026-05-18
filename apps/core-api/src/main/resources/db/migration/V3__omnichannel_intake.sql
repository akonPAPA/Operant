CREATE TABLE object_storage_record (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  storage_provider VARCHAR(40) NOT NULL,
  bucket_name VARCHAR(160) NULL,
  object_key VARCHAR(500) NOT NULL,
  original_filename VARCHAR(255) NULL,
  content_type VARCHAR(160) NULL,
  file_size_bytes BIGINT NULL,
  sha256_fingerprint VARCHAR(64) NULL,
  status VARCHAR(40) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE inbound_document (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  source_channel VARCHAR(40) NOT NULL,
  document_type VARCHAR(40) NOT NULL,
  status VARCHAR(40) NOT NULL,
  original_filename VARCHAR(255) NULL,
  content_type VARCHAR(160) NULL,
  file_size_bytes BIGINT NULL,
  object_storage_key VARCHAR(500) NULL,
  sha256_fingerprint VARCHAR(64) NULL,
  received_from VARCHAR(320) NULL,
  subject VARCHAR(500) NULL,
  raw_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  received_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE channel_message (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  channel VARCHAR(40) NOT NULL,
  external_message_id VARCHAR(255) NULL,
  conversation_id VARCHAR(255) NULL,
  sender_handle VARCHAR(255) NULL,
  sender_display_name VARCHAR(255) NULL,
  customer_account_id UUID NULL REFERENCES customer_account(id),
  direction VARCHAR(40) NOT NULL,
  message_type VARCHAR(40) NOT NULL,
  text_content TEXT NULL,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  status VARCHAR(40) NOT NULL,
  received_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE inbound_attachment (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  channel_message_id UUID NULL REFERENCES channel_message(id),
  inbound_document_id UUID NULL REFERENCES inbound_document(id),
  original_filename VARCHAR(255) NULL,
  content_type VARCHAR(160) NULL,
  file_size_bytes BIGINT NULL,
  object_storage_key VARCHAR(500) NULL,
  sha256_fingerprint VARCHAR(64) NULL,
  status VARCHAR(40) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE processing_job (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  job_type VARCHAR(60) NOT NULL,
  target_type VARCHAR(60) NOT NULL,
  target_id UUID NOT NULL,
  status VARCHAR(40) NOT NULL,
  priority INTEGER NOT NULL DEFAULT 100,
  attempts INTEGER NOT NULL DEFAULT 0,
  max_attempts INTEGER NOT NULL DEFAULT 3,
  last_error TEXT NULL,
  queued_at TIMESTAMPTZ NOT NULL,
  started_at TIMESTAMPTZ NULL,
  finished_at TIMESTAMPTZ NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE webhook_event (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NULL REFERENCES tenant(id),
  provider VARCHAR(40) NOT NULL,
  external_event_id VARCHAR(255) NULL,
  signature_verified BOOLEAN NOT NULL DEFAULT false,
  replay_detected BOOLEAN NOT NULL DEFAULT false,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  headers JSONB NULL,
  status VARCHAR(40) NOT NULL,
  received_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_inbound_document_tenant_status_received ON inbound_document(tenant_id, status, received_at DESC);
CREATE INDEX idx_inbound_document_tenant_channel_received ON inbound_document(tenant_id, source_channel, received_at DESC);
CREATE INDEX idx_inbound_document_tenant_fingerprint ON inbound_document(tenant_id, sha256_fingerprint);
CREATE INDEX idx_channel_message_tenant_channel_received ON channel_message(tenant_id, channel, received_at DESC);
CREATE INDEX idx_channel_message_tenant_status_received ON channel_message(tenant_id, status, received_at DESC);
CREATE INDEX idx_channel_message_tenant_conversation_received ON channel_message(tenant_id, conversation_id, received_at);
CREATE INDEX idx_channel_message_tenant_external_message ON channel_message(tenant_id, external_message_id);
CREATE INDEX idx_inbound_attachment_tenant_fingerprint ON inbound_attachment(tenant_id, sha256_fingerprint);
CREATE INDEX idx_inbound_attachment_tenant_message ON inbound_attachment(tenant_id, channel_message_id);
CREATE INDEX idx_inbound_attachment_tenant_document ON inbound_attachment(tenant_id, inbound_document_id);
CREATE INDEX idx_processing_job_tenant_status_queued ON processing_job(tenant_id, status, queued_at);
CREATE INDEX idx_processing_job_tenant_type_status ON processing_job(tenant_id, job_type, status);
CREATE INDEX idx_webhook_event_provider_external ON webhook_event(provider, external_event_id);
CREATE INDEX idx_webhook_event_tenant_provider_received ON webhook_event(tenant_id, provider, received_at DESC);
CREATE INDEX idx_webhook_event_status_received ON webhook_event(status, received_at DESC);
CREATE INDEX idx_object_storage_tenant_key ON object_storage_record(tenant_id, object_key);
CREATE INDEX idx_object_storage_tenant_fingerprint ON object_storage_record(tenant_id, sha256_fingerprint);