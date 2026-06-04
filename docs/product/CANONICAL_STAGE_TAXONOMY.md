# Canonical Stage Taxonomy

## 1. Status

- Canonical Stage-Source Freeze: PASS
- Product stage status: PARTIAL
- Product capability freeze: ACTIVE
- This document defines stage/status terminology and document authority.
- This document does not implement product capability.

## 2. Canonical source hierarchy

1. `docs/product/current-stage.md`
   - Canonical pointer only.
   - Identifies where current detailed stage evidence lives.
   - Does not, by itself, prove production completeness.

2. `docs/product/STAGE_STATUS_RECONCILIATION.md`
   - Detailed evidence source for current stage/status reconciliation.
   - Explains why the current product stage status is `PARTIAL`.
   - Preserves implementation, verification, stale-doc, and limitation evidence.

3. `docs/product/DIRTY_WORKTREE_ATTRIBUTION_PLAN.md`
   - Dirty worktree classification and future staging plan.
   - Explains which modified/untracked files should be kept, reviewed, split, investigated, or left untouched.
   - Does not stage, clean, delete, or approve the dirty worktree.

4. `docs/product/CANONICAL_STAGE_TAXONOMY.md`
   - Terminology and authority taxonomy.
   - Defines allowed status vocabulary and future stage-source update protocol.

5. `docs/product/HISTORICAL_STAGE_DOC_INDEX.md`
   - Historical/superseded status-doc index.
   - Preserves useful context while preventing historical documents from becoming current-stage authority by accident.

6. Roadmaps / investor docs / reports / runbooks
   - Context only unless explicitly promoted by `docs/product/current-stage.md`.
   - May preserve useful planning, demo, evidence, or runbook history.
   - Must not override the canonical pointer or detailed evidence source.

## 3. Terms

### Canonical current-stage pointer

The file that identifies the authoritative detailed evidence source. The current canonical current-stage pointer is `docs/product/current-stage.md`.

### Detailed evidence source

The file that explains why the current stage/status is what it is. The current detailed evidence source is `docs/product/STAGE_STATUS_RECONCILIATION.md`.

### Product stage status

The current implementation/reconciliation status of the product. The current value is `PARTIAL`, meaning the repository contains meaningful implemented capability, but Core v1 is not production-complete and not fully reconciled as complete.

### Freeze result

Whether the stage-source reconciliation/freeze itself passed. The current value is `PASS`, meaning the stage-source authority model was reconciled successfully.

### Capability freeze

A repository/process state in which no new product capability should be added until the next safe executable slice is explicitly chosen. The current capability freeze is `ACTIVE`.

### Historical status document

A document that may preserve useful evidence, implementation history, demo state, or investor context, but must not be read as current stage authority unless `docs/product/current-stage.md` explicitly promotes it.

### Roadmap/guardrail document

A planning or architecture document that guides future work and preserves product constraints, but does not override current-stage status. Roadmaps and guardrails are useful context, not current status authority.

### Dirty worktree attribution

A classification of modified/untracked files into safe future staging groups. It is evidence for review and planning, not an instruction to stage, delete, or clean files.

## 4. Valid status vocabulary

### Product stage status values

- `NOT_STARTED`: Work has not begun or no implementation evidence is present.
- `PARTIAL`: Some implementation/evidence exists, but completion, production readiness, or full acceptance is not proven.
- `BLOCKED`: Work or verification cannot proceed without a blocking decision, dependency, environment, or owner input.
- `READY_FOR_REVIEW`: A slice is believed complete enough for owner/reviewer evaluation, but is not accepted as passed.
- `PASS`: A specific product stage/slice passed its defined acceptance criteria.
- `FAIL`: A specific product stage/slice failed its defined acceptance criteria.

Current product stage status: `PARTIAL`.

### Freeze result values

- `PASS`: Stage-source authority/freeze reconciliation passed.
- `FAIL`: Stage-source authority/freeze reconciliation failed.
- `NOT_RUN`: Stage-source authority/freeze reconciliation has not been run.

