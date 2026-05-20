# Pricing, Discount and Margin Validation

Stage 5 validates commercial rules without changing the commercial mirror.

Pricing:

- Filters active `price_rule` rows by tenant, product, quantity, UOM, active date, customer account, and customer segment.
- Prefers customer-specific rules, then segment-level rules, then general rules.
- Uses priority as the deterministic tie-breaker.

Discount:

- Reads requested discount from extraction result data when available.
- Compares it with active `discount_rule` rows.
- Creates approval requirements when requested discount exceeds allowed thresholds.

Margin:

- Computes gross margin as `((unitPrice - unitCost) / unitPrice) * 100`.
- Uses product cost from the mirror and selected unit price.
- Creates issues and workflow approvals when margin is below configured guardrails.

Stage 5 does not update prices, discounts, costs, quotes, orders, invoices, or external systems.
