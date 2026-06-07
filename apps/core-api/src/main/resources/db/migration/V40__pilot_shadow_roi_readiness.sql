-- OP-CAP-11F Pilot Shadow-Mode ROI Readiness.
-- Non-destructive extension of the existing Stage 10B shadow_run table with structured,
-- tenant-scoped pilot evidence fields. No raw document/message/AI text is stored here:
-- only an exception category label, cycle-time minutes, and advisory boolean flags.
-- All columns are nullable or carry a safe default so existing shadow runs remain valid.

ALTER TABLE shadow_run ADD COLUMN IF NOT EXISTS exception_category VARCHAR(60) NULL;
ALTER TABLE shadow_run ADD COLUMN IF NOT EXISTS manual_baseline_minutes NUMERIC(8,2) NULL;
ALTER TABLE shadow_run ADD COLUMN IF NOT EXISTS assisted_processing_minutes NUMERIC(8,2) NULL;
ALTER TABLE shadow_run ADD COLUMN IF NOT EXISTS automation_candidate BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shadow_run ADD COLUMN IF NOT EXISTS review_required BOOLEAN NOT NULL DEFAULT FALSE;

-- Supports the tenant-scoped exception-category breakdown read model.
CREATE INDEX IF NOT EXISTS idx_shadow_run_tenant_exception
  ON shadow_run(tenant_id, exception_category, created_at DESC);