Current freeze result: `PASS`.

### Document authority values

- `CANONICAL_POINTER`: The file that points to the current detailed evidence source.
- `DETAILED_EVIDENCE`: The current authoritative evidence source for stage/status.
- `GOVERNANCE_TAXONOMY`: A terminology and authority taxonomy.
- `DIRTY_WORKTREE_EVIDENCE`: Dirty worktree attribution and staging guidance.
- `HISTORICAL_INDEX`: An index of historical/superseded/context documents.
- `ROADMAP_GUARDRAIL`: Planning or guardrail context that does not override current status.
- `HISTORICAL_EVIDENCE`: Historical implementation, verification, or stage evidence that is preserved but non-authoritative for current status.
- `INVESTOR_DEMO_CONTEXT`: Investor/demo narrative, rehearsal, freeze, or signoff context that does not define the current product stage.
- `RUNBOOK_CONTEXT`: Operational/runbook guidance that does not define the current product stage.
- `UNKNOWN_OWNER_DECISION`: A file or artifact that needs an explicit owner decision before it is treated as authoritative, historical, staged, or discarded.

## 5. Important distinction: PASS vs PARTIAL

- Freeze `PASS` means stage-source authority was reconciled successfully.
- Product `PARTIAL` means product implementation/completeness is still not fully complete.
- These two statuses are not interchangeable.
- A `PASS` freeze does not mean production-ready Core v1.
- Historical Stage 13/13E signoff language does not override `PARTIAL` status.
- Investor walkthrough readiness is not the same as production readiness.
- Demo freeze language is not the same as product capability acceptance.

## 6. Rules for future Codex/Claude runs

- Start every stage/status slice by reading `docs/product/current-stage.md`.
- Treat `docs/product/STAGE_STATUS_RECONCILIATION.md` as detailed evidence unless `docs/product/current-stage.md` points elsewhere.
- Treat Stage 13C/13D/13E investor docs as historical or demo context unless explicitly re-promoted by the canonical pointer.
- Do not use `README.md`, `docs/ROADMAP.md`, `PROJECT_STATUS_CHECKPOINT.txt`, or `ORDERPILOT_CORE_V1_AI_DEV.md` as current-stage authority unless the canonical pointer says so.
- Do not combine doc-control changes with product capability changes.
- Do not stage or commit broad dirty worktree files without owner decision.
- If a file status is uncertain, classify it; do not "fix" it silently.
- Preserve historical evidence unless a future explicit slice authorizes deletion or migration.
- Keep `PARTIAL` product status and `PASS` freeze result separate in summaries, docs, and PR descriptions.

## 7. Stage-source update protocol

1. Choose one executable slice.
2. State whether it is doc-control or capability.
3. Inspect `docs/product/current-stage.md`.
4. Inspect the detailed evidence source named by `docs/product/current-stage.md`.
5. Inspect `docs/product/DIRTY_WORKTREE_ATTRIBUTION_PLAN.md`.
6. Modify only files in scope.
7. Run verification appropriate to the slice type and blast radius.
8. Update evidence docs only when evidence actually changes.
9. Leave `docs/product/current-stage.md` unchanged unless the authoritative detailed evidence source changes.
10. Report files changed, commands run, limitations, and no-touch areas.
11. Do not stage, commit, delete, install dependencies, or update lockfiles unless explicitly requested.

## 8. Non-authoritative historical docs

Historical, superseded, roadmap, investor-demo, and runbook context is indexed in `docs/product/HISTORICAL_STAGE_DOC_INDEX.md`.

That index does not delete or rewrite historical evidence. It classifies authority so future work does not confuse preserved context with the current canonical stage.

## 9. Verification notes

Commands used for this documentation-control slice:

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

Capability-file confirmation:

- No backend capability files were changed.
- No frontend capability files were changed.
- No AI worker, bot runtime, integration, analytics, test, fixture, script, dependency, or lockfile changes were made.
- No files were staged, committed, deleted, moved, or cleaned.
