# 17 — DECISION, RISK, ASSUMPTION AND NOT_PROVEN REGISTERS

Status: PARTIAL_FOR_DECLARED_SCOPE — seeded in Pass 0; grows through Passes 1–7. Nothing here is OWNER_APPROVED.

## A. Owner-decision register

| ID | Decision needed | Why owner-level | Options (default recommendation) | Status | Blocks |
|---|---|---|---|---|---|
| OD-01 | **Select/reconcile the governing target proposal**: in-repo ARCHITECTURE_FREEZE_0 (S4) vs ENTERPRISE_MODULAR_MONOLITH_FREEZE_V2 (S5), same baseline `c11f7e8` | Two rival architecture contracts exist; scope difference (buy-side S2P now vs later; mandatory RabbitMQ/Typesense/Novu vs defer-until-trigger) changes product scope and ops cost | (a) S4 base + S5 deltas re-derived per-decision through ATAM (recommended); (b) S5 wholesale; (c) S4 wholesale | OWNER_DECISION_REQUIRED | Pass 2 module map; Pass 6 ADRs |
| OD-02 | Supply or supersede missing V2 production prompts (01/02/03) and the 4 other missing governance docs | Only owner knows whether V2 texts exist and where | (a) supply files; (b) declare in-repo non-V2 + master prompt as current | OWNER_DECISION_REQUIRED | freshness of normative constraints |
| OD-03 | Confirm State-1/2/3 ↔ P1 mapping (roadmap V2 absent) | State axis defined only in a missing owner document | (a) accept S4 §1.1 mapping "State 2 ≈ P1 through P1-G" (recommended, matches S2); (b) restore roadmap doc | OWNER_DECISION_REQUIRED (carried from S4 #1) | freeze finality |
| OD-04 | Supply canonical C01–C31 capability list | No located document enumerates C01–C31; V3 §12 forbids silent renumbering | (a) supply list; (b) authorize this package to propose a versioned capability map from S4/S5+OP-CAP evidence | OWNER_DECISION_REQUIRED | file 03 completion |
| OD-05 | billingar / Invoice-AR ownership: owned aggregate vs ERP-mirror projection | Financial authority boundary | (b) mirror until ERP write proven (S4 default) | OWNER_DECISION_REQUIRED (S4 #2) | finance module design |
| OD-06 | securitydelivery vs communicationdelivery split + MFA provider | Blast-radius/ops cost | (a) module boundary now, provider later (S4 default) | OWNER_DECISION_REQUIRED (S4 #3) | delivery design |
| OD-07 | Redis physical split (session/security vs runtime/rate) | Ops budget | (b) one Redis, two namespaces now (S4 default) | OWNER_DECISION_REQUIRED (S4 #4) | topology |
| OD-08 | Legacy `/api/stage8|9` deprecation timeline | FE migration schedule | (a) alias + deprecate in first wave | OWNER_DECISION_REQUIRED (S4 #5) | migration wave 1 |
| OD-09 | Quote/Order state enforcement mechanism | Data-integrity hardening w/ migration cost | (a) DB CHECK + (b) JPA enum (S4 default) | OWNER_DECISION_REQUIRED (S4 #6) | migration wave 2 |
| OD-10 | Connector Gateway P1-F boundary: in-monolith module vs first extracted service | Extraction gate | (a) in-monolith until ADR-012 gate satisfied | OWNER_DECISION_REQUIRED (S4 #7) | P1-F design |
| OD-11 | Staff SSO/MFA/JIT identity timing | Security ops investment | (b) after P1-H (S4 default) | OWNER_DECISION_REQUIRED (S4 #8) | staff plane completeness |
| OD-12 | RabbitMQ / Typesense / Novu adoption (S5 mandates; S4 silent/defers) | New operated components; V3 §28 requires measurable triggers | re-derive in Pass 6 ATAM; default DEFER_UNTIL_TRIGGER unless owner confirms S5's mandate | OWNER_DECISION_REQUIRED | files 07/10/15 |
| OD-13 | Buy-side S2P/S2C module family now vs deferred | Product scope | S5 includes; wedge (V3 §2) is sell-side O2C → default: contracts designed, build deferred | OWNER_DECISION_REQUIRED | file 03/16 |
| OD-14 | SLO / RTO / RPO / capacity thresholds (1000/1200 RPS remain hypotheses) | Commercial/ops commitments | define after Pass 5 workload model | OWNER_DECISION_REQUIRED | files 13/14 |

## B. Assumption register

| ID | Assumption | Owner | Review trigger |
|---|---|---|---|
| AS-01 | Head `b2b7255` is the review baseline; `c11f7e8`-bound evidence (S4) valid for all files untouched by the head commit | architecture run | any new commit to main |
| AS-02 | GitHub repo rename OrderPilot→Operant is an alias-level event; local remote URL functional | architecture run | git fetch failure |
| AS-03 | `deep-research-report.md` and Downloads CV files are not project governance | architecture run | owner statement |
| AS-04 | The stage-axis canonical pointer (2026-06-04) is stale relative to P1 work but its Stage-N historical closures remain valid | architecture run | owner refresh of current-stage.md |
| AS-05 | Historical Stage 29 remains CLOSED (accepted from V3 prompt; no local source references Stage 29) | owner | discovery of any Stage-29 artifact |

## C. Risk register (architecture-run level)

| ID | Risk | Impact | Mitigation | Status |
|---|---|---|---|---|
| R-01 | Dual rival freezes drift further apart while both live (one in repo, one in Downloads) | Implementers may follow the wrong contract | OD-01 decision; until then this package is the single reconciliation surface | OPEN |
| R-02 | Missing security constitution → an owner-approved invariant could exist that this package unknowingly violates | Rework after freeze proposal | OD-02; freeze proposal (file 18) will list this as an explicit reopen trigger | OPEN |
| R-03 | S8 audit CSVs reused beyond their SHA validity | False current-state claims | compatibility check step in continuation manifest before any reuse | OPEN |
| R-04 | Package written into nested repo as untracked files; nested-repo status shows only top-level dir to outer repo | Artifacts lost on cleanup | owner to decide commit/PR in a separate implementation-authorized task | OPEN |

## D. NOT_PROVEN register (carried verbatim from S2 + S4 §22; scope-bound)

Production runtime: credential issuance, private management ingress, live operantctl, staff SSO/MFA/JIT, persistent control-access audit, clean-host runtime, reboot lifecycle, public ingress closure. Integration: external connector writes, outbox external publish, real payment matching, MFA delivery, real IdP interop, Redis failover. Authentication flows: independent external-customer / service-account / staff planes. Data: Quote/Order transition semantics (string-valued, inferred). Documents: 8 mandatory governance sources absent (01 §2). Repository identity server-side (CF-06). Head-commit code delta G7 unreviewed.
