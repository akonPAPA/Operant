-- PG-248-01 (found by the PR #248 PostgreSQL integration proof).
--
-- audit_event.actor_id was originally constrained (V1) as `REFERENCES user_account(id)`. That FK is too
-- narrow: an audit actor is a POLYMORPHIC / OPAQUE principal id that may identify any of several distinct
-- identity domains, not only a tenant user_account:
--   * tenant user (user_account);
--   * Operant staff/support principal (staff_user) — the case that exposed the defect;
--   * service account;
--   * connector / bot / worker;
--   * system / reaper / runtime job;
--   * a future BFF/SSO staff principal.
--
-- On real PostgreSQL the FK caused every support-plane audit write (SupportAccessService via
-- InternalSupportController records actor = staff_user.id) to fail `audit_event_actor_id_fkey`, aborting
-- both allowed and denied support-access audit events. H2 hid it (ddl-auto builds the schema from JPA
-- entities, where actor_id is a plain UUID column with no association, so the FK was never created).
--
-- Fix: drop ONLY that incorrect FK. The actor_id column, its data, and the audit_event indexes are all
-- preserved; audit rows are immutable and untouched. This does not remove or relax any other constraint.
-- Application code remains the source of actor provenance (RequestActorResolver for tenant users,
-- StaffIdentityResolver for staff). A first-class actor principal type/source column is a separate,
-- future hardening item (see docs/backlog/fix-notebook.md, PG-248-02) and is intentionally NOT added here.

ALTER TABLE audit_event
  DROP CONSTRAINT IF EXISTS audit_event_actor_id_fkey;

COMMENT ON COLUMN audit_event.actor_id IS
  'Opaque audit principal id. May identify a tenant user (user_account), an Operant staff user (staff_user), a service account, a connector/bot/worker, or a system/runtime actor depending on audit context. Intentionally NOT constrained by a foreign key to user_account because audit events span multiple identity domains. Immutable once written.';
