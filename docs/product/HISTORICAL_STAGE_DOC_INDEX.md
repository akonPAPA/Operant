# Historical Stage Document Index

## 1. Status

- Canonical Stage-Source Freeze: PASS
- Product stage status: PARTIAL
- Product capability freeze: ACTIVE
- This index preserves historical/status context without making those docs authoritative.

## 2. Current canonical sources

| Role | Path | Authority |
| --- | --- | --- |
| Current-stage pointer | `docs/product/current-stage.md` | `CANONICAL_POINTER` |
| Detailed evidence | `docs/product/STAGE_STATUS_RECONCILIATION.md` | `DETAILED_EVIDENCE` |
| Dirty worktree attribution | `docs/product/DIRTY_WORKTREE_ATTRIBUTION_PLAN.md` | `DIRTY_WORKTREE_EVIDENCE` |
| Taxonomy | `docs/product/CANONICAL_STAGE_TAXONOMY.md` | `GOVERNANCE_TAXONOMY` |
| Historical index | `docs/product/HISTORICAL_STAGE_DOC_INDEX.md` | `HISTORICAL_INDEX` |

## 3. Historical / superseded / context documents

| Path | Current classification | Why non-canonical / notes | Safe future action |
| --- | --- | --- | --- |
| `docs/ROADMAP.md` | `ROADMAP_GUARDRAIL` | It says the current phase is Stage 10, but `docs/product/current-stage.md` now points to reconciliation evidence and current product status is `PARTIAL`. | Preserve as roadmap/guardrail context; keep pointer banner; do not use as current-stage authority. |
| `README.md` | `ROADMAP_GUARDRAIL` | It says the current backend milestone is Stage 11E, which conflicts with the canonical pointer/evidence model. | Preserve quick-start and guardrail content; keep pointer banner; do not use as current-stage authority. |
| `PROJECT_STATUS_CHECKPOINT.txt` | `HISTORICAL_EVIDENCE` | It records a 2026-05-22 Stage 13 restored/stable checkpoint and "move next" wording, not current authority. | Preserve as historical checkpoint; do not use as current-stage authority. |
| `ORDERPILOT_CORE_V1_AI_DEV.md` | `ROADMAP_GUARDRAIL` | It remains useful as product thesis and architecture guardrail context, but its stage language includes older "Current" roadmap statements. | Preserve as roadmap/guardrail context; do not use as current-stage authority unless canonical pointer promotes it. |
| `docs/product/PRODUCT_CATALOG_MATCHING_STAGE_11B.md` | `HISTORICAL_EVIDENCE` | It documents normalized SKU matching behavior and is referenced by reconciliation as prior validation-slice evidence, not current stage authority. | Review with reconciliation evidence group; keep separate from frontend/demo staging. |
| `docs/product/RFQ_TO_DRAFT_QUOTE_WORKFLOW_STAGE_11A.md` | `HISTORICAL_EVIDENCE` | It is a Stage 11A workflow document with demo text alignment, not current stage authority. | Preserve as historical workflow context; do not promote without a future slice. |
| `docs/investor/DEMO_DATASET_CORE_V1.md` | `INVESTOR_DEMO_CONTEXT` | It supports the seeded investor story and RFQ payload, not canonical product status. | Preserve with investor/demo docs; do not treat as stage authority. |
| `docs/investor/DEMO_SCREENSHOT_CHECKLIST.md` | `INVESTOR_DEMO_CONTEXT` | It is a screenshot checklist for demo capture. | Preserve as demo context; stage only with investor/demo group if owner approves. |
| `docs/investor/DEMO_SCREENSHOT_CHECKLIST_STAGE_9J_COMPLETED.md` | `HISTORICAL_EVIDENCE` | It is completed Stage 9J evidence that has later RFQ wording edits; provenance needs review. | Investigate before staging; do not silently rewrite historical evidence further. |
| `docs/investor/INVESTOR_DEMO_HANDOFF.md` | `INVESTOR_DEMO_CONTEXT` | It supports demo handoff content, not current product status. | Preserve as investor context. |
| `docs/investor/INVESTOR_DEMO_SCRIPT_CORE_V1.md` | `INVESTOR_DEMO_CONTEXT` | It is a demo script, not current-stage authority. | Preserve as investor context. |
| `docs/investor/STAGE_13B_INVESTOR_DEMO_SCRIPT.md` | `INVESTOR_DEMO_CONTEXT` | It is Stage 13B demo script context and must not canonize Stage 13B as current. | Preserve as historical demo context; do not use as current-stage authority. |
| `docs/investor/STAGE_13C_DEMO_REHEARSAL_REPORT.md` | `INVESTOR_DEMO_CONTEXT` | It records `INVESTOR_WALKTHROUGH_READY` for a controlled demo rehearsal, not production completeness or current product status. | Preserve as historical rehearsal evidence; do not use as current-stage authority. |
| `docs/investor/STAGE_13D_DEMO_LIMITATIONS_AND_RISK_NOTES.md` | `INVESTOR_DEMO_CONTEXT` | It documents Stage 13D demo limitations and guardrails. It is useful but not canonical product status. | Preserve; stage later only with owner-approved investor/demo group. |
| `docs/investor/STAGE_13D_INVESTOR_DEMO_FREEZE.md` | `INVESTOR_DEMO_CONTEXT` | It freezes a repeatable investor demo package. Demo freeze does not override product `PARTIAL` status. | Preserve; do not mark Stage 13D current. |
| `docs/investor/STAGE_13E_DEMO_PREFLIGHT_EVIDENCE.md` | `INVESTOR_DEMO_CONTEXT` | It is a preflight evidence template for demo readiness. It is not current product-stage evidence. | Preserve as preflight template; owner decision before staging. |
| `docs/investor/STAGE_13E_FINAL_DEMO_SIGNOFF.md` | `INVESTOR_DEMO_CONTEXT` | It is final demo signoff guidance with pending go/no-go fields, not current-stage authority. | Preserve; do not mark Stage 13E current. |
| `docs/investor/investor-demo-script.md` | `INVESTOR_DEMO_CONTEXT` | It is an older investor demo script dated 2026-05-23 with Stage 9 limitation language. | Preserve as historical demo script; index before reuse. |
| `docs/investor/DATA_ROOM_CHECKLIST.md` | `INVESTOR_DEMO_CONTEXT` | It is a data-room checklist outline, not stage/status evidence. | Keep for owner decision; stage only with investor/data-room docs. |
| `docs/investor/INVESTOR_NARRATIVE_V1.md` | `UNKNOWN_OWNER_DECISION` | It appears empty or placeholder-like during bounded inspection. Ownership and intended content are unclear. | Do not edit or stage until owner decides whether to populate, keep as placeholder, or remove in a future explicit cleanup slice. |
| `docs/investor/demo-api-walkthrough.http` | `INVESTOR_DEMO_CONTEXT` | It is a runnable demo walkthrough artifact with seeded IDs and headers, not status authority. | Review with investor/demo or local demo tooling group. |
| `docs/investor/demo-evidence/README_STAGE_9J.md` | `HISTORICAL_EVIDENCE` | It is Stage 9J evidence with later RFQ wording edits; provenance needs review. | Investigate before staging; avoid further silent edits. |
| `docs/runbooks/LOCAL_DEMO_RUNBOOK.md` | `RUNBOOK_CONTEXT` | It is a local demo runbook, not current-stage authority. | Preserve as runbook context. |
| `docs/runbooks/LOCAL_DEMO_VERIFICATION_REPORT_STAGE_9J.md` | `HISTORICAL_EVIDENCE` | It is a completed Stage 9J verification report with later RFQ wording edits; provenance needs review. | Investigate before staging; avoid further silent edits. |
| `docs/runbooks/STAGE_13B_DEMO_RUNBOOK.md` | `RUNBOOK_CONTEXT` | It is Stage 13B runbook context and must not canonize Stage 13B as current. | Preserve as runbook context. |
| `docs/runbooks/STAGE_13D_DEMO_PREFLIGHT_CHECKLIST.md` | `RUNBOOK_CONTEXT` | It is a Stage 13D demo preflight checklist, not product status evidence. | Preserve; owner decision before staging. |
| `docs/runbooks/STAGE_13D_INVESTOR_WALKTHROUGH_CHECKLIST.md` | `RUNBOOK_CONTEXT` | It is a route-by-route investor walkthrough checklist, not product status evidence. | Preserve; owner decision before staging. |
| `docs/runbooks/STAGE_13E_BROWSER_RESET_AND_STARTUP.md` | `RUNBOOK_CONTEXT` | It is browser/session startup guidance for the investor walkthrough, not product status evidence. | Preserve; owner decision before staging. |

