CREATE TABLE customer_contact (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  customer_account_id UUID NOT NULL REFERENCES customer_account(id),
  contact_type VARCHAR(40) NOT NULL,
  full_name VARCHAR(255) NOT NULL,
  email VARCHAR(320) NULL,
  phone VARCHAR(80) NULL,
  title VARCHAR(160) NULL,
  preferred BOOLEAN NOT NULL DEFAULT false,
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ NULL
);

CREATE TABLE vehicle_make (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  code VARCHAR(120) NOT NULL,
  name VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_vehicle_make_tenant_code UNIQUE (tenant_id, code)
);

CREATE TABLE vehicle_model (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  vehicle_make_id UUID NOT NULL REFERENCES vehicle_make(id),
  code VARCHAR(120) NOT NULL,
  name VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_vehicle_model_tenant_make_code UNIQUE (tenant_id, vehicle_make_id, code)
);

CREATE TABLE vehicle_year (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  vehicle_model_id UUID NOT NULL REFERENCES vehicle_model(id),
  model_year INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_vehicle_year_tenant_model_year UNIQUE (tenant_id, vehicle_model_id, model_year)
);

CREATE TABLE vehicle_configuration (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  vehicle_year_id UUID NOT NULL REFERENCES vehicle_year(id),
  configuration_code VARCHAR(160) NOT NULL,
  engine VARCHAR(160) NULL,
  drivetrain VARCHAR(160) NULL,
  transmission VARCHAR(160) NULL,
  notes TEXT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_vehicle_configuration_tenant_year_code UNIQUE (tenant_id, vehicle_year_id, configuration_code)
);

CREATE TABLE import_validation_issue (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  import_job_id UUID NOT NULL REFERENCES import_job(id),
  import_staging_row_id UUID NULL REFERENCES import_staging_row(id),
  row_number INTEGER NULL,
  severity VARCHAR(40) NOT NULL,
  issue_code VARCHAR(120) NOT NULL,
  message TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_customer_contact_tenant_account ON customer_contact(tenant_id, customer_account_id);
CREATE INDEX idx_vehicle_make_tenant_name ON vehicle_make(tenant_id, name);
CREATE INDEX idx_vehicle_model_tenant_make ON vehicle_model(tenant_id, vehicle_make_id);
CREATE INDEX idx_vehicle_year_tenant_model ON vehicle_year(tenant_id, vehicle_model_id);
CREATE INDEX idx_vehicle_configuration_tenant_year ON vehicle_configuration(tenant_id, vehicle_year_id);
CREATE INDEX idx_import_validation_issue_tenant_job ON import_validation_issue(tenant_id, import_job_id);
CREATE INDEX idx_import_validation_issue_tenant_row ON import_validation_issue(tenant_id, import_staging_row_id);

CREATE INDEX IF NOT EXISTS idx_discount_rule_tenant_segment_product ON discount_rule(tenant_id, customer_segment_id, product_id);
CREATE INDEX IF NOT EXISTS idx_margin_rule_tenant_product ON margin_rule(tenant_id, product_id);