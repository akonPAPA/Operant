CREATE INDEX IF NOT EXISTS idx_change_request_tenant_source
  ON change_request(tenant_id, source_type, source_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_change_request_tenant_status
  ON change_request(tenant_id, approval_status, execution_status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_change_request_tenant_external_reference
  ON change_request(tenant_id, external_reference)
  WHERE external_reference IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_connector_sync_event_tenant_connection_started
  ON connector_sync_event(tenant_id, integration_connection_id, started_at DESC);