## 4. Documents requiring owner decision

| Path | Why uncertain | Owner must decide | What not to do yet |
| --- | --- | --- | --- |
| `docs/investor/INVESTOR_NARRATIVE_V1.md` | Bounded read showed no content; purpose and owner are unclear. | Decide whether this should become a real investor narrative, remain a placeholder, or be removed in a future explicit cleanup slice. | Do not populate, stage, delete, or treat as evidence in this slice. |
| `apps/web-dashboard/next-env.d.ts` | Dirty worktree attribution called it generated-looking/local frontend type drift. | Decide whether it is intentional frontend tooling state or should be regenerated/ignored in a future frontend slice. | Do not edit or stage from a doc-control slice. |
| `docs/investor/DEMO_SCREENSHOT_CHECKLIST_STAGE_9J_COMPLETED.md` | Historical completed evidence appears edited for current RFQ wording. | Decide whether historical evidence should remain original, include amendment notes, or be split from current demo docs. | Do not silently rewrite historical evidence further. |
| `docs/investor/demo-evidence/README_STAGE_9J.md` | Historical Stage 9J evidence appears edited for current RFQ wording. | Decide whether to preserve original evidence text, add amendment context, or move current RFQ wording elsewhere. | Do not silently rewrite historical evidence further. |
| `docs/runbooks/LOCAL_DEMO_VERIFICATION_REPORT_STAGE_9J.md` | Historical verification report appears edited for current RFQ wording. | Decide whether the report should remain immutable evidence or carry an explicit later-amended note. | Do not silently rewrite historical evidence further. |
| `apps/core-api/src/main/java/com/orderpilot/api/rest/ServiceInfoController.java` | Untracked backend runtime artifact, not a documentation-control artifact. | Decide whether to include it in a backend runtime/demo ergonomics PR. | Do not edit, stage, or classify as documentation evidence. |

