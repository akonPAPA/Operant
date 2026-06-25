-- OP-CAP-46G Order Journey Tracking Link Revocation Foundation.
-- Adds the ability for an operator to explicitly revoke a secure customer tracking link before its
-- natural expiry. This is NOT a link-management portal, buyer portal, or login/session system, and it
-- performs NO external write and NO order-state / ETA / milestone mutation.
--
-- Security model:
--   * Revocation stores only safe metadata on the existing link row. No raw token is ever persisted
--     (only its SHA-256 hash already exists), and the reason is operator-only and bounded.
--   * A revoked link is denied on public resolution exactly like an expired or unknown one (the same
--     generic not-found response) so the customer cannot tell that a link existed or why it stopped.
--   * Revocation is identified by the internal link id within (tenant_id, journey_id) scope, never by
--     the raw token.
--
-- All columns are nullable and additive — existing rows remain valid (NULL revoked_at == not revoked).
-- Do not modify V60.

ALTER TABLE order_journey_tracking_link
  ADD COLUMN IF NOT EXISTS revoked_at        TIMESTAMPTZ NULL,
  ADD COLUMN IF NOT EXISTS revoked_by        UUID NULL,
  ADD COLUMN IF NOT EXISTS revocation_reason VARCHAR(280) NULL;
