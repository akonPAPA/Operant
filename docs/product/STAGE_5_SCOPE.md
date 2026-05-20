# Stage 5 Scope - Validation, Substitution and Pricing Intelligence

Stage 5 turns advisory extraction output into deterministic validation workflow records.

In scope:

- Customer account matching from extracted hints.
- Product matching by SKU, product alias, OEM reference, and conservative description fallback.
- Unit-of-measure normalization.
- Inventory availability checks from the latest tenant mirror snapshots.
- Price rule selection.
- Discount and margin guardrail checks.
- Compatibility checks where extracted equipment context exists.
- Substitute candidate generation from configured product substitutes.
- Validation issues and approval requirements for operator review.

Out of scope:

- Final quote/order creation.
- ERP, 1C, accounting, warehouse, or external business-system writes.
- Autonomous approval.
- Telegram/WhatsApp business replies.
- Production payment or invoice logic.
- Advanced ML ranking or real AI provider calls.
- ChangeRequest execution.
