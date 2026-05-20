# AI and Bot Governance

## Governing Rule

AI is advisory, not authoritative. Bot Runtime Lite is intake and workflow assistance only. Neither AI nor chatbot nor frontend code may directly write to master business data or external ERP systems.

## Bot Runtime Lite Boundaries

- Bot Runtime Lite cannot approve quotes.
- Bot Runtime Lite cannot approve or create final orders.
- Bot Runtime Lite cannot update inventory.
- Bot Runtime Lite cannot update prices, discounts, margins, or customers.
- Bot Runtime Lite cannot write to ERP, accounting, warehouse, or payment systems.
- External Telegram chat IDs and message IDs are stored for correlation and replay detection, but they are not trusted identity.

## AI Output Rules

- AI output cannot become an ERP payload without deterministic validation, operator approval where required, and audit.
- AI output cannot bypass Stage 5 validation or Stage 6 internal workflow controls.
- AI suggestions remain evidence or recommendations until accepted through backend application services.
- Customer messages and documents are hostile input and may include prompt injection.

## Required Controls for Risky Actions

All risky actions require:
- backend validation;
- tenant isolation;
- policy checks;
- transaction boundaries;
- approval gates where needed;
- audit events;
- clear source evidence.

## Stage 9 Position

Current Stage 9 hardening verifies the advisory boundary through tests and documentation. Real LLM calls, real Telegram API calls, and external ERP writes remain out of scope.
