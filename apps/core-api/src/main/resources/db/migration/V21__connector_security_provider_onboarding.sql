ALTER TABLE channel_connection
  ADD COLUMN IF NOT EXISTS secret_reference_id VARCHAR(240) NULL,
  ADD COLUMN IF NOT EXISTS secret_last_updated_at TIMESTAMPTZ NULL,
  ADD COLUMN IF NOT EXISTS webhook_verification_mode VARCHAR(40) NOT NULL DEFAULT 'DISABLED_FOR_LOCAL_DEV',
  ADD COLUMN IF NOT EXISTS last_health_check_status VARCHAR(40) NULL,
  ADD COLUMN IF NOT EXISTS last_diagnostic_summary TEXT NULL;

ALTER TABLE integration_connection
  ADD COLUMN IF NOT EXISTS secret_reference_id VARCHAR(240) NULL,
  ADD COLUMN IF NOT EXISTS secret_last_updated_at TIMESTAMPTZ NULL,
  ADD COLUMN IF NOT EXISTS last_health_check_status VARCHAR(40) NULL,
  ADD COLUMN IF NOT EXISTS last_diagnostic_summary TEXT NULL;

ALTER TABLE inbound_channel_event
  ADD COLUMN IF NOT EXISTS verification_status VARCHAR(40) NULL,
  ADD COLUMN IF NOT EXISTS verification_reason VARCHAR(240) NULL;

ALTER TABLE connector_sync_event
  ADD COLUMN IF NOT EXISTS duration_ms BIGINT NULL,
  ADD COLUMN IF NOT EXISTS error_category VARCHAR(80) NULL;

UPDATE channel_connection
SET secret_reference_id = secret_ref
WHERE secret_reference_id IS NULL AND secret_ref IS NOT NULL;

UPDATE integration_connection
SET secret_reference_id = secret_ref
WHERE secret_reference_id IS NULL AND secret_ref IS NOT NULL;

ALTER TABLE channel_connection
  ADD CONSTRAINT ck_channel_connection_webhook_verification_mode
  CHECK (webhook_verification_mode IN ('DISABLED_FOR_LOCAL_DEV','SHARED_SECRET','SIGNATURE_HEADER','PROVIDER_SPECIFIC'));

ALTER TABLE inbound_channel_event
  ADD CONSTRAINT ck_inbound_channel_event_verification_status
  CHECK (verification_status IS NULL OR verification_status IN ('ACCEPTED','REJECTED','SKIPPED_LOCAL_DEV','DUPLICATE'));
