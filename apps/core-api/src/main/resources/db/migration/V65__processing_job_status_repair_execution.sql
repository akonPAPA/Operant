-- OP-CAP-54 Controlled Approved Operational Repair Executor for One Safe Target.
--
-- The FIRST real, approval-gated data-repair executor. It is deliberately bounded to ONE operational
-- target: processing-job status repair (unsticking a wedged PENDING/PROCESSING job to a terminal FAILED
-- state so the existing control-plane retry path can requeue it). There is NO arbitrary SQL/script, NO
-- generic table/row/field patch, and NO business order/quote/inventory/customer/price mutation.
--
-- This migration is additive and non-destructive: it only ADDs nullable execution-result columns to the
-- existing data_repair_request table. It rewrites no table, drops nothing, and touches no business table.
-- The columns are stamped only by the backend-owned executor on a successful, approved, validated repair;
-- once present they make a repeated execute an idempotent replay. Existing rows keep them NULL (no prior
-- execution). The execution_status column already exists (V62); OP-CAP-54 adds the EXECUTED value to it.
--
-- No secret, raw payload, connector credential, or free-form target is stored here — only safe operational
-- metadata (the repaired processing-job id, previous/new bounded status, who executed it, and when).

ALTER TABLE data_repair_request
  ADD COLUMN IF NOT EXISTS target_processing_job_id UUID NULL,
  ADD COLUMN IF NOT EXISTS previous_status          VARCHAR(40) NULL,
  ADD COLUMN IF NOT EXISTS new_status               VARCHAR(40) NULL,
  ADD COLUMN IF NOT EXISTS executed_at              TIMESTAMPTZ NULL,
  ADD COLUMN IF NOT EXISTS executed_by              UUID NULL;
