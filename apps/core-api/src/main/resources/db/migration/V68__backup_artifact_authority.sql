-- P1-E2B-02 Durable backup artifact authority and deployment-global lifecycle audit.
-- PostgreSQL rows are the authority for artifact state; filesystem presence alone is not.

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM lifecycle_operation
    WHERE operation_type = 'BACKUP'
      AND state = 'SUCCEEDED'
      AND result_code = 'BACKUP_COMPLETED'
  ) THEN
    RAISE EXCEPTION 'V68_LEGACY_BACKUP_SUCCESS_REQUIRES_RECONCILIATION'
      USING ERRCODE = 'P0001';
  END IF;
END;
$$;

CREATE TABLE backup_artifact (
  id                            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  public_handle                 VARCHAR(48) NOT NULL,
  lifecycle_operation_id        UUID NOT NULL,
  state                         VARCHAR(20) NOT NULL,
  backup_format                 VARCHAR(40) NOT NULL,
  encryption_algorithm          VARCHAR(40) NULL,
  encryption_envelope_version   VARCHAR(40) NULL,
  encryption_key_identifier     VARCHAR(80) NULL,
  created_at                    TIMESTAMPTZ NOT NULL,
  updated_at                    TIMESTAMPTZ NOT NULL,
  available_at                  TIMESTAMPTZ NULL,
  postgres_server_version       VARCHAR(80) NULL,
  pg_dump_version               VARCHAR(80) NULL,
  pg_restore_version            VARCHAR(80) NULL,
  schema_version                VARCHAR(40) NULL,
  encrypted_byte_size           BIGINT NULL,
  ciphertext_sha256             VARCHAR(64) NULL,
  archive_validated             BOOLEAN NULL,
  archive_entry_count           INTEGER NULL,
  storage_key                   VARCHAR(256) NOT NULL,
  execution_attempt             INTEGER NOT NULL,
  fencing_token                 BIGINT NOT NULL,
  failure_code                  VARCHAR(80) NULL,
  CONSTRAINT fk_backup_artifact_lifecycle_operation
    FOREIGN KEY (lifecycle_operation_id) REFERENCES lifecycle_operation(id),
  CONSTRAINT ck_backup_artifact_state
    CHECK (state IN ('STAGED', 'AVAILABLE', 'REJECTED', 'ORPHANED')),
  CONSTRAINT ck_backup_artifact_public_handle CHECK (
    public_handle ~ '^ba_[0-9a-f]{24}$'
  ),
  CONSTRAINT ck_backup_artifact_format CHECK (backup_format = 'POSTGRES_CUSTOM'),
  CONSTRAINT ck_backup_artifact_execution_identity CHECK (
    execution_attempt > 0 AND fencing_token > 0
  ),
  CONSTRAINT ck_backup_artifact_time_order CHECK (updated_at >= created_at),
  CONSTRAINT ck_backup_artifact_ciphertext_sha256 CHECK (
    ciphertext_sha256 IS NULL OR ciphertext_sha256 ~ '^[0-9a-f]{64}$'
  ),
  CONSTRAINT ck_backup_artifact_storage_key CHECK (
    length(storage_key) BETWEEN 1 AND 256
    AND storage_key !~ '(^/|^[A-Za-z]:|\\|(^|/)\.\.(/|$))'
  ),
  CONSTRAINT ck_backup_artifact_failure_code CHECK (
    failure_code IS NULL OR failure_code IN (
      'BACKUP_FAILED_PREFLIGHT',
      'BACKUP_FAILED_EXECUTION',
      'BACKUP_TIMED_OUT',
      'EXPIRED_LEASE_REPLACED'
    )
  ),
  CONSTRAINT ck_backup_artifact_closed_encryption CHECK (
    encryption_algorithm IS NULL OR encryption_algorithm = 'AES-256-GCM'
  ),
  CONSTRAINT ck_backup_artifact_closed_envelope CHECK (
    encryption_envelope_version IS NULL OR encryption_envelope_version = 'v1'
  ),
  CONSTRAINT ck_backup_artifact_key_reference CHECK (
    encryption_key_identifier IS NULL OR encryption_key_identifier ~ '^[0-9A-Za-z:_-]{1,80}$'
  ),
  -- Canonical restore provenance. Identical to the Java Patterns
  -- BackupArtifact.CANONICAL_TOOL_VERSION_REGEX / CANONICAL_SCHEMA_VERSION_REGEX. Stored provenance has
  -- exactly one representation; raw tool banners ('PostgreSQL 16', 'pg_dump 16', 'v16', '16.') are rejected.
  CONSTRAINT ck_backup_artifact_pg_versions CHECK (
    (postgres_server_version IS NULL OR postgres_server_version ~ '^[0-9]{1,3}(\.[0-9]{1,3}){0,2}$')
    AND (pg_dump_version IS NULL OR pg_dump_version ~ '^[0-9]{1,3}(\.[0-9]{1,3}){0,2}$')
    AND (pg_restore_version IS NULL OR pg_restore_version ~ '^[0-9]{1,3}(\.[0-9]{1,3}){0,2}$')
  ),
  CONSTRAINT ck_backup_artifact_schema_version CHECK (
    schema_version IS NULL OR schema_version ~ '^V[0-9]{1,6}(\.[0-9]{1,6}){0,3}$'
  ),
  CONSTRAINT ck_backup_artifact_state_specific CHECK (
    (
      state = 'STAGED'
      AND available_at IS NULL
      AND encryption_algorithm IS NULL
      AND encryption_envelope_version IS NULL
      AND encryption_key_identifier IS NULL
      AND postgres_server_version IS NULL
      AND pg_dump_version IS NULL
      AND pg_restore_version IS NULL
      AND schema_version IS NULL
      AND encrypted_byte_size IS NULL
      AND ciphertext_sha256 IS NULL
      AND archive_validated IS NULL
      AND archive_entry_count IS NULL
      AND failure_code IS NULL
    )
    OR
    (
      state = 'AVAILABLE'
      AND available_at IS NOT NULL
      AND available_at >= created_at
      AND encryption_algorithm IS NOT NULL
      AND encryption_algorithm = 'AES-256-GCM'
      AND encryption_envelope_version IS NOT NULL
      AND encryption_envelope_version = 'v1'
      AND encryption_key_identifier IS NOT NULL
      AND encryption_key_identifier ~ '^[0-9A-Za-z:_-]{1,80}$'
      AND postgres_server_version IS NOT NULL
      AND postgres_server_version ~ '^[0-9]{1,3}(\.[0-9]{1,3}){0,2}$'
      AND pg_dump_version IS NOT NULL
      AND pg_dump_version ~ '^[0-9]{1,3}(\.[0-9]{1,3}){0,2}$'
      AND pg_restore_version IS NOT NULL
      AND pg_restore_version ~ '^[0-9]{1,3}(\.[0-9]{1,3}){0,2}$'
      AND schema_version IS NOT NULL
      AND schema_version ~ '^V[0-9]{1,6}(\.[0-9]{1,6}){0,3}$'
      AND encrypted_byte_size IS NOT NULL
      AND encrypted_byte_size > 0
      AND ciphertext_sha256 IS NOT NULL
      AND ciphertext_sha256 ~ '^[0-9a-f]{64}$'
      AND archive_validated IS TRUE
      AND archive_entry_count IS NOT NULL
      AND archive_entry_count > 0
      AND failure_code IS NULL
    )
    OR
    (
      state IN ('REJECTED', 'ORPHANED')
      AND available_at IS NULL
      AND encryption_algorithm IS NULL
      AND encryption_envelope_version IS NULL
      AND encryption_key_identifier IS NULL
      AND postgres_server_version IS NULL
      AND pg_dump_version IS NULL
      AND pg_restore_version IS NULL
      AND schema_version IS NULL
      AND encrypted_byte_size IS NULL
      AND ciphertext_sha256 IS NULL
      AND archive_validated IS NULL
      AND archive_entry_count IS NULL
      AND failure_code IS NOT NULL
    )
  )
);

