-- OP-CAP-46B DB-level idempotency hardening for fulfillment signal replay.
-- The application-level check in OrderJourneyService.recordSignal() already prevents duplicate
-- signals when source_ref is provided (tenant + journey + source + type + sourceRef match).
-- This constraint duplicates that guard at the database layer for production (PostgreSQL),
-- ensuring that even concurrent or non-transactional signal replays cannot create duplicates.
--
-- Scope: NULL source_ref entries are NOT constrained (matching the application logic: without a
-- stable source reference, the service does not dedupe). Operator manual milestones
-- (OrderJourneyService.recordManualMilestone) intentionally use a NULL source_ref — each manual
-- action is a distinct, audited operator event, and the derived milestone projection is recomputed
-- idempotently, so manual replays never duplicate the business effect even though the signal/event
-- rows are append-only.
--
-- Convention: PostgreSQL partial unique index, identical in style to V39
-- (uq_draft_quote_tenant_source_case). This is NOT exercised by the H2 unit suite (Flyway is disabled
-- and ddl-auto=create-drop there); it is proven on real PostgreSQL by
-- FulfillmentSignalIdempotencyPostgresIntegrationTest.
--
-- Operational note: CREATE UNIQUE INDEX fails fast if the table already contains rows that violate the
-- constraint (pre-existing duplicates with the same key and a non-null source_ref). This migration
-- does NOT silently delete or rewrite historical rows; resolving any such duplicates is a deliberate
-- operational step, not an automatic data mutation.

CREATE UNIQUE INDEX IF NOT EXISTS ux_fulfillment_signal_idempotency
  ON fulfillment_signal(tenant_id, journey_id, source_type, signal_type, source_ref)
  WHERE source_ref IS NOT NULL;
