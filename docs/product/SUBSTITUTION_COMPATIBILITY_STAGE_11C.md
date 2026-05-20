# Stage 11C - Substitution + Compatibility Engine v1

## Objective

Stage 11C adds deterministic product substitution and compatibility suggestions for B2B auto and industrial parts workflows.

OrderPilot remains a secure transaction intelligence layer. Substitutions are suggestions for operator review, not autonomous quote/order conversion. This stage does not create connector commands, does not execute ERP/1C/accounting/warehouse writes, does not call a real AI provider, and does not let chatbot, AI, or frontend code write trusted business data directly.

## Deterministic Model

The engine uses existing tenant-owned catalog tables:

- `product_substitute` for product-to-product substitute relationships.
- `product_compatibility` for vehicle/equipment fitment context.
- `product_alias` and `oem_reference` for deterministic source product resolution.
- `customer_substitution_preference` for accepted aftermarket and blocked substitute rules.
- `inventory_snapshot` for available stock signals when present.

All lookups include `tenant_id`. Tenant data is never shared across substitution, compatibility, alias, OEM, or inventory checks.

## Substitute Types

Supported relation types:

- `EXACT_REPLACEMENT`
- `OEM_EQUIVALENT`
- `COMPATIBLE_ALTERNATIVE`
- `STOCK_PREFERRED`
- `MARGIN_PREFERRED`
- `CUSTOMER_ACCEPTED`
- `CUSTOMER_BLOCKED`

`MARGIN_PREFERRED` is future-safe in Stage 11C. It is not used for fake margin ranking unless margin data exists.

## Candidate Explanation

Each substitute candidate exposes:

- matched source
- relation type
- risk level
- compatibility match reason
- deterministic reason code
- available stock when known
- approval requirement
- customer accepted or blocked signal
- human-readable explanation

Risky or unverified compatibility requires approval. Blocked customer substitutes are marked as blocked and are not safe for automatic selection.

## RFQ Integration

RFQ-to-draft-quote keeps the Stage 11B product matching semantics and still preserves raw SKU/text. When the requested product is out of stock or unavailable, the draft quote line response can include substitution candidates.

Validation issue codes added for Stage 11C:

- `PRODUCT_OUT_OF_STOCK_SUBSTITUTE_AVAILABLE`
- `SUBSTITUTE_REQUIRES_APPROVAL`
- `SUBSTITUTE_BLOCKED_FOR_CUSTOMER`
- `COMPATIBILITY_UNVERIFIED`
- `NO_SAFE_SUBSTITUTE_FOUND`

Existing `INSUFFICIENT_STOCK` remains in place for compatibility with Stage 11B validation behavior.

## Demo Scenario

The demo fixtures include:

- Toyota Camry 2018 original brake pads.
- Two aftermarket substitute products.
- verified Camry 2018 compatibility rows.
- one customer accepted-aftermarket preference.
- one customer blocked substitute preference.
- stock variation where the original is unavailable, substitute A is available, and substitute B is low stock.

## Non-Goals

- no fuzzy AI ranking
- no autonomous substitute selection
- no quote/order conversion from a substitute without approval
- no external connector write
- no ERP/1C/accounting/warehouse write
- no real AI provider call
- no UI redesign