## 5. Do-not-canonize list

These docs must not become current-stage authority unless `docs/product/current-stage.md` is intentionally updated in a future explicit slice:

- `docs/ROADMAP.md`
- `README.md`
- `PROJECT_STATUS_CHECKPOINT.txt`
- `ORDERPILOT_CORE_V1_AI_DEV.md`
- `docs/investor/STAGE_13C_DEMO_REHEARSAL_REPORT.md`
- `docs/investor/STAGE_13D_INVESTOR_DEMO_FREEZE.md`
- `docs/investor/STAGE_13E_FINAL_DEMO_SIGNOFF.md`

## 6. Safe future actions

### Safe doc-control actions

- Add short non-authoritative banners where a document is likely to be misread.
- Link to `docs/product/current-stage.md`.
- Preserve historical evidence.
- Split future PR/staging groups by document authority and intent.
- Keep `PASS` freeze result separate from `PARTIAL` product status.
- Keep uncertain files in owner-decision buckets until explicitly resolved.

### Unsafe actions during freeze

- Deleting historical docs.
- Rewriting investor reports.
- Claiming production readiness.
- Combining with backend/frontend capability changes.
- Staging broad dirty files.
- Promoting Stage 13C/13D/13E demo signoff to current product-stage authority.
- Treating README, roadmap, or checkpoint files as current-stage authority.

## Alignment with Dirty Worktree Attribution Plan

- Dirty worktree attribution classified 59 current dirty entries at the time of that slice: 45 tracked modified files and 14 untracked files including `docs/product/DIRTY_WORKTREE_ATTRIBUTION_PLAN.md`.
- This index does not reclassify capability files.
- This index only clarifies document authority.
- Any uncertain file remains owner-decision required.
- No conflict with `docs/product/DIRTY_WORKTREE_ATTRIBUTION_PLAN.md` was found during this slice.

## 7. Verification notes

Commands run for this documentation-control slice:

```powershell
git status --short
git diff --stat
git diff --name-status
git diff --cached --name-status
rg -n "Stage|stage|current|status|freeze|PASS|PARTIAL|canonical|superseded|historical|signoff|stable|move next|roadmap|checkpoint" docs README.md PROJECT_STATUS_CHECKPOINT.txt ORDERPILOT_CORE_V1_AI_DEV.md
Get-Content <targeted docs> -TotalCount <bounded count>
```

Files changed by this slice:

- `docs/product/CANONICAL_STAGE_TAXONOMY.md`
- `docs/product/HISTORICAL_STAGE_DOC_INDEX.md`

Confirmations:

- No product capability files were changed.
- No backend capability code was changed.
- No frontend capability code was changed.
- No AI worker, bot runtime, integration, analytics, test, fixture, script, dependency, or lockfile changes were made.
- No files were deleted or moved.
- No files were staged.
- No files were committed.
