# Production Database Authority

Scope: production-like Docker deployment for the Core API database authority boundary.
This runbook does not start real `pg_dump` or `pg_restore` execution.

## Role Model

| Identity | Enters Core API? | Purpose | Authority |
| --- | --- | --- | --- |
| `operant_bootstrap` | No | PostgreSQL bootstrap and idempotent role provisioning | Creates fixed login roles and grants schema access |
| `operant_migrator` | No | One-shot Flyway migration | Owns migration-created objects and exits after migrate |
| `operant_runtime` | Yes | Long-lived Core API datasource | Runtime DML only; no owner, DDL, superuser, createdb, createrole or bypassrls |

Core API must run with `SPRING_FLYWAY_ENABLED=false` in production-like profiles.
The Compose topology enforces this with a one-shot `db-role-provisioner`, then a
one-shot `db-migrator`, then `core-api` using only `operant_runtime` credentials.

## Protected Table Privileges

| Table | Runtime allowed | Runtime denied |
| --- | --- | --- |
| `lifecycle_operation_audit` | `SELECT`, `INSERT`, required sequence usage | `UPDATE`, `DELETE`, `TRUNCATE`, ownership/DDL |
| `backup_artifact` | `SELECT`, `INSERT`, `UPDATE` for staged terminal transitions | `DELETE`, `TRUNCATE`, ownership/DDL |
| `lifecycle_operation` | `SELECT`, `INSERT`, `UPDATE` for lifecycle flow | `DELETE`, `TRUNCATE`, ownership/DDL |

The startup validator rejects a production-like Core API runtime datasource when
it is superuser, can create roles/databases, can bypass RLS, owns the database,
owns the active schema, owns protected lifecycle tables, has forbidden protected
DML, or runs with Flyway enabled.

## Existing Persistent Volumes

`infra/docker/postgres/provision-production-roles.sh` is idempotent and can run
against an existing PostgreSQL volume. It uses fixed server-owned role names and
psql variables for passwords. Do not pass migrator or bootstrap credentials to
`core-api`; they belong only to the provisioner and migrator containers.

If `V68__backup_artifact_authority.sql` was already applied to a non-disposable
shared or production database, do not edit its checksum or Flyway history. Stop
and design a forward reconciliation migration for any legacy successful backups.

## Legacy V67 Gate

V68 fails closed before creating `backup_artifact` or `lifecycle_operation_audit`
when V67 already contains a successful backup row:

`operation_type='BACKUP'`, `state='SUCCEEDED'`, `result_code='BACKUP_COMPLETED'`.

Failure reason: `V68_LEGACY_BACKUP_SUCCESS_REQUIRES_RECONCILIATION`.
No fake artifact is fabricated because there is no ciphertext digest, encrypted
size, encryption metadata, archive validation, or storage evidence.
