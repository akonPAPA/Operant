# Substitution Engine

The Stage 5 substitution engine is deterministic and conservative.

It creates substitute candidates when validation finds out-of-stock or insufficient-stock items, or when a substitute is required for a matched source product. Candidates come from `product_substitute`.

Ranking is intentionally simple for Core v1:

- lower risk first;
- available inventory signals when present;
- customer accepted preference when present in future data;
- margin pass when present in future data.

High-risk substitutes create `ApprovalRequirement` records. No substitute is auto-approved and no quote/order line is created in Stage 5.
