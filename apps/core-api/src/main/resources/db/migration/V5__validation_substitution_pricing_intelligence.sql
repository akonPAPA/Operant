CREATE TABLE validation_run (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  extraction_result_id UUID NOT NULL REFERENCES extraction_result(id),
  source_type VARCHAR(60) NOT NULL,
  status VARCHAR(40) NOT NULL,
  overall_status VARCHAR(40) NOT NULL,
  overall_confidence NUMERIC(8,4) NOT NULL DEFAULT 0,
  started_at TIMESTAMPTZ NULL,
  finished_at TIMESTAMPTZ NULL,
  error_message TEXT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE validation_issue (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  validation_run_id UUID NOT NULL REFERENCES validation_run(id),
  extraction_result_id UUID NULL REFERENCES extraction_result(id),
  extracted_line_item_id UUID NULL REFERENCES extracted_line_item(id),
  extracted_field_id UUID NULL REFERENCES extracted_field(id),
  issue_type VARCHAR(80) NOT NULL,
  severity VARCHAR(20) NOT NULL,
  message TEXT NOT NULL,
  details_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  status VARCHAR(30) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE customer_match_result (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  validation_run_id UUID NOT NULL REFERENCES validation_run(id),
  extraction_result_id UUID NOT NULL REFERENCES extraction_result(id),
  matched_customer_account_id UUID NULL REFERENCES customer_account(id),
  raw_customer_hint TEXT NULL,
  match_type VARCHAR(40) NOT NULL,
  confidence NUMERIC(8,4) NOT NULL DEFAULT 0,
  status VARCHAR(40) NOT NULL,
  candidates_json JSONB NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE product_match_result (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  validation_run_id UUID NOT NULL REFERENCES validation_run(id),
  extracted_line_item_id UUID NOT NULL REFERENCES extracted_line_item(id),
  matched_product_id UUID NULL REFERENCES product(id),
  raw_sku TEXT NULL,
  raw_description TEXT NULL,
  match_type VARCHAR(40) NOT NULL,
  confidence NUMERIC(8,4) NOT NULL DEFAULT 0,
  status VARCHAR(40) NOT NULL,
  candidates_json JSONB NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE uom_normalization_result (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  validation_run_id UUID NOT NULL REFERENCES validation_run(id),
  extracted_line_item_id UUID NOT NULL REFERENCES extracted_line_item(id),
  raw_uom VARCHAR(80) NULL,
  normalized_uom VARCHAR(40) NULL,
  status VARCHAR(40) NOT NULL,
  confidence NUMERIC(8,4) NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE inventory_check_result (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  validation_run_id UUID NOT NULL REFERENCES validation_run(id),
  extracted_line_item_id UUID NOT NULL REFERENCES extracted_line_item(id),
  product_id UUID NULL REFERENCES product(id),
  location_id UUID NULL REFERENCES location(id),
  requested_quantity NUMERIC(18,4) NULL,
  quantity_on_hand NUMERIC(18,4) NULL,
  quantity_available NUMERIC(18,4) NULL,
  quantity_reserved NUMERIC(18,4) NULL,
  status VARCHAR(40) NOT NULL,
  snapshot_id UUID NULL REFERENCES inventory_snapshot(id),
  checked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE price_check_result (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  validation_run_id UUID NOT NULL REFERENCES validation_run(id),
  extracted_line_item_id UUID NOT NULL REFERENCES extracted_line_item(id),
  product_id UUID NULL REFERENCES product(id),
  customer_account_id UUID NULL REFERENCES customer_account(id),
  price_rule_id UUID NULL REFERENCES price_rule(id),
  requested_quantity NUMERIC(18,4) NULL,
  uom VARCHAR(40) NULL,
  unit_price NUMERIC(18,4) NULL,
  currency VARCHAR(12) NULL,
  status VARCHAR(40) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE discount_check_result (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  validation_run_id UUID NOT NULL REFERENCES validation_run(id),
  extracted_line_item_id UUID NULL REFERENCES extracted_line_item(id),
  customer_account_id UUID NULL REFERENCES customer_account(id),
  product_id UUID NULL REFERENCES product(id),
  discount_rule_id UUID NULL REFERENCES discount_rule(id),
  requested_discount_percent NUMERIC(8,4) NULL,
  max_allowed_discount_percent NUMERIC(8,4) NULL,
  requires_approval BOOLEAN NOT NULL DEFAULT false,
  status VARCHAR(40) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE margin_check_result (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  validation_run_id UUID NOT NULL REFERENCES validation_run(id),
  extracted_line_item_id UUID NOT NULL REFERENCES extracted_line_item(id),
  product_id UUID NULL REFERENCES product(id),
  margin_rule_id UUID NULL REFERENCES margin_rule(id),
  unit_price NUMERIC(18,4) NULL,
  unit_cost NUMERIC(18,4) NULL,
  gross_margin_percent NUMERIC(8,4) NULL,
  minimum_gross_margin_percent NUMERIC(8,4) NULL,
  approval_required_below_percent NUMERIC(8,4) NULL,
  requires_approval BOOLEAN NOT NULL DEFAULT false,
  status VARCHAR(50) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE substitute_candidate (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  validation_run_id UUID NOT NULL REFERENCES validation_run(id),
  extracted_line_item_id UUID NOT NULL REFERENCES extracted_line_item(id),
  source_product_id UUID NULL REFERENCES product(id),
  substitute_product_id UUID NOT NULL REFERENCES product(id),
  substitute_type VARCHAR(40) NOT NULL,
  risk_level VARCHAR(20) NOT NULL,
  rank_score NUMERIC(8,4) NOT NULL DEFAULT 0,
  reason TEXT NOT NULL,
  inventory_status VARCHAR(40) NULL,
  margin_status VARCHAR(50) NULL,
  requires_approval BOOLEAN NOT NULL DEFAULT false,
  status VARCHAR(40) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE approval_requirement (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  validation_run_id UUID NOT NULL REFERENCES validation_run(id),
  extracted_line_item_id UUID NULL REFERENCES extracted_line_item(id),
  requirement_type VARCHAR(80) NOT NULL,
  severity VARCHAR(20) NOT NULL,
  reason TEXT NOT NULL,
  status VARCHAR(30) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_validation_run_tenant_result ON validation_run(tenant_id, extraction_result_id);
CREATE INDEX idx_validation_run_tenant_status_created ON validation_run(tenant_id, status, created_at DESC);
CREATE INDEX idx_validation_run_tenant_overall_created ON validation_run(tenant_id, overall_status, created_at DESC);
CREATE INDEX idx_validation_issue_tenant_run ON validation_issue(tenant_id, validation_run_id);
CREATE INDEX idx_validation_issue_tenant_type ON validation_issue(tenant_id, issue_type);
CREATE INDEX idx_validation_issue_tenant_severity ON validation_issue(tenant_id, severity);
CREATE INDEX idx_validation_issue_tenant_status ON validation_issue(tenant_id, status);
CREATE INDEX idx_customer_match_tenant_run ON customer_match_result(tenant_id, validation_run_id);
CREATE INDEX idx_customer_match_tenant_customer ON customer_match_result(tenant_id, matched_customer_account_id);
CREATE INDEX idx_customer_match_tenant_status ON customer_match_result(tenant_id, status);
CREATE INDEX idx_product_match_tenant_run ON product_match_result(tenant_id, validation_run_id);
CREATE INDEX idx_product_match_tenant_line ON product_match_result(tenant_id, extracted_line_item_id);
CREATE INDEX idx_product_match_tenant_product ON product_match_result(tenant_id, matched_product_id);
CREATE INDEX idx_product_match_tenant_status ON product_match_result(tenant_id, status);
CREATE INDEX idx_inventory_check_tenant_run ON inventory_check_result(tenant_id, validation_run_id);
CREATE INDEX idx_inventory_check_tenant_product ON inventory_check_result(tenant_id, product_id);
CREATE INDEX idx_inventory_check_tenant_status ON inventory_check_result(tenant_id, status);
CREATE INDEX idx_price_check_tenant_run ON price_check_result(tenant_id, validation_run_id);
CREATE INDEX idx_price_check_tenant_product ON price_check_result(tenant_id, product_id);
CREATE INDEX idx_price_check_tenant_customer ON price_check_result(tenant_id, customer_account_id);
CREATE INDEX idx_price_check_tenant_status ON price_check_result(tenant_id, status);
CREATE INDEX idx_discount_check_tenant_run ON discount_check_result(tenant_id, validation_run_id);
CREATE INDEX idx_discount_check_tenant_status ON discount_check_result(tenant_id, status);
CREATE INDEX idx_margin_check_tenant_run ON margin_check_result(tenant_id, validation_run_id);
CREATE INDEX idx_margin_check_tenant_status ON margin_check_result(tenant_id, status);
CREATE INDEX idx_substitute_candidate_tenant_run ON substitute_candidate(tenant_id, validation_run_id);
CREATE INDEX idx_substitute_candidate_tenant_line ON substitute_candidate(tenant_id, extracted_line_item_id);
CREATE INDEX idx_substitute_candidate_tenant_product ON substitute_candidate(tenant_id, substitute_product_id);
CREATE INDEX idx_substitute_candidate_tenant_status ON substitute_candidate(tenant_id, status);
CREATE INDEX idx_approval_requirement_tenant_run ON approval_requirement(tenant_id, validation_run_id);
CREATE INDEX idx_approval_requirement_tenant_type ON approval_requirement(tenant_id, requirement_type);
CREATE INDEX idx_approval_requirement_tenant_status ON approval_requirement(tenant_id, status);
CREATE INDEX idx_approval_requirement_tenant_severity ON approval_requirement(tenant_id, severity);
