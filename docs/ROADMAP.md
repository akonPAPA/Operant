# Roadmap

## Current Phase: Phase 4 AI-Assisted Understanding

OrderPilot Core v1 now includes the safe skeleton for provider-agnostic AI/document understanding:

- Tenant-scoped extraction runs for inbound documents and channel messages.
- Mock text extraction and mock semantic extraction providers.
- Strict schema validation for provider output.
- Advisory extraction results, fields, line items, suggestions, source evidence, confidence signals, and processing status.
- Low-confidence routing to `NEEDS_REVIEW`.
- Read-only inspection APIs for extraction results by run and by source.

## Guardrails

- AI is advisory only.
- Deterministic validation owns the final business decision.
- AI output must not directly write master business data.
- Human approval is required for risky actions and external writes.
- Prompt-injection content is treated as untrusted customer text.

## Next Recommended Phase

Phase 5 should connect advisory extraction results to deterministic validation and operator review:

- Validate extracted customers, products, quantities, UOM, inventory, pricing, discounts, margins, substitutions, and requested dates.
- Produce structured validation issues and suggested fixes.
- Route ambiguous or risky cases to human review.
- Keep quote/order creation behind typed backend services, validation, audit, and approval gates.
