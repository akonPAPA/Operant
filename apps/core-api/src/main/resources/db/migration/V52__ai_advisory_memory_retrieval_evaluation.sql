-- OP-CAP-19 Transactional Trust/AI Event Auto-Publishing + Advisory Memory Retrieval Ranking +
-- Projected Memory Evaluation Harness.
--
-- This stage adds NO new event/projector tables (Layer A reuses the OP-CAP-18 trust_ai_domain_event
-- publisher; Layer B reuses the OP-CAP-17F ai_memory_record read model). It only adds:
--   1) supplementary retrieval indexes on ai_memory_record for Layer B advisory ranking, and
--   2) the Layer C projected-memory evaluation harness tables.
--
-- Every table is tenant-isolated via tenant_id. All text columns are bounded VARCHAR (no unbounded TEXT).
-- No raw documents, OCR text, prompts, customer messages, secrets, card data, or bank credentials are
-- stored — only memory keys, deterministic scores, boolean assertions, and bounded failure reasons.
-- Evaluation is read-only with respect to memory and business state; advisory memory is never the source
-- of truth.

-- ----------------------------------------------------------------------------
-- Layer B: supplementary retrieval indexes on the OP-CAP-17F advisory memory table.
-- (V49 already indexes (tenant_id, namespace, status, updated_at), (tenant_id, namespace, memory_key),
--  and (tenant_id, expires_at). These add the source-pointer and authority/confidence access paths.)
-- ----------------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_ai_memory_record_tenant_source
  ON ai_memory_record(tenant_id, source_type, source_id);

CREATE INDEX IF NOT EXISTS idx_ai_memory_record_tenant_authority_confidence
  ON ai_memory_record(tenant_id, authority_level, confidence);

-- ----------------------------------------------------------------------------
-- Layer C: projected-memory evaluation harness.
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ai_memory_evaluation_run (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant(id),
  run_type        VARCHAR(48) NOT NULL,
  status          VARCHAR(16) NOT NULL,
  started_at      TIMESTAMPTZ NULL,
  completed_at    TIMESTAMPTZ NULL,
  total_cases     INTEGER NOT NULL,
  passed_cases    INTEGER NOT NULL,
  failed_cases    INTEGER NOT NULL,
  average_score   NUMERIC(6,2) NULL,
  created_by      UUID NULL,
  created_at      TIMESTAMPTZ NOT NULL,
  CONSTRAINT chk_ai_memory_eval_run_counts CHECK (total_cases >= 0 AND passed_cases >= 0 AND failed_cases >= 0),
  CONSTRAINT chk_ai_memory_eval_run_avg CHECK (average_score IS NULL OR (average_score >= 0 AND average_score <= 100))
);

CREATE INDEX IF NOT EXISTS idx_ai_memory_eval_run_tenant_status_created
  ON ai_memory_evaluation_run(tenant_id, status, created_at);

CREATE INDEX IF NOT EXISTS idx_ai_memory_eval_run_tenant_type_created
  ON ai_memory_evaluation_run(tenant_id, run_type, created_at);

CREATE TABLE IF NOT EXISTS ai_memory_evaluation_case (
  id                            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id                     UUID NOT NULL REFERENCES tenant(id),
  run_id                        UUID NULL REFERENCES ai_memory_evaluation_run(id),
  case_type                     VARCHAR(48) NOT NULL,
  task_type                     VARCHAR(48) NOT NULL,
  namespace                     VARCHAR(48) NOT NULL,
  lookup_key                    VARCHAR(160) NULL,
  expected_memory_key           VARCHAR(160) NULL,
  expected_excluded_memory_key  VARCHAR(160) NULL,
  min_expected_score            INTEGER NULL,
  max_results                   INTEGER NOT NULL,
  status                        VARCHAR(16) NOT NULL,
  created_at                    TIMESTAMPTZ NOT NULL,
  CONSTRAINT chk_ai_memory_eval_case_max_results CHECK (max_results >= 1 AND max_results <= 25),
  CONSTRAINT chk_ai_memory_eval_case_min_score CHECK (min_expected_score IS NULL OR (min_expected_score >= 0 AND min_expected_score <= 100))
);

CREATE INDEX IF NOT EXISTS idx_ai_memory_eval_case_tenant_run
  ON ai_memory_evaluation_case(tenant_id, run_id);

CREATE INDEX IF NOT EXISTS idx_ai_memory_eval_case_tenant_type_status
  ON ai_memory_evaluation_case(tenant_id, case_type, status);

CREATE TABLE IF NOT EXISTS ai_memory_evaluation_result (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id             UUID NOT NULL REFERENCES tenant(id),
  run_id                UUID NOT NULL REFERENCES ai_memory_evaluation_run(id),
  case_id               UUID NOT NULL REFERENCES ai_memory_evaluation_case(id),
  status                VARCHAR(16) NOT NULL,
  top_memory_record_id  UUID NULL,
  top_memory_key        VARCHAR(160) NULL,
  top_score             INTEGER NULL,
  expected_matched      BOOLEAN NOT NULL,
  excluded_unsafe       BOOLEAN NOT NULL,
  tenant_isolated       BOOLEAN NOT NULL,
  failure_reason        VARCHAR(280) NULL,
  created_at            TIMESTAMPTZ NOT NULL,
  CONSTRAINT chk_ai_memory_eval_result_score CHECK (top_score IS NULL OR (top_score >= 0 AND top_score <= 100))
);

CREATE INDEX IF NOT EXISTS idx_ai_memory_eval_result_tenant_run
  ON ai_memory_evaluation_result(tenant_id, run_id);

CREATE INDEX IF NOT EXISTS idx_ai_memory_eval_result_tenant_case
  ON ai_memory_evaluation_result(tenant_id, case_id);

CREATE INDEX IF NOT EXISTS idx_ai_memory_eval_result_tenant_status_created
  ON ai_memory_evaluation_result(tenant_id, status, created_at);
