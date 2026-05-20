CREATE TABLE inventory_movement (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  product_id UUID NOT NULL REFERENCES product(id),
  location_id UUID NOT NULL REFERENCES location(id),
  movement_type VARCHAR(40) NOT NULL,
  quantity NUMERIC(18,4) NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  source_type VARCHAR(60) NOT NULL,
  source_reference VARCHAR(160) NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reconciliation_case (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  product_id UUID NOT NULL REFERENCES product(id),
  location_id UUID NOT NULL REFERENCES location(id),
  expected_stock NUMERIC(18,4) NOT NULL,
  actual_stock NUMERIC(18,4) NOT NULL,
  mismatch_quantity NUMERIC(18,4) NOT NULL,
  severity VARCHAR(20) NOT NULL,
  status VARCHAR(30) NOT NULL,
  likely_causes JSONB NOT NULL DEFAULT '[]'::jsonb,
  calculated_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_inventory_movement_tenant_product_location_time ON inventory_movement(tenant_id, product_id, location_id, occurred_at DESC);
CREATE INDEX idx_inventory_movement_tenant_type_time ON inventory_movement(tenant_id, movement_type, occurred_at DESC);
CREATE INDEX idx_reconciliation_case_tenant_status_severity ON reconciliation_case(tenant_id, status, severity, updated_at DESC);
CREATE INDEX idx_reconciliation_case_tenant_product_location ON reconciliation_case(tenant_id, product_id, location_id, updated_at DESC);
CREATE INDEX idx_bot_message_tenant_channel_created ON bot_message(tenant_id, channel, created_at DESC);
