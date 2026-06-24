-- OP-CAP-46C Order Journey Secure Tracking Link Foundation.
-- A narrow, tokenized, tenant- and journey-scoped, expiring, READ-ONLY secure tracking link that
-- resolves to exactly one customer-safe order-journey tracking view. This is NOT a buyer portal,
-- NOT a login/session system, NOT a WMS/TMS/carrier integration, and performs NO external writes.
--
-- Security model:
--   * Only the SHA-256 hash of the token is persisted (token_hash). The raw token is shown to the
--     operator exactly once at creation (so the link can be shared) and is never stored or logged.
--   * Scope (tenant_id, journey_id) is resolved FROM the token row, never from the request. A token
--     minted for tenant/journey A can never resolve tenant/journey B.
--   * expires_at is enforced deterministically; an expired (or unknown) token is denied.
--   * Resolving a link never mutates order_journey / order_journey_milestone / fulfillment_signal;
--     it only emits an audit access event.

CREATE TABLE IF NOT EXISTS order_journey_tracking_link (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     UUID NOT NULL REFERENCES tenant(id),
  journey_id    UUID NOT NULL REFERENCES order_journey(id),
  token_hash    VARCHAR(64) NOT NULL,
  expires_at    TIMESTAMPTZ NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL,
  created_by    UUID NULL,
  CONSTRAINT ux_order_journey_tracking_link_token UNIQUE (token_hash)
);

-- Lookups: by token hash (resolve path, covered by the unique constraint) and by journey (operator
-- listing / management within a tenant).
CREATE INDEX IF NOT EXISTS idx_order_journey_tracking_link_tenant_journey
  ON order_journey_tracking_link(tenant_id, journey_id, created_at DESC);
