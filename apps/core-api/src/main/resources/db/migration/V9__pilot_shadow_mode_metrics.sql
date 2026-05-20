CREATE TABLE shadow_run (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  source_type VARCHAR(40) NOT NULL,
  source_id UUID NOT NULL,
  prediction_type VARCHAR(40) NOT NULL,
  provider_mode VARCHAR(30) NOT NULL,
  provider_label VARCHAR(120) NOT NULL,
  prediction_payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  confidence_score NUMERIC(5,4) NULL,
  status VARCHAR(30) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  reviewed_at TIMESTAMPTZ NULL,
  CONSTRAINT ck_shadow_run_mock_only CHECK (provider_mode = 'MOCK_ONLY')
);

CREATE TABLE human_correction (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenant(id),
  shadow_run_id UUID NOT NULL REFERENCES shadow_run(id),
  corrected_by_user_id UUID NULL,
  correction_type VARCHAR(40) NOT NULL,
  before_payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  after_payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  correction_reason TEXT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_shadow_run_tenant_source_status ON shadow_run(tenant_id, source_type, status, created_at DESC);
CREATE INDEX idx_shadow_run_tenant_prediction_status ON shadow_run(tenant_id, prediction_type, status, created_at DESC);
CREATE INDEX idx_human_correction_tenant_shadow_run ON human_correction(tenant_id, shadow_run_id, created_at DESC);
CREATE INDEX idx_human_correction_tenant_type ON human_correction(tenant_id, correction_type, created_at DESC);
