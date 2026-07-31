# ADR-002 — Canonical intake pipeline

- **Status:** Proposed. Source commit `c11f7e8`.
- **Problem:** Multiple inbound entry points (`ChannelWebhookController`, `WebhookController`,
  `BotTelegramWebhookController`, `ChannelGatewayController`) plus multiple RFQ handoff controllers
  duplicate provider-specific intake logic (findings D1/D2).
- **Current evidence:** `intake-architecture.md` already defines a controlled mirror (InboundDocument,
  ChannelMessage, InboundEventLedger, WebhookEvent, ProcessingJob) — the pipeline exists but is fronted by
  several controllers.
- **Options:** (a) per-provider flows; (b) one canonical pipeline behind category-typed adapters.
- **Decision:** (b) — adapters emit a canonical inbound envelope; a single pipeline does auth/signature →
  replay → connection/tenant resolution → limits → admission → envelope → dedup → evidence → ack →
  classify → route. Adapters may **never** create Quote/Order/PaymentAllocation/Approval/ChangeRequest.
- **Consequences:** one place for security/dedup/backpressure; new channels are thin adapters.
- **Rejected alternatives:** per-provider flows (duplication, inconsistent security).
- **Migration impact:** Wave 3 MERGE; legacy webhook paths kept as thin adapters until removed.
- **Security impact:** strong positive — one hardened boundary; fail-closed preserved.
- **Performance impact:** positive — fast-ack path, heavy work async.
- **Reversibility:** medium.
- **NOT_PROVEN:** WhatsApp/marketplace/buyer-portal adapters (Telegram/email/API exist today).
