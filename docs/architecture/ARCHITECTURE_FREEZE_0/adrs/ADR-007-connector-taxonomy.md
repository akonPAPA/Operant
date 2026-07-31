# ADR-007 — Connector taxonomy

- **Status:** Proposed. Source commit `c11f7e8`.
- **Problem:** Risk of one generic `Connector` interface erasing authority/protocol/delivery/transactional/
  failure differences.
- **Current evidence:** `integration-connector-foundation.md` (business-system connectors, DRAFT+READ_ONLY
  default); channel adapters; AI/OCR worker; OIDC identity; payment obligation model — already distinct in
  code but not modelled as separate categories.
- **Options:** (a) one generic connector; (b) eight typed categories.
- **Decision:** (b) — Inbound Channel, Outbound Business Communication, Security Delivery (separate),
  Identity Providers, Business-System Connectors (read≠write), AI/Document Providers (advisory), Payment
  Providers, Connector Execution Runtime (Gateway + agent). See `06_CONNECTOR_TAXONOMY.mmd`.
- **Consequences:** each category has its own authority, transaction, and failure model; no capability leak.
- **Rejected alternatives:** generic connector (quality-gate failure: read/write conflation, authority erasure).
- **Migration impact:** typed category interfaces introduced incrementally; no external write enabled.
- **Security impact:** strong positive — read vs write capability never conflated; effects gated on APPROVED ChangeRequest.
- **Performance impact:** neutral.
- **Reversibility:** medium.
- **NOT_PROVEN:** Connector Gateway (P1-F), operant-agent (P1-G), payment matching, security delivery.
