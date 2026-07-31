# ADR-008 — Communication vs security delivery

- **Status:** Proposed. Source commit `c11f7e8`.
- **Problem:** Business messaging and MFA/security messaging must not share providers, pools, or audit.
- **Current evidence:** OIDC session flow exists (P1-C); bot response drafts exist; **no MFA delivery** today.
- **Options:** (a) one delivery service for all messages; (b) two owners — `communicationdelivery` (business)
  and `securitydelivery` (MFA/security), physically separable.
- **Decision:** (b). Security delivery uses a separate provider/pool and separate audit; business delivery
  is driven by the business outbox.
- **Consequences:** a compromised/rate-limited business messaging provider cannot block MFA; distinct blast radii.
- **Rejected alternatives:** (a) — mixing business and security messaging (quality-gate failure).
- **Migration impact:** Wave 5 introduces `securitydelivery`; business delivery wires the outbox relay.
- **Security impact:** strong positive.
- **Performance impact:** positive (security path isolated from bulk business messaging).
- **Reversibility:** medium.
- **NOT_PROVEN:** MFA provider choice (owner decision); no security delivery implemented yet.
