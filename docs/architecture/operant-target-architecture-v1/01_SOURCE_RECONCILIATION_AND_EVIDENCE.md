# 01 — SOURCE RECONCILIATION AND EVIDENCE

Scope: reconcile every located governance source for the Operant target-architecture run; select authoritative use per the four truth models (Master Prompt V3 §6); register conflicts.
Status: COMPLETE_FOR_DECLARED_SCOPE (locations searched: repo, `~/Documents`, `~/Downloads`, `/home/akm` maxdepth-4 patterns). Absence claims are location-bound, not absolute.

## 1. Source inventory (located)

| # | File | Location | SHA-256 (prefix) | Date evidence | Declared status | Selected authoritative use |
|---|---|---|---|---|---|---|
| S1 | `OPERANT_TARGET_ARCHITECTURE_DESIGN_AND_ARCHITECTURE_FREEZE_MASTER_PROMPT_V3.md` | `OrderPilot-Core/` (untracked) | `a9ceebf7` | mtime 2026-08-01 | this run's execution contract | **Governing task contract** |
| S1b | `OPERANT_TARGET_ARCHITECTURE_MASTER_PROMPT_V3.md` | `~/Downloads/` | `8dcb9bc2` | — | same text, formatting-only differences (heading levels, code fences; verified by diff) | Duplicate of S1; NOT independent authority |
| S2 | `OPERANT_PRODUCTION_EXECUTION_STATE.md` (document_version 12) | `OrderPilot-Core/` | `76a90774` | phase 1, active P1-E | durable capability truth | **Authoritative current-implementation status axis (P1-A…H)** |
| S3 | `OPERANT_PRODUCTION_MASTER_PROMPT.md` (139 KB) | `OrderPilot-Core/` | `dc497083` | — | production execution contract | Normative constraints source; TARGETED review only this run (TRUNCATED for full-read claims) |
| S4 | ARCHITECTURE_FREEZE_0 package (master + validation + decisions + 12 ADRs + 16 views + 6 TSV catalogs) | `OrderPilot-Core/docs/architecture/ARCHITECTURE_FREEZE_0/` (tracked, commit `b2b7255`) | master `ec48bf0a` | baseline `c11f7e8` | code-grounded target design; verdict **CHANGES_REQUIRED**, 8 owner decisions open | **Current-implementation evidence + target proposal (level: reconciled architecture proposal)** |
| S5 | `OPERANT_ENTERPRISE_MODULAR_MONOLITH_ARCHITECTURE_FREEZE_V2.md` (2470 lines) | `~/Downloads/` | `8ad3039d` | 2026-07-31, baseline `c11f7e8` | "owner-review architecture contract" — **not owner-approved** | **Competing target proposal** (level: reconciled architecture proposal); richer scope (Novu, RabbitMQ, Typesense, S2P/S2C, BotCreationEngine, M1–M11 slices) |
| S6 | `OPERANT_ENTERPRISE_MODULAR_MONOLITH_ARCHITECTURE_FREEZE_V1.md` (1751 lines) | `~/Downloads/` | `8cfcfd5f` | precedes V2 | superseded by V2 | Historical; SUPERSEDED |
| S7 | `OPERANT_ERP_SYNC_CONCURRENCY_DATA_LEAK_CONVERSATION.md` | `~/Documents/` (+ byte-identical `(1)` copy, `1c97f988`) | `1c97f988` | mtime 2026-07-29/31 | §4 mandatory source | ERP sync/concurrency design input (Pass 3/Pass 4); NOT yet fully read — TRUNCATED this run |
| S8 | `OPERANT_CORE_API_MAIN_FULL_DEPTH_AUDIT_2026-07-29.md` + FINDINGS/COVERAGE/PER_FILE_REVIEW CSVs + REMEDIATION_PLAN (byte-identical `(1)` copy `0b0b17a1`) | `~/Documents/` | audit `2599426a` | 2026-07-29 | full-depth audit artifacts | Current-implementation evidence, bound to a 2026-07-29 baseline; SHA-compatibility with `b2b7255` must be checked before reuse |
| S9 | `AGENTS.md` / `CLAUDE.md` / `.claude/skills` | `OrderPilot-Core/` | `f8aa4869` / `88ebb5da` | — | agent workflow rules | Process constraints; not product authority |
| S10 | `заметки для OPERANT.md` | `/home/akm/OrderPilot/` | `622ee187` | ~2026-07-13 | owner PR-review notes (PR #202/#252 follow-ups; "надо доделать фронтенд") | Owner-decision evidence for named findings only; not a vision document |
| S11 | Stage-axis docs: `CANONICAL_STAGE_TAXONOMY.md`, `current-stage.md` (2026-06-04), `STAGE_STATUS_RECONCILIATION.md`, `HISTORICAL_STAGE_DOC_INDEX.md`, `op-cap-roadmap-sync.md` | `OrderPilot-Core/docs/product/` | — | canonical hierarchy defined in-taxonomy | Historical Stage-N / OP-CAP axis authority (historical evidence axis) |
| S12 | `01_SERVER_PLATFORM_AND_CONTROL_PLANE.md` (no V2 suffix) | `docs/prompts/production/` | — | child of S3 | Version-uncertain variant of mandatory "…_V2" source; VERSION_OR_FRESHNESS_CONFLICT |
| S13 | `OPERANT_WORLD_CLASS_FRONTEND_MASTER_PLAN_V1.md` | `OrderPilot/owner-artifacts/` | — | 2026-07-18 | frontend plan | Pass 5/experience input; not read this run |
| S14 | `deep-research-report.md` | `~/Downloads/` | `8ac2bdff` | — | generic research-method template, not Operant-specific | Method reference only; NOT project authority |

## 2. Mandatory §4 sources NOT located (searched: repo, ~/Documents, ~/Downloads, home maxdepth-4)

1. `reverse-защита-и-инструкций-по-бизнес-логике-читать-всегда-перед-промптом.txt`
2. `OPERANT_THREE_STATE_EXECUTION_ROADMAP_V2.md` ← defines State 1–3; its absence was already flagged by FREEZE_0 §1.1
3. `OPERANT_INTEGRATED_PRODUCT_DEVELOPMENT_EXECUTION_PLAN_V3.md`
4. `01_SERVER_PLATFORM_AND_CONTROL_PLANE_V2.md` (only non-V2 exists — S12)
5. `02_STANDALONE_BUSINESS_AI_AND_CONNECTORS_V2.md`
6. `03_PRODUCTION_VERIFICATION_AND_RELEASE_V2.md`
7. `OPERANT_PROMPT_ENGINEERING_BUSINESS_LOGIC_ARCHITECTURE_STANDARD_V1.md`
8. `OPERANT_TOP_TIER_SECURITY_ARCHITECTURE_CONSTITUTION_V1.md`
9. `Видение-оунера-на-проект.txt`

Consequence: State 1–3 axis, the security constitution's exact invariant list, and the owner's vision text cannot be quoted; where they are needed, decisions are registered as OWNER_DECISION_REQUIRED instead of invented (Master Prompt V3 §6.5). C01–C31 canonical capability model: **no in-repo document enumerating C01–C31 was located this run**; capability mapping will use the FREEZE_0/S5 module-capability lists plus OP-CAP historical labels until the owner supplies the canonical C-list. (OP-CAP labels found up to OP-CAP-54.)

## 3. Truth-model application

- **Normative target authority:** no explicit owner decision document located this run → highest available levels are S3 (production master prompt constraints) and the two *proposals* S4/S5. Neither S4 nor S5 is OWNER_APPROVED; both self-declare review status. Nothing in this package may claim owner approval.
- **Current repository implementation evidence:** S2 (execution state v12) + S4's code-grounded findings at `c11f7e8` + S8 CSVs (older baseline). Head `b2b7255` differs from `c11f7e8` only by the FREEZE_0 docs and a `Stage2Dtos.java` change (166 lines, "freeze stages fixes for stage2 DTO") — that code delta is NOT_REVIEWED this run.
- **Deployed/runtime evidence:** none available; production runtime items remain NOT_PROVEN exactly as listed in S2 (`production_runtime_not_proven` block).
- **External reference evidence:** none consulted this run (bounded research deferred to per-decision passes 5–6).

## 4. Conflict register (material)

| ID | Class | Conflict | Resolution this run |
|---|---|---|---|
| CF-01 | OWNER_DECISION_CONFLICT | **Two competing target proposals at the same baseline `c11f7e8`:** S4 (30 modules, 5 bands, sell-side O2C focus, technology-conservative: no broker/search/notification adoption decisions) vs S5 (31 modules incl. `supplier`,`sourcing`,`procurement`,`accountspayable`,`s2p`,`botplatform`; **mandatory** RabbitMQ, Typesense, Novu; O2C+S2P process families; slices M1–M11). Module names also differ for the same concerns (e.g. `identityaccess` vs `identity`+`accesspolicy`; `auditoutbox` naming matches; `workmanagement` vs `workcase`). | Neither selected as target authority. Reconciliation is the first work item of Pass 2; technology adoptions (RabbitMQ/Typesense/Novu) are re-derived as ATAM trade-offs in Pass 6 with S5 as a proposal input, not a decided fact. Registered OWNER_DECISION_REQUIRED (OD-01). |
| CF-02 | VERSION_OR_FRESHNESS_CONFLICT | §4 names prompts 01/02/03 **V2**; only a non-V2 `01_…` exists in-repo; 02/03 absent entirely. | Use in-repo 01 as best-available with freshness caveat; register OD-02 (owner to supply V2 set or confirm supersession). |
| CF-03 | TERMINOLOGY_CONFLICT | Three execution axes coexist in sources: historical Stage 1–14/OP-CAP (S11, closed/frozen), P1-A…P1-H phase axis (S2, authoritative), State 1–3 (defined only in the missing roadmap). S4 maps "State 2 ≈ P1 topology through P1-G" and marks it NEEDS_OWNER_DECISION. | Adopt S2's P-axis as the only implementation-sequence authority; treat Stage-N/OP-CAP as historical evidence per S11 hierarchy; keep State-mapping OWNER_DECISION_REQUIRED (OD-03). No new axis is created by this package (V3 §3). |
| CF-04 | DUPLICATE_SOURCE_CONFLICT | `(1)`-suffixed copies of S7 and S8-remediation exist. | Byte-identity **proven by SHA-256** (identical). Either copy usable; canonical path = non-suffixed. Closed. |
| CF-05 | DUPLICATE_SOURCE_CONFLICT | Two V3 master-prompt copies differ in bytes (`a9ceebf7` vs `8dcb9bc2`). | Diff shows formatting-only (heading levels, ```text fences). Semantically identical; repo copy treated as the executed contract. Closed. |
| CF-06 | IMPLEMENTATION_DRIFT / naming | Prompt and freeze docs say repository `akonPAPA/Operant`; local remote is `akonPAPA/OrderPilot.git`; outer repo/product folder still "OrderPilot"; execution-state says `repository: akonPAPA/Operant`. | Recorded. Not architecture-blocking; GitHub rename typically aliases old URL. NOT_PROVEN which name is current server-side. |
| CF-07 | CURRENT_VS_TARGET | S4 code-grounded gaps: quote-god-module risk, unconstrained `draft_quote.status` VARCHAR(40), 76-controller sprawl with 6 duplicate business paths (D1–D6), legacy `/api/stage8|9` namespaces, 86-file `trust` domain. | Carried into 02_CURRENT_ARCHITECTURE_AND_GAP_MAP.md as the authoritative current-vs-target gap baseline. |
| CF-08 | VERSION_OR_FRESHNESS_CONFLICT | S8 audit artifacts (2026-07-29) predate head `b2b7255` (2026-07-31). | Reusable only for files whose blame/SHA is unchanged; compatibility check pending (continuation manifest). |

## 5. Selected authority summary (this run)

1. Execution contract: S1 (V3 master prompt).
2. Implementation-sequence axis: S2 (P1-A…H; phase 1, active P1-E lifecycle slice, next P1-F).
3. Normative constraints: S3 + safety law restated identically in S4/S5/CLAUDE.md ("AI suggests. Rules validate. Human approves if risky. Backend writes. Audit records." + outbox/reconciliation extensions).
4. Current-state evidence: S2 + S4 (at `c11f7e8`) + S8 (older, gated).
5. Target design inputs: S4 and S5 as **rival drafts** to be reconciled — neither is normative until the owner decides OD-01.
6. Historical axis: S11 hierarchy; Historical Stage 29 remains CLOSED (no source located this run even references a "Stage 29" — its closure is accepted from the V3 prompt itself).
