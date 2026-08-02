# ADR-006 — Work Management ownership

- **Status:** Proposed. Source commit `c11f7e8`.
- **Problem:** `workspace` domain (34 files) risks becoming a second owner of Quote/Order business state.
- **Current evidence:** `DraftReviewService`, `OperatorActionService`, exception cockpit, draft review
  queue — all operator-review UX that currently sits beside Quote/Order entities in one package.
- **Options:** (a) Work Management owns copies of Quote/Order; (b) Work Management orchestrates review UX
  and issues commands to `quote`/`ordermanagement`, owning only WorkCase/review state.
- **Decision:** (b). `workmanagement` owns `WorkCase`/`ExceptionCase` and the review contract; it never
  stores authoritative Quote/Order/Payment/Inventory. Corrections call the owning module's command API.
- **Consequences:** no duplicated business state; single source of truth preserved (quality gate).
- **Rejected alternatives:** (a) — duplicate business state, explicit quality-gate failure.
- **Migration impact:** Wave 2 SPLIT of `workspace` into quote/ordermanagement/workmanagement.
- **Security impact:** positive — REVIEW_* permissions stay distinct from QUOTE_*/APPROVE_*.
- **Performance impact:** neutral.
- **Reversibility:** medium.
- **NOT_PROVEN:** exact boundary of shared DraftQuote/DraftOrder entities pending split.
