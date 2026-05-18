CREATE TABLE extraction_run (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  source_type VARCHAR(60) NOT NULL,
  source_id UUID NOT NULL,
  processing_job_id UUID NULL REFERENCES processing_job(id),
  status VARCHAR(40) NOT NULL,
  provider_type VARCHAR(40) NOT NULL,
  provider_name VARCHAR(160) NULL,
  model_name VARCHAR(160) NULL,
  prompt_version VARCHAR(80) NULL,
  schema_version VARCHAR(80) NOT NULL,
  started_at TIMESTAMPTZ NULL,
  finished_at TIMESTAMPTZ NULL,
  error_message TEXT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE extracted_document_text (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  extraction_run_id UUID NOT NULL REFERENCES extraction_run(id),
  source_type VARCHAR(60) NOT NULL,
  source_id UUID NOT NULL,
  text_content TEXT NOT NULL,
  language VARCHAR(40) NULL,
  extraction_method VARCHAR(60) NOT NULL,
  page_count INTEGER NULL,
  character_count INTEGER NOT NULL,
  quality_score NUMERIC(7, 4) NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE extraction_result (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  extraction_run_id UUID NOT NULL REFERENCES extraction_run(id),
  source_type VARCHAR(60) NOT NULL,
  source_id UUID NOT NULL,
  detected_intent VARCHAR(60) NOT NULL,
  document_type VARCHAR(60) NOT NULL,
  overall_confidence NUMERIC(7, 4) NOT NULL,
  result_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  validation_status VARCHAR(40) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE source_evidence (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  extraction_run_id UUID NOT NULL REFERENCES extraction_run(id),
  source_type VARCHAR(60) NOT NULL,
  source_id UUID NOT NULL,
  evidence_type VARCHAR(60) NOT NULL,
  page_number INTEGER NULL,
  start_offset INTEGER NULL,
  end_offset INTEGER NULL,
  snippet TEXT NULL,
  bounding_box_json JSONB NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE extracted_field (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  extraction_result_id UUID NOT NULL REFERENCES extraction_result(id),
  field_name VARCHAR(160) NOT NULL,
  raw_value TEXT NULL,
  normalized_value TEXT NULL,
  value_type VARCHAR(40) NOT NULL,
  confidence NUMERIC(7, 4) NOT NULL,
  source_evidence_id UUID NULL REFERENCES source_evidence(id),
  validation_status VARCHAR(40) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE extracted_line_item (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  extraction_result_id UUID NOT NULL REFERENCES extraction_result(id),
  line_number INTEGER NOT NULL,
  raw_sku VARCHAR(255) NULL,
  raw_description TEXT NULL,
  raw_quantity VARCHAR(80) NULL,
  normalized_quantity NUMERIC(18, 4) NULL,
  raw_uom VARCHAR(80) NULL,
  normalized_uom VARCHAR(40) NULL,
  requested_date DATE NULL,
  raw_unit_price VARCHAR(80) NULL,
  currency CHAR(3) NULL,
  confidence NUMERIC(7, 4) NOT NULL,
  source_evidence_id UUID NULL REFERENCES source_evidence(id),
  validation_status VARCHAR(40) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ai_suggestion (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  extraction_run_id UUID NOT NULL REFERENCES extraction_run(id),
  suggestion_type VARCHAR(60) NOT NULL,
  suggestion_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  confidence NUMERIC(7, 4) NOT NULL,
  status VARCHAR(40) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE prompt_template_version (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(160) NOT NULL,
  version VARCHAR(80) NOT NULL,
  purpose TEXT NOT NULL,
  schema_version VARCHAR(80) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_prompt_template_name_version UNIQUE (name, version)
);

CREATE INDEX idx_extraction_run_tenant_source ON extraction_run(tenant_id, source_type, source_id);
CREATE INDEX idx_extraction_run_tenant_status_created ON extraction_run(tenant_id, status, created_at DESC);
CREATE INDEX idx_extraction_run_tenant_job ON extraction_run(tenant_id, processing_job_id);
CREATE INDEX idx_extracted_text_tenant_run ON extracted_document_text(tenant_id, extraction_run_id);
CREATE INDEX idx_extracted_text_tenant_source ON extracted_document_text(tenant_id, source_type, source_id);
CREATE INDEX idx_extraction_result_tenant_run ON extraction_result(tenant_id, extraction_run_id);
CREATE INDEX idx_extraction_result_tenant_source ON extraction_result(tenant_id, source_type, source_id);
CREATE INDEX idx_extraction_result_tenant_intent ON extraction_result(tenant_id, detected_intent);
CREATE INDEX idx_extraction_result_tenant_validation ON extraction_result(tenant_id, validation_status);
CREATE INDEX idx_extracted_field_tenant_result ON extracted_field(tenant_id, extraction_result_id);
CREATE INDEX idx_extracted_field_tenant_name ON extracted_field(tenant_id, field_name);
CREATE INDEX idx_extracted_field_tenant_validation ON extracted_field(tenant_id, validation_status);
CREATE INDEX idx_extracted_line_item_tenant_result ON extracted_line_item(tenant_id, extraction_result_id);
CREATE INDEX idx_extracted_line_item_tenant_validation ON extracted_line_item(tenant_id, validation_status);
CREATE INDEX idx_source_evidence_tenant_run ON source_evidence(tenant_id, extraction_run_id);
CREATE INDEX idx_source_evidence_tenant_source ON source_evidence(tenant_id, source_type, source_id);
CREATE INDEX idx_ai_suggestion_tenant_run ON ai_suggestion(tenant_id, extraction_run_id);
CREATE INDEX idx_ai_suggestion_tenant_type ON ai_suggestion(tenant_id, suggestion_type);
CREATE INDEX idx_ai_suggestion_tenant_status ON ai_suggestion(tenant_id, status);