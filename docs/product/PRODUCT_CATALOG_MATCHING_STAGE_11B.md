# Stage 11B - Product Catalog Matching Hardening

## Objective

Stage 11B hardens deterministic product matching so the RFQ to draft quote workflow can resolve tenant-owned quote lines to catalog products when a safe match exists.

Draft quotes remain internal OrderPilot state only. Matching does not create connector commands, does not execute connector sandbox runs, does not call ERP/1C/accounting/warehouse systems, and does not call a real AI provider.

## Matching Order

The catalog matcher evaluates one normalized code in this order:

1. Exact active product `normalized_sku` match within the current tenant.
2. Active customer-specific product alias match for the resolved customer.
3. Active global product alias match within the current tenant.
4. Active OEM reference match within the current tenant.

If more than one active product is found for the same tenant-scoped code, the result is `AMBIGUOUS` and requires operator review.

## Normalization

`ProductCodeNormalizer` is deterministic and intentionally narrow:

- trims input
- uppercases input
- collapses whitespace
- removes hyphen, underscore, spaces, and slash for code matching

Examples:

- `ab-1209` -> `AB1209`
- `AB-1209` -> `AB1209`
- `17801-0H050` -> `178010H050`
- `17801 / 0h050` -> `178010H050`

Raw RFQ text and raw SKU values are preserved on draft quote lines.

The deterministic validation path uses the same normalizer for extracted line item SKU, alias, and OEM-reference checks. Validation lookup must query tenant-scoped active products by `normalized_sku`, not by raw customer formatting.

## Tenant Isolation

Every lookup includes `tenantId`. Tenant A cannot resolve Tenant B product, alias, or OEM data into Tenant A draft quote lines.

Customer-specific aliases are preferred only for the matching customer. Customer-specific aliases do not leak to other customers; global aliases are used only when no matching customer-specific alias exists.

## Confidence Model

- `SKU_EXACT`: `1.00`
- `ALIAS_EXACT`: `0.95`
- `OEM_EXACT`: `0.90`
- `NO_MATCH`: `0.00`
- `AMBIGUOUS`: `0.40`

`NAME_TEXT_WEAK` is reserved for a later deterministic text fallback and is not used by the Stage 11B RFQ path.

## RFQ Integration

For each RFQ line, the draft quote service now:

- preserves `rawSku` and `rawText`
- stores the normalized product code on the draft quote line
- resolves product ID and product name when a deterministic match exists
- stores match confidence on the draft quote line
- keeps unresolved and ambiguous lines in `NEEDS_REVIEW`

Validation issue behavior:

- `PRODUCT_NOT_RESOLVED`: no active tenant-scoped product match
- `PRODUCT_MATCH_AMBIGUOUS`: multiple active tenant-scoped candidates
- `PRODUCT_MATCH_LOW_CONFIDENCE`: reserved for future weak text matching

## API Surface

The existing product API now exposes a read-only lookup:

`GET /api/v1/products/match?code=AB-1209&customerAccountId=<optional-uuid>`

The tenant comes from `TenantContext`; it is not accepted from the request body.

## Non-Goals

- no full substitution engine
- no vehicle compatibility graph expansion
- no fuzzy search dependency
- no OCR/PDF parser
- no real AI provider
- no real external API calls
- no real connector executor
- no ERP/1C/accounting/warehouse writes
- no production secrets
- no production SSO/OIDC
- no WhatsApp/Telegram/Meta production calls
- no UI redesign
- no customer-facing quote portal
- no payment, invoice, or tax engine
- no compensation execution

## Future Stage 11C

Stage 11C should build Substitution + Compatibility Engine v1:

- unavailable or out-of-stock product
- compatible substitute candidates
- customer accepted or blocked substitute rules
- stock and margin-aware ranking placeholder
- quote line suggestion
- human review before use
