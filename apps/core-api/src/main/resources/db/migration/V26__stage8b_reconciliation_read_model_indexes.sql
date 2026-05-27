CREATE INDEX IF NOT EXISTS idx_inventory_snapshot_tenant_captured_at
  ON inventory_snapshot(tenant_id, captured_at DESC);

CREATE INDEX IF NOT EXISTS idx_inventory_snapshot_tenant_available
  ON inventory_snapshot(tenant_id, quantity_available);

CREATE INDEX IF NOT EXISTS idx_reconciliation_case_tenant_status_updated
  ON reconciliation_case(tenant_id, status, updated_at DESC);
