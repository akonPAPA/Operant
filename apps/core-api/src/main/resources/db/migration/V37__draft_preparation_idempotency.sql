-- OP-CAP-09A Draft Quote/Order Preparation Foundation
-- Enforce one internal draft per (tenant, source review case / handoff) so that
-- repeating draft preparation for the same approved validation handoff is idempotent.
-- Partial unique index excludes legacy/header drafts that have no source case linkage.

CREATE UNIQUE INDEX IF NOT EXISTS uq_draft_quote_tenant_source_case
  ON draft_quote(tenant_id, source_exception_case_id)
  WHERE source_exception_case_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_draft_order_tenant_source_case
  ON draft_order(tenant_id, source_exception_case_id)
  WHERE source_exception_case_id IS NOT NULL;
