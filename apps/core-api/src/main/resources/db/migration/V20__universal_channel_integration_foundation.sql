CREATE TABLE channel_connection (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  provider_type VARCHAR(40) NOT NULL,
  display_name VARCHAR(180) NOT NULL,
  status VARCHAR(40) NOT NULL DEFAULT 'DRAFT',
  mode VARCHAR(40) NOT NULL DEFAULT 'READ_ONLY',
  external_account_id VARCHAR(180) NULL,
  webhook_url TEXT NULL,
  secret_ref VARCHAR(240) NULL,
  last_health_check_at TIMESTAMPTZ NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_channel_connection_provider CHECK (provider_type IN ('EMAIL','FILE_UPLOAD','API','TELEGRAM','WHATSAPP','META_MESSENGER','VIBER','WECHAT','OTHER')),
  CONSTRAINT ck_channel_connection_status CHECK (status IN ('DRAFT','ACTIVE','PAUSED','DISABLED','ERROR')),
  CONSTRAINT ck_channel_connection_mode CHECK (mode IN ('READ_ONLY','DRAFT_ONLY','WRITE_ENABLED'))
);

CREATE INDEX idx_channel_connection_tenant ON channel_connection(tenant_id, created_at DESC);
CREATE INDEX idx_channel_connection_provider ON channel_connection(provider_type);
CREATE INDEX idx_channel_connection_status ON channel_connection(status);

CREATE TABLE integration_connection (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  provider_type VARCHAR(60) NOT NULL,
  display_name VARCHAR(180) NOT NULL,
  status VARCHAR(40) NOT NULL DEFAULT 'DRAFT',
  mode VARCHAR(40) NOT NULL DEFAULT 'READ_ONLY',
  connection_kind VARCHAR(40) NOT NULL DEFAULT 'MANUAL_UPLOAD',
  secret_ref VARCHAR(240) NULL,
  endpoint_ref TEXT NULL,
  last_sync_at TIMESTAMPTZ NULL,
  last_health_check_at TIMESTAMPTZ NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ck_integration_connection_provider CHECK (provider_type IN ('ONE_C','EXCEL','CSV','GENERIC_DATABASE','GENERIC_REST_API','NETSUITE','DYNAMICS_365','EPICOR','SAP','ODOO','QUICKBOOKS','OTHER_ERP','OTHER_ACCOUNTING','OTHER_INVENTORY')),
  CONSTRAINT ck_integration_connection_status CHECK (status IN ('DRAFT','ACTIVE','PAUSED','DISABLED','ERROR')),
  CONSTRAINT ck_integration_connection_mode CHECK (mode IN ('READ_ONLY','MIRROR_ONLY','DRAFT_WRITE','WRITE_ENABLED')),
  CONSTRAINT ck_integration_connection_kind CHECK (connection_kind IN ('CLOUD_API','LOCAL_AGENT','FILE_IMPORT','DATABASE_READONLY','WEBHOOK','MANUAL_UPLOAD'))
);

CREATE INDEX idx_integration_connection_tenant ON integration_connection(tenant_id, created_at DESC);
CREATE INDEX idx_integration_connection_provider ON integration_connection(provider_type);
CREATE INDEX idx_integration_connection_status ON integration_connection(status);

CREATE TABLE inbound_channel_event (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  channel_connection_id UUID NOT NULL REFERENCES channel_connection(id),
  provider_type VARCHAR(40) NOT NULL,
  external_event_id VARCHAR(220) NULL,
  source_actor_type VARCHAR(40) NOT NULL DEFAULT 'UNKNOWN',
  source_actor_external_id VARCHAR(220) NULL,
  normalized_text TEXT NULL,
  payload_hash VARCHAR(128) NOT NULL,
  raw_payload_storage_ref VARCHAR(260) NULL,
  raw_payload_json JSONB NULL,
  status VARCHAR(40) NOT NULL DEFAULT 'RECEIVED',
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at TIMESTAMPTZ NULL,
  error_code VARCHAR(80) NULL,
  error_message TEXT NULL,
  CONSTRAINT ck_inbound_channel_event_provider CHECK (provider_type IN ('EMAIL','FILE_UPLOAD','API','TELEGRAM','WHATSAPP','META_MESSENGER','VIBER','WECHAT','OTHER')),
  CONSTRAINT ck_inbound_channel_event_actor CHECK (source_actor_type IN ('CUSTOMER','USER','SYSTEM','UNKNOWN')),
  CONSTRAINT ck_inbound_channel_event_status CHECK (status IN ('RECEIVED','NORMALIZED','ROUTED','FAILED','IGNORED'))
);

CREATE UNIQUE INDEX uq_inbound_channel_event_external_id
  ON inbound_channel_event(tenant_id, provider_type, external_event_id)
  WHERE external_event_id IS NOT NULL;
CREATE INDEX idx_inbound_channel_event_tenant ON inbound_channel_event(tenant_id, received_at DESC);
CREATE INDEX idx_inbound_channel_event_provider ON inbound_channel_event(provider_type);
CREATE INDEX idx_inbound_channel_event_status ON inbound_channel_event(status);
CREATE INDEX idx_inbound_channel_event_payload_hash ON inbound_channel_event(tenant_id, channel_connection_id, payload_hash);

CREATE TABLE connector_sync_event (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  integration_connection_id UUID NOT NULL REFERENCES integration_connection(id),
  provider_type VARCHAR(60) NOT NULL,
  sync_type VARCHAR(60) NOT NULL,
  direction VARCHAR(40) NOT NULL,
  status VARCHAR(40) NOT NULL DEFAULT 'STARTED',
  records_read INTEGER NOT NULL DEFAULT 0,
  records_written INTEGER NOT NULL DEFAULT 0,
  records_failed INTEGER NOT NULL DEFAULT 0,
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ NULL,
  error_code VARCHAR(80) NULL,
  error_message TEXT NULL,
  CONSTRAINT ck_connector_sync_provider CHECK (provider_type IN ('ONE_C','EXCEL','CSV','GENERIC_DATABASE','GENERIC_REST_API','NETSUITE','DYNAMICS_365','EPICOR','SAP','ODOO','QUICKBOOKS','OTHER_ERP','OTHER_ACCOUNTING','OTHER_INVENTORY')),
  CONSTRAINT ck_connector_sync_type CHECK (sync_type IN ('PRODUCT_IMPORT','CUSTOMER_IMPORT','INVENTORY_IMPORT','PRICE_IMPORT','ORDER_EXPORT','QUOTE_EXPORT','HEALTH_CHECK')),
  CONSTRAINT ck_connector_sync_direction CHECK (direction IN ('INBOUND','OUTBOUND')),
  CONSTRAINT ck_connector_sync_status CHECK (status IN ('STARTED','SUCCESS','PARTIAL_SUCCESS','FAILED'))
);

CREATE INDEX idx_connector_sync_event_tenant ON connector_sync_event(tenant_id, started_at DESC);
CREATE INDEX idx_connector_sync_event_provider ON connector_sync_event(provider_type);
CREATE INDEX idx_connector_sync_event_status ON connector_sync_event(status);
