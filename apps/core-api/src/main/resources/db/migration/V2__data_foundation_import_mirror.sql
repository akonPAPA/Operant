CREATE TABLE customer_segment (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  code VARCHAR(120) NOT NULL,
  name VARCHAR(255) NOT NULL,
  description TEXT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_customer_segment_tenant_code UNIQUE (tenant_id, code)
);

CREATE TABLE location (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  code VARCHAR(120) NOT NULL,
  name VARCHAR(255) NOT NULL,
  type VARCHAR(40) NOT NULL,
  address TEXT NULL,
  city VARCHAR(160) NULL,
  country VARCHAR(120) NULL,
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_location_tenant_code UNIQUE (tenant_id, code)
);

CREATE TABLE department (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  code VARCHAR(120) NOT NULL,
  name VARCHAR(255) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_department_tenant_code UNIQUE (tenant_id, code)
);

CREATE TABLE customer_account (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  external_ref VARCHAR(180) NULL,
  account_code VARCHAR(120) NOT NULL,
  legal_name VARCHAR(255) NOT NULL,
  display_name VARCHAR(255) NOT NULL,
  segment_id UUID NULL REFERENCES customer_segment(id),
  status VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
  default_currency CHAR(3) NOT NULL DEFAULT 'USD',
  default_location_id UUID NULL REFERENCES location(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ NULL,
  CONSTRAINT uq_customer_account_tenant_code UNIQUE (tenant_id, account_code)
);

CREATE TABLE product (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  sku VARCHAR(160) NOT NULL,
  name VARCHAR(255) NOT NULL,
  description TEXT NULL,
  category VARCHAR(160) NULL,
  brand VARCHAR(160) NULL,
  manufacturer VARCHAR(160) NULL,
  base_uom VARCHAR(40) NOT NULL,
  status VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
  cost NUMERIC(18, 4) NULL,
  currency CHAR(3) NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ NULL,
  CONSTRAINT uq_product_tenant_sku UNIQUE (tenant_id, sku),
  CONSTRAINT chk_product_cost_non_negative CHECK (cost IS NULL OR cost >= 0)
);

CREATE TABLE product_alias (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  product_id UUID NOT NULL REFERENCES product(id),
  alias_type VARCHAR(40) NOT NULL,
  raw_alias VARCHAR(255) NOT NULL,
  normalized_alias VARCHAR(255) NOT NULL,
  customer_account_id UUID NULL REFERENCES customer_account(id),
  confidence_default NUMERIC(5, 4) NULL,
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE oem_reference (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  product_id UUID NOT NULL REFERENCES product(id),
  oem_code VARCHAR(255) NOT NULL,
  manufacturer VARCHAR(160) NULL,
  normalized_oem_code VARCHAR(255) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE product_compatibility (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  product_id UUID NOT NULL REFERENCES product(id),
  compatible_type VARCHAR(40) NOT NULL,
  make VARCHAR(160) NULL,
  model VARCHAR(160) NULL,
  year_from INTEGER NULL,
  year_to INTEGER NULL,
  configuration VARCHAR(255) NULL,
  notes TEXT NULL,
  risk_level VARCHAR(40) NOT NULL DEFAULT 'MEDIUM',
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE product_substitute (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  source_product_id UUID NOT NULL REFERENCES product(id),
  substitute_product_id UUID NOT NULL REFERENCES product(id),
  substitute_type VARCHAR(60) NOT NULL,
  risk_level VARCHAR(40) NOT NULL DEFAULT 'MEDIUM',
  requires_approval BOOLEAN NOT NULL DEFAULT true,
  notes TEXT NULL,
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_product_substitute_not_self CHECK (source_product_id <> substitute_product_id)
);

CREATE TABLE customer_substitution_preference (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  customer_account_id UUID NOT NULL REFERENCES customer_account(id),
  product_id UUID NULL REFERENCES product(id),
  brand VARCHAR(160) NULL,
  allow_aftermarket BOOLEAN NOT NULL DEFAULT false,
  allow_used BOOLEAN NULL,
  blocked_substitute_product_id UUID NULL REFERENCES product(id),
  notes TEXT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE data_source (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  name VARCHAR(255) NOT NULL,
  source_type VARCHAR(60) NOT NULL,
  status VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE import_job (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  data_source_id UUID NULL REFERENCES data_source(id),
  import_type VARCHAR(40) NOT NULL,
  original_filename VARCHAR(255) NULL,
  status VARCHAR(40) NOT NULL DEFAULT 'CREATED',
  total_rows INTEGER NOT NULL DEFAULT 0,
  valid_rows INTEGER NOT NULL DEFAULT 0,
  invalid_rows INTEGER NOT NULL DEFAULT 0,
  error_summary TEXT NULL,
  created_by UUID NULL REFERENCES user_account(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE import_staging_row (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  import_job_id UUID NOT NULL REFERENCES import_job(id),
  row_number INTEGER NOT NULL,
  raw_data JSONB NOT NULL DEFAULT '{}'::jsonb,
  mapped_data JSONB NOT NULL DEFAULT '{}'::jsonb,
  validation_status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
  validation_errors JSONB NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_import_staging_row_job_row UNIQUE (tenant_id, import_job_id, row_number)
);

CREATE TABLE validation_report (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  import_job_id UUID NOT NULL REFERENCES import_job(id),
  status VARCHAR(40) NOT NULL,
  summary JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_validation_report_import_job UNIQUE (tenant_id, import_job_id)
);

CREATE TABLE inventory_snapshot (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  product_id UUID NOT NULL REFERENCES product(id),
  location_id UUID NOT NULL REFERENCES location(id),
  quantity_on_hand NUMERIC(18, 4) NOT NULL,
  quantity_available NUMERIC(18, 4) NOT NULL,
  quantity_reserved NUMERIC(18, 4) NOT NULL DEFAULT 0,
  captured_at TIMESTAMPTZ NOT NULL,
  source VARCHAR(80) NOT NULL,
  import_job_id UUID NULL REFERENCES import_job(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_inventory_quantities_non_negative CHECK (quantity_on_hand >= 0 AND quantity_available >= 0 AND quantity_reserved >= 0)
);

CREATE TABLE price_rule (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  product_id UUID NOT NULL REFERENCES product(id),
  customer_account_id UUID NULL REFERENCES customer_account(id),
  customer_segment_id UUID NULL REFERENCES customer_segment(id),
  location_id UUID NULL REFERENCES location(id),
  min_quantity NUMERIC(18, 4) NOT NULL DEFAULT 1,
  uom VARCHAR(40) NOT NULL,
  unit_price NUMERIC(18, 4) NOT NULL,
  currency CHAR(3) NOT NULL,
  active_from TIMESTAMPTZ NOT NULL,
  active_to TIMESTAMPTZ NULL,
  priority INTEGER NOT NULL DEFAULT 100,
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_price_rule_amounts CHECK (min_quantity > 0 AND unit_price >= 0)
);

CREATE TABLE discount_rule (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  code VARCHAR(120) NOT NULL,
  name VARCHAR(255) NOT NULL,
  customer_account_id UUID NULL REFERENCES customer_account(id),
  customer_segment_id UUID NULL REFERENCES customer_segment(id),
  product_id UUID NULL REFERENCES product(id),
  max_discount_percent NUMERIC(7, 4) NOT NULL,
  requires_approval_above_percent NUMERIC(7, 4) NOT NULL,
  active_from TIMESTAMPTZ NOT NULL,
  active_to TIMESTAMPTZ NULL,
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_discount_rule_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT chk_discount_rule_percent CHECK (max_discount_percent >= 0 AND requires_approval_above_percent >= 0)
);

CREATE TABLE margin_rule (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  code VARCHAR(120) NOT NULL,
  name VARCHAR(255) NOT NULL,
  product_id UUID NULL REFERENCES product(id),
  category VARCHAR(160) NULL,
  customer_segment_id UUID NULL REFERENCES customer_segment(id),
  minimum_gross_margin_percent NUMERIC(7, 4) NOT NULL,
  approval_required_below_percent NUMERIC(7, 4) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_margin_rule_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT chk_margin_rule_percent CHECK (minimum_gross_margin_percent >= 0 AND approval_required_below_percent >= 0)
);

CREATE INDEX idx_product_tenant_sku ON product(tenant_id, sku);
CREATE INDEX idx_product_tenant_status ON product(tenant_id, status);
CREATE INDEX idx_product_tenant_category ON product(tenant_id, category);
CREATE INDEX idx_product_alias_tenant_normalized ON product_alias(tenant_id, normalized_alias);
CREATE INDEX idx_product_alias_tenant_product ON product_alias(tenant_id, product_id);
CREATE INDEX idx_product_alias_tenant_customer ON product_alias(tenant_id, customer_account_id);
CREATE INDEX idx_oem_reference_tenant_normalized ON oem_reference(tenant_id, normalized_oem_code);
CREATE INDEX idx_oem_reference_tenant_product ON oem_reference(tenant_id, product_id);
CREATE INDEX idx_customer_account_tenant_code ON customer_account(tenant_id, account_code);
CREATE INDEX idx_customer_account_tenant_legal_name ON customer_account(tenant_id, legal_name);
CREATE INDEX idx_customer_account_tenant_status ON customer_account(tenant_id, status);
CREATE INDEX idx_inventory_tenant_product_location_captured ON inventory_snapshot(tenant_id, product_id, location_id, captured_at DESC);
CREATE INDEX idx_price_rule_tenant_product ON price_rule(tenant_id, product_id);
CREATE INDEX idx_price_rule_tenant_customer ON price_rule(tenant_id, customer_account_id);
CREATE INDEX idx_price_rule_tenant_segment ON price_rule(tenant_id, customer_segment_id);
CREATE INDEX idx_price_rule_tenant_dates ON price_rule(tenant_id, active_from, active_to);
CREATE INDEX idx_import_job_tenant_status_created ON import_job(tenant_id, status, created_at DESC);
CREATE INDEX idx_import_staging_row_tenant_job_row ON import_staging_row(tenant_id, import_job_id, row_number);
CREATE INDEX idx_import_staging_row_tenant_job_status ON import_staging_row(tenant_id, import_job_id, validation_status);