CREATE UNIQUE INDEX ux_backup_artifact_public_handle
  ON backup_artifact (public_handle);

CREATE UNIQUE INDEX ux_backup_artifact_storage_key
  ON backup_artifact (storage_key);

CREATE UNIQUE INDEX ux_backup_artifact_execution_identity
  ON backup_artifact (lifecycle_operation_id, execution_attempt, fencing_token);

CREATE UNIQUE INDEX ux_backup_artifact_identity_fk
  ON backup_artifact (id, lifecycle_operation_id);

CREATE UNIQUE INDEX ux_backup_artifact_one_available_per_operation
  ON backup_artifact (lifecycle_operation_id)
  WHERE state = 'AVAILABLE';

CREATE INDEX idx_backup_artifact_lifecycle_operation
  ON backup_artifact (lifecycle_operation_id);

CREATE INDEX idx_backup_artifact_state_created
  ON backup_artifact (state, created_at);

CREATE TABLE lifecycle_operation_audit (
  id                       BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  lifecycle_operation_id   UUID NOT NULL,
  backup_artifact_id       UUID NULL,
  event_type               VARCHAR(80) NOT NULL,
  principal_type           VARCHAR(30) NOT NULL,
  principal_fingerprint    VARCHAR(64) NOT NULL,
  result_code              VARCHAR(80) NULL,
  metadata                 JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at               TIMESTAMPTZ NOT NULL,
  CONSTRAINT fk_lifecycle_operation_audit_operation
    FOREIGN KEY (lifecycle_operation_id) REFERENCES lifecycle_operation(id),
  CONSTRAINT fk_lifecycle_operation_audit_artifact
    FOREIGN KEY (backup_artifact_id) REFERENCES backup_artifact(id),
  CONSTRAINT fk_lifecycle_operation_audit_artifact_operation
    FOREIGN KEY (backup_artifact_id, lifecycle_operation_id)
    REFERENCES backup_artifact(id, lifecycle_operation_id),
  CONSTRAINT ck_lifecycle_operation_audit_event_type
    CHECK (event_type IN (
      'BACKUP_REQUESTED',
      'BACKUP_IDEMPOTENCY_REPLAYED',
      'BACKUP_LEASE_ACQUIRED',
      'BACKUP_EXECUTION_STARTED',
      'BACKUP_LEASE_LOST',
      'BACKUP_PROCESS_FAILED',
      'BACKUP_PROCESS_TIMED_OUT',
      'BACKUP_ARCHIVE_VALIDATED',
      'BACKUP_ARCHIVE_VALIDATION_FAILED',
      'BACKUP_ENCRYPTED',
      'BACKUP_ARTIFACT_STAGED',
      'BACKUP_ARTIFACT_AVAILABLE',
      'BACKUP_ARTIFACT_REJECTED',
      'BACKUP_ARTIFACT_ORPHANED',
      'BACKUP_STALE_EXECUTOR_DENIED',
      'BACKUP_WRONG_EXECUTOR_DENIED',
      'BACKUP_EXPIRED_LEASE_DENIED',
      'BACKUP_SUCCEEDED',
      'BACKUP_FAILED'
    )),
  CONSTRAINT ck_lifecycle_operation_audit_principal_type
    CHECK (principal_type IN ('STAFF', 'EXECUTOR', 'SYSTEM')),
  CONSTRAINT ck_lifecycle_operation_audit_principal_fingerprint
    CHECK (principal_fingerprint ~ '^[0-9A-Za-z:_-]{1,64}$'),
  CONSTRAINT ck_lifecycle_operation_audit_result_code
    CHECK (result_code IS NULL OR result_code IN (
      'BACKUP_COMPLETED',
      'BACKUP_FAILED_PREFLIGHT',
      'BACKUP_FAILED_EXECUTION',
      'BACKUP_TIMED_OUT',
      'EXPIRED_LEASE_REPLACED',
      'STALE_FENCING_TOKEN',
      'LIFECYCLE_LEASE_OWNER_MISMATCH',
      'LIFECYCLE_LEASE_EXPIRED'
    )),
  CONSTRAINT ck_lifecycle_operation_audit_metadata_bound
    CHECK (length(metadata::text) <= 2048),
  CONSTRAINT ck_lifecycle_operation_audit_artifact_contract CHECK (
    (event_type IN (
      'BACKUP_ARTIFACT_STAGED',
      'BACKUP_ARTIFACT_AVAILABLE',
      'BACKUP_ARTIFACT_REJECTED',
      'BACKUP_ARTIFACT_ORPHANED'
    ) AND backup_artifact_id IS NOT NULL)
    OR
    (event_type NOT IN (
      'BACKUP_ARTIFACT_STAGED',
      'BACKUP_ARTIFACT_AVAILABLE',
      'BACKUP_ARTIFACT_REJECTED',
      'BACKUP_ARTIFACT_ORPHANED'
    ) AND backup_artifact_id IS NULL)
  ),
  CONSTRAINT ck_lifecycle_operation_audit_principal_contract CHECK (
    (event_type = 'BACKUP_REQUESTED' AND principal_type = 'STAFF')
    OR (event_type = 'BACKUP_ARTIFACT_ORPHANED' AND principal_type = 'SYSTEM')
    OR (event_type <> 'BACKUP_REQUESTED' AND event_type <> 'BACKUP_ARTIFACT_ORPHANED' AND principal_type = 'EXECUTOR')
  ),
  CONSTRAINT ck_lifecycle_operation_audit_result_contract CHECK (
    (event_type = 'BACKUP_SUCCEEDED' AND result_code = 'BACKUP_COMPLETED')
    OR (event_type = 'BACKUP_FAILED' AND result_code IN ('BACKUP_FAILED_PREFLIGHT', 'BACKUP_FAILED_EXECUTION', 'BACKUP_TIMED_OUT'))
    OR (event_type = 'BACKUP_ARTIFACT_REJECTED' AND result_code IN ('BACKUP_FAILED_PREFLIGHT', 'BACKUP_FAILED_EXECUTION', 'BACKUP_TIMED_OUT'))
    OR (event_type = 'BACKUP_ARTIFACT_ORPHANED' AND result_code = 'EXPIRED_LEASE_REPLACED')
    OR (event_type = 'BACKUP_STALE_EXECUTOR_DENIED' AND result_code = 'STALE_FENCING_TOKEN')
    OR (event_type = 'BACKUP_WRONG_EXECUTOR_DENIED' AND result_code = 'LIFECYCLE_LEASE_OWNER_MISMATCH')
    OR (event_type = 'BACKUP_EXPIRED_LEASE_DENIED' AND result_code = 'LIFECYCLE_LEASE_EXPIRED')
    OR (event_type NOT IN (
      'BACKUP_SUCCEEDED',
      'BACKUP_FAILED',
      'BACKUP_ARTIFACT_REJECTED',
      'BACKUP_ARTIFACT_ORPHANED',
      'BACKUP_STALE_EXECUTOR_DENIED',
      'BACKUP_WRONG_EXECUTOR_DENIED',
      'BACKUP_EXPIRED_LEASE_DENIED'
    ) AND result_code IS NULL)
  )
);

