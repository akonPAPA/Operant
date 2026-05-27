CREATE TABLE IF NOT EXISTS roi_assumptions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  average_manual_handling_minutes_per_request NUMERIC(10,2) NOT NULL,
  average_fully_loaded_operator_hourly_cost NUMERIC(12,2) NOT NULL,
  default_currency VARCHAR(3) NOT NULL,
  value_attribution_mode VARCHAR(20) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uk_roi_assumptions_tenant UNIQUE (tenant_id)
);

CREATE INDEX IF NOT EXISTS idx_roi_assumptions_tenant_updated
  ON roi_assumptions(tenant_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_event_tenant_action_occurred
  ON audit_event(tenant_id, action, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_draft_quote_tenant_created
  ON draft_quote(tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_draft_order_tenant_created
  ON draft_order(tenant_id, created_at DESC);
