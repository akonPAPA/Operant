-- OP-CAP-52 Support Grant Approval Workflow + Data-Repair Approval-Gated Execution Stub.
--
-- A bounded approval layer on top of the OP-CAP-51 support foundation. It makes sensitive support access
-- and data-repair execution attempts require explicit, backend-owned approval, while keeping real
-- mutation/execution DISABLED:
--   * support_access_grant gains an approval gate: a sensitive grant is born PENDING_APPROVAL and cannot
--     authorize access until APPROVED; a low-risk (diagnostics) grant remains AUTO_APPROVED;
--   * data_repair_request gains an approval gate: NONE -> PENDING_APPROVAL -> APPROVED|REJECTED. Even an
--     APPROVED request never executes — execution_status stays EXECUTION_DISABLED and the execute endpoint
--     is a stub that always fails closed.
--
-- This migration is additive and non-destructive: it only ADDs nullable/defaulted columns. It rewrites no
-- existing table, drops nothing, and touches no business order/quote/inventory/customer/price table.
-- Existing OP-CAP-51 grants default to AUTO_APPROVED (they were already usable), and existing data-repair
-- requests default to approval_status NONE (dry-run only, no execution requested).

ALTER TABLE support_access_grant
  ADD COLUMN IF NOT EXISTS approval_status      VARCHAR(20) NOT NULL DEFAULT 'AUTO_APPROVED',
  ADD COLUMN IF NOT EXISTS approved_by          UUID NULL,
  ADD COLUMN IF NOT EXISTS approval_decided_at  TIMESTAMPTZ NULL,
  ADD COLUMN IF NOT EXISTS approval_note        VARCHAR(500) NULL;

ALTER TABLE data_repair_request
  ADD COLUMN IF NOT EXISTS approval_status         VARCHAR(30) NOT NULL DEFAULT 'NONE',
  ADD COLUMN IF NOT EXISTS affected_target_summary VARCHAR(500) NULL,
  ADD COLUMN IF NOT EXISTS approved_by             UUID NULL,
  ADD COLUMN IF NOT EXISTS approval_note           VARCHAR(500) NULL,
  ADD COLUMN IF NOT EXISTS approval_requested_at   TIMESTAMPTZ NULL,
  ADD COLUMN IF NOT EXISTS approval_expires_at     TIMESTAMPTZ NULL,
  ADD COLUMN IF NOT EXISTS approval_decided_at     TIMESTAMPTZ NULL;
