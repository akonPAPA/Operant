# Operant Architecture Freeze 0 — Validation Report

Source commit `c11f7e8` (akonPAPA/Operant, `main`, clean worktree). This is **implementer self-review
only** — no claim of independent review.

## Coverage of inputs

- **Read (current truth):** `docs/ai/ORDERPILOT_ACTIVE_CHECKPOINT.md`, `OPERANT_PRODUCTION_EXECUTION_STATE.md`
  (v12), architecture docs (`data-authority-model`, `intake-architecture`, `integration-connector-foundation`,
  `api-command-boundaries`, `ADR-0001`, `SECURITY_AND_CONTROLLED_WRITE_PATHS`).
- **Inspected (code):** 28 domain contexts; 76 controllers + base mappings; ~40 state enums;
  `ApiRouteSecurityPolicy` (491 lines), `ApiPermission` (full list), `OutboxEvent`, idempotency infra,
  Redis usage, 69 migrations V1–V69.
- **Absent (could not cross-check):** `OPERANT_THREE_STATE_EXECUTION_ROADMAP_V2.md`, the Cyrillic
  reverse-defense txt, `OPERANT_PROMPT_ENGINEERING_BUSINESS_LOGIC_ARCHITECTURE_STANDARD_V1.md`,
  `OPERANT_TOP_TIER_SECURITY_ARCHITECTURE_CONSTITUTION_V1.md`, any tracked AGENTS.md. **Compatibility with
  the referenced full-depth audit: PARTIAL** — code fully reconciled to HEAD; external audit/roadmap docs
  not locatable.

## PASS 1 — Domain and business review

- **Capability completeness:** all 28 current contexts map to a target module; no capability dropped.
- **Owner of every business state:** one authoritative owner per state (ownership matrix). No state has two
  writers. Work Management is not a second owner (ADR-006).
- **Duplicate workflows:** D1–D6 identified with controller evidence and MERGE/SPLIT plans.
- **Legal transitions:** state machines catalogued; forbidden transitions listed (non-APPROVED→convert,
  self-approve, terminal→correct, milestone out-of-sequence, import skip-validation).
- **O2C/payment/support/connector/communication flows:** all covered (F1–F16).
- **Defect found & corrected during review:** initial draft under-specified the buyer accept/reject path
  (F8) — added as explicit flow + View 07 note and marked TARGET_STATE_2 (no buyer portal today).

## PASS 2 — Security and consistency review

- **Four access planes:** modelled independently and grounded in `ApiRouteSecurityPolicy` (tenant,
  external-customer token, service-account signature, staff STAFF_*/executor). Global default-deny.
- **Client vs backend authority:** tenant/actor server-resolved; body authority ignored (OP-CAP-31/35).
- **Tenant isolation:** tenant_id on business rows; tenant-scoped repos mandated (rule 10).
- **DTO boundaries:** request DTOs carry intent only; responses expose no secret/internal-id/audit/outbox
  internals (rule 12, existing contract-test precedent).
- **Concurrency/idempotency:** ladder defined (ADR-010); Idempotency-Key + records present.
- **Audit/outbox:** atomic with business tx; accepted audit cannot commit independently (View 12, quality gate).
- **Connector effects:** gated on APPROVED ChangeRequest; read≠write; external disabled today.
- **Provider compromise:** at-least-once + idempotent effect; no exactly-once claim; circuit state + DLQ.
- **No-mutation denial:** fail-closed paths documented (409/4xx, deny-closed for staff/external).
- **Defect found & corrected:** initial security-delivery modelling shared the business outbox; corrected to
  a separate `securitydelivery` owner/pool (ADR-008, View 09).

## PASS 3 — Performance and operability review

- **Request hot paths:** fast-ack path defined; heavy work async (ADR-011, View 14).
- **Bounded queries:** no unbounded findAll; indexed tenant-scoped; cursor pagination; read models for
  dashboards (View 14 rules).
- **Queue/job isolation, tenant fairness, backpressure, retry storms, large payloads:** modelled;
  ProcessingJob + WorkerJobLeaseService are the current foothold.
- **Redis/DB blast radius:** Redis partitioned by operational semantics (ADR-005); one authoritative DB
  (ADR-004); no cross-DB tx.
- **Restore/rollback, observability:** supportcontrol lifecycle/backup (P1-E) + control diagnostics.
- **Future extraction seams:** dashed only; 12-point gate (ADR-012).
- **Defect found & corrected:** an earlier draft of View 02 drew `commerceintelligence` as a writer;
  corrected to projection/read-only edges (`-. reads .->`) to avoid a projection-as-truth gate failure.

## Quality-gate checklist

| Gate | Result |
|---|---|
| Master diagram readable at normal zoom | PASS (module-level, not field-level) |
| Arrows have explicit semantics | PASS (legend per view) |
| Module ownership unambiguous | PASS (one owner per state) |
| No multiple authoritative writers of one state | PASS |
| Provider-specific flows don't duplicate RFQ/Quote/Order/Payment/Approval | PASS (canonical flows) |
| Quote not a god-module reading all repos | PASS (ADR-001/006; de-god in Wave 2) — *current code is PARTIAL (must-never enforced by target)* |
| Work Management not a copy of business state | PASS (ADR-006) |
| AI/connector don't own business decisions | PASS |
| Business vs security messaging separated | PASS (ADR-008) |
| Read/write connector capabilities not conflated | PASS (ADR-007) |
| Accepted audit cannot commit independent of rolled-back business state | PASS (ADR-009/View 12) |
| No one-Redis/DB-per-small-module without evidence | PASS (ADR-004/005) |
| No distributed tx to solve internal modularity | PASS |
| Current vs future visually distinguished | PASS (status labels + dashed [T2]/[S3]) |
| Current-to-target migration map present | PASS (TSV) |
| Enforceable with tests/package rules | PASS (dependency rules) |

## Blockers (STOP-condition check)

- Repository/branch/HEAD established: **YES** (no blocker).
- Unexplained worktree modifications: **NONE**.
- Important source areas unavailable: **NO** (code fully readable).
- Cannot reconcile code with provided audit: the audit/roadmap docs are **absent** — this is the one
  material gap → contributes to **NOT final**, not to BLOCKED (code itself is fully reconciled).
- Incompatible authoritative documents: not observed among **present** docs (the stale "Current Branch"
  block in the checkpoint is superseded by `OPERANT_PRODUCTION_EXECUTION_STATE.md` and real HEAD).
- Owner decision required for data ownership/workflow authority: **YES** (decisions #1, #2, #3 — see
  `OPERANT_ARCHITECTURE_DECISIONS_REQUIRED.md`).
- Architecture requires unrestricted SQL / shell / backdoor / direct AI or connector mutation / client
  authority / premature distributed tx: **NO** — none proposed.

## Final verdict

**CHANGES_REQUIRED** — the target architecture is code-grounded, consistent, enforceable, and
State-2-compatible, and is ready to guide implementation, but it must not be declared the *final* freeze
until owner decisions #1–#3 are confirmed (chiefly the missing three-state roadmap that authoritatively
defines "State 2"). Nothing here is BLOCKED and nothing violates the canonical product law.
