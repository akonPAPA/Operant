-- P1-E2A Durable Backup Operation Control Slice.
--
-- A single durable, DEPLOYMENT-GLOBAL lifecycle-operation table for the bounded backup control slice. It
-- is intentionally NOT tenant-scoped: a backup is a platform/deployment operation, so there is no
-- tenant_id and no tenant/actor authority on the row. This migration is additive and non-destructive: it
-- only CREATEs one new table + indexes. It rewrites no existing table, drops nothing, and touches no
-- business order/quote/inventory/customer/price table and no existing support/control table.
--
-- The row stores bounded, server-owned attribution only:
--   * public_id                 - opaque id exposed to clients; the UUID primary key is never exposed;
--   * operation_type            - fixed to BACKUP in this slice (CHECK-constrained; no client-chosen type);
--   * state                     - QUEUED/LEASED/RUNNING/SUCCEEDED/FAILED (CHECK-constrained);
--   * idempotency_key_hash      - SHA-256 hex of the request Idempotency-Key (never the raw key);
--   * requested_by_fingerprint  - non-reversible SHA-256 fingerprint of the requesting control principal;
--   * leased_by_fingerprint     - non-reversible fingerprint of the leasing executor principal;
--   * result_code               - bounded terminal result code (never a raw log/stdout/stderr/path);
--   * fencing_token             - per-operation monotonic token assigned under a row lock at (re-)lease.
-- It stores NO raw secret, raw principal, password, token, path, command line, environment, or customer
-- data.
--
-- Idempotency is enforced at the database level by a UNIQUE index on (operation_type,
-- idempotency_key_hash): two concurrent requests reusing the same key can create at most one operation.

CREATE TABLE IF NOT EXISTS lifecycle_operation (
  id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  public_id                 VARCHAR(40) NOT NULL,
  operation_type            VARCHAR(20) NOT NULL,
  state                     VARCHAR(20) NOT NULL,
  idempotency_key_hash      VARCHAR(64) NOT NULL,
  requested_by_fingerprint  VARCHAR(64) NOT NULL,
  result_code               VARCHAR(40) NULL,
  attempt                   INTEGER NOT NULL DEFAULT 0,
  fencing_token             BIGINT NULL,
  lease_expires_at          TIMESTAMPTZ NULL,
  leased_by_fingerprint     VARCHAR(64) NULL,
  created_at                TIMESTAMPTZ NOT NULL,
  updated_at                TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_lifecycle_operation_type CHECK (operation_type = 'BACKUP'),
  CONSTRAINT ck_lifecycle_operation_state
    CHECK (state IN ('QUEUED', 'LEASED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
  CONSTRAINT ck_lifecycle_operation_attempt CHECK (attempt >= 0),
  CONSTRAINT ck_lifecycle_operation_fencing CHECK (fencing_token IS NULL OR fencing_token > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_lifecycle_operation_public_id
  ON lifecycle_operation (public_id);

CREATE UNIQUE INDEX IF NOT EXISTS ux_lifecycle_operation_idempotency
  ON lifecycle_operation (operation_type, idempotency_key_hash);

CREATE INDEX IF NOT EXISTS idx_lifecycle_operation_state
  ON lifecycle_operation (state, created_at);
