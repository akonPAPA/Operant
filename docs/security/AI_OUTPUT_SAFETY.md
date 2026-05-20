# AI Output Safety

AI/provider output is untrusted input.

## Controls

- Stage 4 uses mock/rule-based providers only.
- Output is schema validated.
- Unsafe HTML/script markers are sanitized.
- Output is stored as advisory extraction data.
- Output cannot become SQL, code, or ERP payload.
- Output does not mutate product, customer, inventory, pricing, quote, or order tables.

## Forbidden

- No real LLM API keys.
- No direct AI writes to business tables.
- No autonomous quote/order/discount/substitute approval.
- No ERP/1C/accounting/warehouse writes.