# Pilot Metrics Specification

## Purpose

Pilot metrics must show whether OrderPilot reduces operator work safely before any production action is enabled.

Metrics are measured per tenant and per design partner. Where possible, split by source channel, document/message type, customer segment, and product/category.

## Core Metrics

| Metric | Definition | Suggested calculation | Use |
| --- | --- | --- | --- |
| Document/message volume | Count of pilot-approved intake items processed in shadow mode. | `count(intake items)` by day/week/channel/type. | Establish sample size and workload coverage. |
| Automation candidate rate | Share of items that look safe for partial or full automation after review. | `automation_candidate_count / processed_count`. | Estimate future automation potential. |
| Extraction confidence | Confidence from advisory extraction at document, field, and line-item level. | Average, median, p10/p90 by field/type. | Tune thresholds and identify weak extraction areas. |
| Validation issue rate | Share of validation runs with at least one issue. | `runs_with_issues / validation_runs`. | Measure data quality and business-rule friction. |
| Human correction rate | Share of predictions requiring correction. | `corrected_predictions / reviewed_predictions`. | Primary readiness metric for safe automation. |
| Exception category distribution | Count/share of exception categories assigned by validation or human review. | Group by category and severity. | Prioritize product and data cleanup work. |
| Cycle time before/after | Time spent in the current process versus shadow-mode assisted process. | Baseline minutes per item vs pilot minutes per item. | Estimate operational improvement. |
| Quote/order draft readiness rate | Share of validated items that can become internal drafts without unresolved high-risk issues. | `draft_ready_items / reviewed_items`. | Assess readiness for controlled internal workflow. |
| Substitution acceptance rate | Share of suggested substitutes accepted by humans. | `accepted_substitutes / reviewed_substitute_candidates`. | Measure substitution engine value and risk. |
| Discount/margin issue rate | Share of items with discount or margin warnings/approvals. | `items_with_discount_or_margin_issue / reviewed_items`. | Estimate commercial control burden. |
| Estimated labor time saved | Human minutes avoided or reduced through assisted processing. | `(baseline_minutes - pilot_minutes) * processed_count`. | Feed ROI model. |

## Human Correction Rate Detail

Track correction rate by:

- Intent.
- Document/message type.
- Customer match.
- Product/SKU/OEM/alias match.
- Quantity.
- UOM.
- Date.
- Price.
- Discount.
- Currency.
- Substitute candidate.
- Validation issue.
- Exception category.
- Draft readiness.

Minimum reporting:

```text
field_name
predicted_value
human_value
correction_type
severity
source_evidence
reviewer_id
reviewed_at
```

## Exception Categories

Initial categories:

- Unknown customer.
- Ambiguous customer identity.
- Unknown product.
- Ambiguous product match.
- UOM mismatch.
- Quantity ambiguity.
- Inventory shortfall.
- Price missing.
- Discount requires approval.
- Margin below threshold.
- Compatibility concern.
- Substitute candidate requires approval.
- Prompt injection or hostile input.
- Unsupported document type.
- External-write request.
- Manual-only workflow.

## ROI Report Fields

Each client ROI report should include:

- Client name and tenant id.
- Pilot date range.
- Channels included.
- Document/message types included.
- Documents/messages processed.
- Automation candidate rate.
- Average extraction confidence.
- Human correction rate by category.
- Validation issue rate.
- Top exception categories.
- Baseline cycle time.
- Shadow-mode cycle time.
- Estimated minutes saved.
- Estimated labor cost saved.
- Draft quote/order readiness rate.
- Substitution acceptance rate.
- Discount/margin issue rate.
- Safety incidents.
- External writes attempted or blocked.
- Recommended next stage.
- Remaining blockers.

## Measurement Boundaries

- A pilot item is counted only after it is reviewed or explicitly marked not reviewable.
- Human correction is authoritative even if OrderPilot confidence is high.
- ROI should separate time saved from revenue uplift unless revenue impact is measured directly.
- Do not claim production automation from shadow-mode metrics alone.
- Do not use metrics to enable external writes without a later controlled-write stage.
