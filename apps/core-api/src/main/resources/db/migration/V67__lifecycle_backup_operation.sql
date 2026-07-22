-- P1-E2A Durable Backup Operation Control Slice.
--
-- A single durable, DEPLOYMENT-GLOBAL lifecycle-operation table for the bounded backup control slice.
-- It is intentionally NOT tenant-scoped: a backup is a platform/deployment operation, so there is no
-- tenant_id and no tenant/actor authority on the row. This migration is additive and non-destructive.
--
-- The row stores bounded, server-owned attribution only:
--   * public_id                 - opaque id exposed to clients; the UUID primary key is never exposed;
--   * operation_type            - fixed to BACKUP in this slice;
--   * state/result_code         - a closed, database-constrained state/result pair;
--   * idempotency_key_hash      - SHA-256 hex of the signed opaque idempotency intent;
--   * requested_by_fingerprint  - non-reversible control-principal fingerprint;
--   * leased_by_fingerprint     - non-reversible executor-principal fingerprint;
--   * fencing_token             - per-operation monotonic token assigned under a row lock at (re-)lease.
-- It stores no raw secret, raw principal, password, token, path, command line, environment, or customer
-- data.
--
-- Idempotency is enforced by a UNIQUE index on (operation_type, idempotency_key_hash). The lifecycle
-- consistency constraint prevents impossible partial rows such as a terminal state without a bounded
-- result, a queued row carrying lease authority, or an in-flight row without owner/token/expiry.
--
-- Deliberately no IF NOT EXISTS: Flyway must fail if an unexpected object already occupies these names.
-- Silently accepting a pre-existing incompatible table/index would turn schema drift into runtime risk.

CREATE TABLE lifecycle_operation (
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
  CONSTRAINT ck_lifecycle_operation_result_code
    CHECK (
      result_code IS NULL OR result_code IN (
        'BACKUP_COMPLETED',
        'BACKUP_FAILED_PREFLIGHT',
        'BACKUP_FAILED_EXECUTION',
        'BACKUP_TIMED_OUT'
      )
    ),
  CONSTRAINT ck_lifecycle_operation_attempt CHECK (attempt >= 0),
  CONSTRAINT ck_lifecycle_operation_fencing CHECK (fencing_token IS NULL OR fencing_token > 0),
  CONSTRAINT ck_lifecycle_operation_time_order CHECK (updated_at >= created_at),
  CONSTRAINT ck_lifecycle_operation_consistency CHECK (
    (
      state = 'QUEUED'
      AND result_code IS NULL
      AND attempt = 0
      AND fencing_token IS NULL
      AND lease_expires_at IS NULL
      AND leased_by_fingerprint IS NULL
    )
    OR
    (
      state IN ('LEASED', 'RUNNING')
      AND result_code IS NULL
      AND attempt > 0
      AND fencing_token > 0
      AND lease_expires_at IS NOT NULL
      AND leased_by_fingerprint IS NOT NULL
    )
    OR
    (
      state = 'SUCCEEDED'
      AND result_code = 'BACKUP_COMPLETED'
      AND attempt > 0
      AND fencing_token > 0
      AND lease_expires_at IS NOT NULL
      AND leased_by_fingerprint IS NOT NULL
    )
    OR
    (
      state = 'FAILED'
      AND result_code IN ('BACKUP_FAILED_PREFLIGHT', 'BACKUP_FAILED_EXECUTION', 'BACKUP_TIMED_OUT')
      AND attempt > 0
      AND fencing_token > 0
      AND lease_expires_at IS NOT NULL
      AND leased_by_fingerprint IS NOT NULL
    )
  )
);

CREATE UNIQUE INDEX ux_lifecycle_operation_public_id
  ON lifecycle_operation (public_id);

CREATE UNIQUE INDEX ux_lifecycle_operation_idempotency
  ON lifecycle_operation (operation_type, idempotency_key_hash);

CREATE INDEX idx_lifecycle_operation_state
  ON lifecycle_operation (state, created_at);