CREATE INDEX idx_lifecycle_operation_audit_operation_order
  ON lifecycle_operation_audit (lifecycle_operation_id, created_at DESC, id DESC);

CREATE INDEX idx_lifecycle_operation_audit_artifact_order
  ON lifecycle_operation_audit (backup_artifact_id, created_at DESC, id DESC)
  WHERE backup_artifact_id IS NOT NULL;

-- Terminal artifact states are immutable history. Only STAGED may transition.
-- Whole-row comparison (NEW IS DISTINCT FROM OLD) is future-safe: it also protects created_at,
-- updated_at, and any column added to backup_artifact later without editing this trigger. An exact
-- no-op UPDATE is still permitted because NEW is not distinct from OLD.
CREATE OR REPLACE FUNCTION backup_artifact_forbid_terminal_rewrite()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF OLD.state IN ('AVAILABLE', 'REJECTED', 'ORPHANED')
     AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION 'BACKUP_ARTIFACT_TERMINAL_IMMUTABLE'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_backup_artifact_forbid_terminal_rewrite
  BEFORE UPDATE ON backup_artifact
  FOR EACH ROW
  EXECUTE FUNCTION backup_artifact_forbid_terminal_rewrite();

-- Fix D: lifecycle_operation_audit is durable security evidence. Enforce append-only at the database
-- layer (INSERT and SELECT only). JPA @Immutable and the repository's insert/read-only method surface
-- are application-level intent; this BEFORE UPDATE OR DELETE trigger is the authoritative guard. There
-- is intentionally NO request/session/HTTP/support bypass. Isolated Testcontainers cleanup uses an
-- explicit test-only TRUNCATE strategy (which does not fire row-level triggers), never a production
-- repository delete API. Long-term retention/archival is out of scope for this migration.
CREATE OR REPLACE FUNCTION lifecycle_operation_audit_append_only()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'LIFECYCLE_OPERATION_AUDIT_APPEND_ONLY'
    USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER trg_lifecycle_operation_audit_append_only
  BEFORE UPDATE OR DELETE ON lifecycle_operation_audit
  FOR EACH ROW
  EXECUTE FUNCTION lifecycle_operation_audit_append_only();

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'operant_runtime') THEN
    GRANT SELECT, INSERT ON lifecycle_operation_audit TO operant_runtime;
    GRANT USAGE, SELECT ON SEQUENCE lifecycle_operation_audit_id_seq TO operant_runtime;
    REVOKE UPDATE, DELETE, TRUNCATE ON lifecycle_operation_audit FROM operant_runtime;

    GRANT SELECT, INSERT, UPDATE ON backup_artifact TO operant_runtime;
    REVOKE DELETE, TRUNCATE ON backup_artifact FROM operant_runtime;

    GRANT SELECT, INSERT, UPDATE ON lifecycle_operation TO operant_runtime;
    REVOKE DELETE, TRUNCATE ON lifecycle_operation FROM operant_runtime;
  END IF;
END;
$$;
