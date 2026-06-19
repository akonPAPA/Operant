# OP-CAP-38/COORD Local AI Review + Metrics Report

Generated: 2026-06-20. Reviews OP-CAP-37 (non-executed ChangeRequest candidate) at commit
`5103aa1`. Local AI output is **advisory only**; the repository + targeted tests are the
source of truth. No production code was modified during Stage B. No connector execution was
enabled.

---

## 1. Inputs

- **Branch:** `OP-CAP-37-Draft-Assembled-Controlled`
- **Diff ref reviewed:** `5103aa1` (`prepare non-executed quote external sync candidate`)
- **git status (Stage B start):** clean working tree except untracked Stage A/B artifacts
  (`docs/product/op-cap-roadmap-sync.md`, `docs/runbooks/local-ai-review.md`,
  `scripts/local-ai-review/`, this report).
- **Files reviewed (allowlisted, scoped diff only — no secrets/.env):**
  - `apps/core-api/.../api/dto/Stage12CDtos.java`
  - `apps/core-api/.../application/services/integration/ChangeRequestService.java`
  - `apps/core-api/.../application/services/workspace/QuoteReviewService.java`
  - `apps/core-api/.../test/.../workspace/QuoteReviewServiceTest.java`
  - `apps/core-api/.../test/.../api/rest/QuoteReviewControllerTest.java`
  - `apps/core-api/.../test/.../workspace/QuoteDraftServiceStage12ATest.java`
- **Input package size:** 27.7 KB (file names + scoped diff + Stage A summary + verified test
  results). Verified to contain no `.env`, credentials, private keys, raw customer data, or
  build output.
- **Tests/results reviewed:** backend targeted 63/63 pass; frontend cockpit 20/20 pass.
- **Models used (installed, `ollama list`):** `qwen3-coder:30b`, `deepseek-r1:32b`,
  `qwen3:30b`.
- **Models unavailable:** none missing; one model (`deepseek-r1:32b`) failed at runtime — see §3.

---

## 2. Stage A Verification Summary

| Property | Result | Evidence |
| --- | --- | --- |
| OP-CAP-37 status | **done** | code + 63/63 targeted tests pass |
| externalExecution state | **DISABLED** | `QuoteDraftSummary.externalExecution="DISABLED"`; candidate `executionStatus="EXECUTION_DISABLED"` (ChangeRequest 10-arg ctor default) |
| connector call | **no** | `prepareQuoteExternalSyncCandidate` calls only `repository.save` + `auditEventService.record`; demo executor requires `DEMO_ERP`, candidate target is `INTERNAL_SYNC_CANDIDATE` |
| executable outbox | **no** | `prepare...` does not call `emit()`; test asserts `outboxEvents` empty and `connectorSyncEvents` empty |
| audit | **proven** | `QUOTE_DRAFT_EXTERNAL_SYNC_CANDIDATE_CREATED`/`_REUSED`; test asserts action present with metadata |
| idempotency | **proven** | deterministic key `opcap37:quote-external-sync-candidate:<tenant>:<quote>`; DB unique `uq_change_request_tenant_idempotency_key (tenant_id, idempotency_key)` (V10); `repeatedAssembleDoesNotDuplicateActiveCandidate` |
| cross-tenant | **proven** | `assembleDraftCrossTenantAccessBlocked` (NotFound on foreign quote → no candidate) |
| malicious payload | **proven** | `assembleDraftUsesTrustedActorIgnoresClientAuthorityAndReturnsSafeSummary` (controller) — body authority/state ignored, server-resolved actor |
| response leak | **proven** | controller asserts only safe summary fields; DTO adds `externalSyncCandidateStatus` only (no candidate id / target / connector data) |

**Conclusion:** Stage A is complete and safe. No P0 blockers. Production code unchanged in
this task (Stage A was already committed; only the roadmap sync-lock doc was added).

---

## 3. Local AI Reviewer Results

### Reviewer 1 — qwen3-coder:30b (code-level review) — **RUN OK**

- **Run status:** OK (first attempt)
- **Wall duration:** 153.2 s (`total_duration` 152.89 s; `load_duration` 28.16 s)
- **Tokens:** prompt_eval 6957 (92.88 s ≈ 74.9 tok/s); eval 598 (31.67 s ≈ 18.9 tok/s)
- **Memory observed (Get-Process WS):** ollama launcher 86.9→85 MB, java 15.8 MB (model
  runs in a separate runner subprocess + GPU not captured by name — see §4)
- **P0 blockers:** none.
- **P1 risks:** none material — confirmed tenant isolation, idempotency, audit, neutral
  target / no-outbox as correctly implemented and tested.
- **Missing tests (suggested):** (a) re-assemble after an escalation/approval is later
  cleared back to assembled; (b) more explicit assertion of
  `externalSyncCandidateStatus="PENDING_INTERNAL_APPROVAL"` under varied approval states.
- **False positives suspected:** none.
- **Useful findings:** accurate restatement of the safety contract; the two missing-test
  suggestions are legitimate small gaps.
- **Verdict:** Safe to proceed: **yes**.

### Reviewer 2 — deepseek-r1:32b (root-cause / business-logic) — **RUN FAILED (×2)**

- **Run status:** FAILED both attempts.
  - Attempt 1 (in-harness): failed at **268.8 s** — "The underlying connection was closed".
  - Attempt 2 (isolated retry, reduced `num_ctx=6144`, `num_predict=1500`): failed at
    **663.7 s** — same connection-closed error.
- **Root cause (observed):** the 32B model run **took down the local Ollama server** — the
  post-failure `Get-Process` snapshot shows the `ollama` process absent / collapsed to ~13 MB
  WS, and the *next* model (qwen3:30b) then failed with "unable to connect". This is a local
  resource/stability limit, not a code defect in the slice under review.
- **Output:** none produced (no `thinking`, no `response`, no token metadata).
- **Decision:** recorded as failed; continued with available reviewers per harness policy
  (missing/failed models are reported, never auto-pulled, never block the gate).

### Reviewer 3 — qwen3:30b (product/security stage-gate) — **RUN OK (on retry)**

- **Run status:** first attempt FAILED ("unable to connect" at 4.1 s — collateral of the
  deepseek-r1:32b server crash above). Isolated retry after server auto-restart: **OK**.
- **Wall duration (retry):** 131.9 s (`total_duration` 131.59 s; `load_duration` 24.94 s)
- **Tokens:** prompt_eval 6903; eval 1289 (66.13 s ≈ 19.5 tok/s)
- **Output:** full reasoning trace; the final "Answer" block was truncated by the
  `num_predict` budget (reasoning models spend budget thinking). The captured reasoning works
  through every gate criterion (no external execution, tenant isolation, backend-owned
  authority, idempotency, audit, response safety, no overengineering) and concludes the slice
  is "correctly implemented as a safe non-executed ChangeRequest candidate layer."
- **P0 / required fixes:** none raised.
- **False positives suspected:** none.
- **Verdict (from reasoning):** effectively **Accepted** as a safe non-executed candidate
  layer. (Caveat: the explicit "Accepted/Not accepted" line was not emitted before the token
  budget cut off; verdict inferred from the complete reasoning.)

**Reviewer coverage:** 2 of 3 models produced usable review (code + product/security). The
business-logic reviewer (deepseek-r1:32b) could not run locally on this machine.

---

## 4. Performance / Memory Measurements

| Measurement | Value |
| --- | --- |
| Backend targeted tests (`Measure-Command`, 63 tests) | **28.84 s** wall |
| Frontend cockpit test (20 tests) | **116.3 ms** internal (~0.3 s wall) |
| qwen3-coder:30b run | 153.2 s (load 28.16 s) |
| deepseek-r1:32b run | FAILED ×2 (268.8 s, 663.7 s) |
| qwen3:30b run (retry) | 131.9 s (load 24.94 s) |

**Process memory observations:**

- `Get-Process -Name ollama,java,node` captured only the Ollama **launcher/server** process
  WS (13–87 MB) and the test `java` process. The actual model weights load into a separate
  Ollama **runner** subprocess (and GPU), which is not captured by the `ollama` name — so the
  WS numbers understate true model memory. This is a known limitation of name-based process
  memory sampling and is recorded honestly rather than inflated.
- **Critical qualitative observation:** after the `deepseek-r1:32b` run the `ollama` process
  **disappeared** from `Get-Process` (server crash), and `java` WS also dropped — i.e. a
  single 32B inference at this context/output budget exhausted local resources enough to take
  down the Ollama server.

**Timeouts / failures:** deepseek-r1:32b failed twice (connection closed); qwen3:30b's first
attempt failed as collateral, then succeeded in isolation after the server restarted.

**Notes on running 30B/32B models locally:**

- The two 30B models (`qwen3-coder:30b`, `qwen3:30b`) ran successfully one-at-a-time in
  ~130–155 s each (cold load ~25–28 s).
- The 32B model (`deepseek-r1:32b`) was **not reliably runnable** for this 27.7 KB package at
  `num_ctx≥6144` on this machine — it crashed the server both times.
- **Sequential execution is mandatory** and was honored; two large models were never
  co-resident. Even sequentially, the 32B crash then blocked the following model until the
  server restarted.

**Is local model review acceptable for this workflow?** **Yes, with a caveat.** The 30B
models give fast, on-target, cheap second-opinion review and are acceptable as an advisory
local gate. The 32B model is currently unreliable on this hardware and should either be
dropped from the rota or run with a much smaller context/output budget (harness hardening —
see §10), until the AI Model Runtime Foundation slice formalizes resourcing.

---

## 5. Business Logic Scorecard

Evidence-based. Scores reflect what is proven by code + tests, not aspiration.

| Dimension | Score | Evidence | Not proven / to raise |
| --- | --- | --- | --- |
| ChangeRequest candidate lifecycle correctness | **90** | PREPARED on clean assemble; PENDING_INTERNAL_APPROVAL + no candidate when approval required; dedup → reuse. Tests: `...PreparesTenantScopedNonExecutedSyncCandidate`, `assembleRequiringApprovalDoesNotPrepareCandidate`, `repeatedAssemble...` | No test for re-assemble after escalation later cleared back to assembled. |
| External-write safety | **95** | Neutral `INTERNAL_SYNC_CANDIDATE` target (executor requires `DEMO_ERP`); no `emit()`/outbox; negative-proof asserts `outboxEvents`/`connectorSyncEvents` empty; `executedAt`/`externalReference` null | Full demo-executor refusal of `INTERNAL_SYNC_CANDIDATE` is reasoned, not separately unit-tested. |
| Tenant / authority boundary | **90** | `TenantContext.requireTenantId()`; cross-tenant test; backend-owned tenant/actor/status; malicious-override controller test | RBAC role-matrix breadth out of scope for this slice. |
| DTO / request / response safety | **88** | DTO adds only `externalSyncCandidateStatus`; controller asserts no candidate id / target / connector data; intent-only request | No automated schema/serialization snapshot test of the full response. |
| Idempotency / concurrency safety | **82** | Deterministic per-quote key + DB unique `uq_change_request_tenant_idempotency_key`; dedup test → duplicates structurally impossible | Graceful concurrent-race handling (constraint-violation loser → reuse vs error) not explicitly tested. |
| Audit correctness | **85** | CREATED/REUSED actions with tenant/quote/candidate/status metadata; test asserts action present | Field-by-field audit metadata assertion not added. |
| Business workflow correctness | **85** | Assemble → candidate → audit flow matches the safe core path; pending-approval defers candidate | End-to-end workflow (review→assemble→candidate) proven at service/controller layer, not via browser E2E. |
| Test proof strength | **85** | 63 backend (incl. negative proofs) + 20 frontend targeted, all green | Full suite, Postgres integration concurrency, and E2E not run for this slice. |
| Runtime / performance risk | **88** | O(1) key build; tenant+key scoped lookup (indexed); one extra save + audit; no loops/batch/new threads | No load test; single candidate per assemble. |
| Memory / resource risk (the slice) | **90** | No new in-memory structures, caches, or large payloads; snapshot JSON is small and server-built | n/a (the harness/32B memory issue is tooling, not the slice). |
| Complexity / maintainability | **85** | Minimal: one DTO field, one narrow service method, clear safety comments; reuses existing `ChangeRequest`/`ChangeRequestService` | New field threaded through controller→service→DTO; acceptable. |
| Production readiness (this layer) | **60** | Safe non-executed candidate layer is production-leaning and test-proven | Real approval/execution guard, connector policy/sandbox/dry-run, concurrent-race handling, RBAC breadth, and E2E/pentest are downstream gates — not built here. |

---

## 6. Complexity / Trade-off Review

- **Necessary complexity:** the `externalSyncCandidateStatus` field and the narrow
  `prepareQuoteExternalSyncCandidate` method — minimal surface needed to expose a safe
  candidate handle and dedup it.
- **Parasite complexity:** none identified by reviewers or inspection. The slice reuses the
  existing `ChangeRequest` model/service rather than inventing a parallel candidate subsystem.
- **Deferred complexity (intentional):** approval/execution guard, connector capability
  policy / sandbox / dry-run, and real connector enablement are explicitly deferred to later
  gates. Candidate is created only on the clean (no-approval-pending) path this slice.
- **Dangerous simplification risks:** (a) the server-built snapshot JSON is hand-concatenated
  (string building) rather than serialized via a mapper — low risk now (operator-safe fields,
  `escape()` applied), but should move to a typed serializer before the payload feeds any real
  connector; (b) concurrent double-assemble relies on the DB unique constraint to prevent
  duplicates — safe, but the loser currently surfaces an error rather than a graceful reuse.
- **Recommended next bounded slice:** AI Model Runtime Foundation (per roadmap), with a small
  harness-hardening follow-up for the 32B reliability issue.

---

## 7. Weak Spots

**P0 — must fix before proceeding:** none.

**P1 — should fix soon:** none material. (Both running reviewers returned no P0/P1; inspection
agrees.)

**P2 — acceptable later:**
- Move the candidate snapshot from hand-built JSON string to a typed serializer before any
  real connector consumes it.
- Add explicit graceful handling/test for concurrent double-assemble
  (constraint-violation loser → return existing candidate instead of erroring).
- Add the two reviewer-suggested tests (re-assemble after cleared escalation; explicit
  `PENDING_INTERNAL_APPROVAL` status assertion).

**Not proven — requires future test/audit:**
- True concurrent-race behavior (parallel threads) — only deterministic single-thread dedup
  proven; DB unique constraint makes duplicates impossible but graceful handling untested.
- Field-by-field audit metadata content.
- Browser E2E of the review→assemble→candidate path.
- Business-logic deep review by deepseek-r1:32b (model could not run locally).

---

## 8. What Is Strong (proven by code/tests)

- External-write safety: neutral target + no outbox/connector + negative-proof tests.
- Tenant isolation and backend-owned authority (cross-tenant + malicious-override tests).
- Idempotency: deterministic key backed by a DB unique constraint; dedup + reuse audit.
- Response safety: operator-safe DTO, no internal ids/targets/connector data.
- Minimal, contained complexity; reuse of existing ChangeRequest model/service.
- 63/63 backend + 20/20 frontend targeted tests green.

---

## 9. What Not To Claim Yet

- Production ERP/1C external-write readiness — **not** built (deliberately disabled).
- Autonomous bot readiness — out of scope.
- Production AI/OCR accuracy — not evaluated here.
- Full RBAC/SSO readiness — only the candidate path's authority boundary is proven.
- Full pentest readiness — not performed.
- Full production observability / DR — not in scope.
- Full browser E2E readiness — service/controller proofs only.
- Reliable local 32B model review — `deepseek-r1:32b` failed twice on this machine.

---

## 10. Next Prompt Recommendation

Stage A (OP-CAP-37) is clean (no P0). Per the accepted roadmap order the next gate is **AI
Model Runtime Foundation**, with a small harness-hardening note folded in (the 32B reviewer
failed locally). Exact bounded next prompt:

```
Use /orderpilot-task.
Task: AI Model Runtime Foundation (post OP-CAP-38/COORD) — bounded slice.
Repository: C:\OrderPilot\OrderPilot-Core
Stage gate: OP-CAP-37 (non-executed ChangeRequest candidate) is accepted and test-proven;
OP-CAP-38/COORD local AI review gate is complete (2/3 local reviewers ran; deepseek-r1:32b
failed locally on resource limits). Real external connector execution remains DISABLED.

Objective: define the minimal, bounded AI Model Runtime Foundation that lets OrderPilot
treat local/remote AI models as *advisory reviewers only* (never authority, never writers),
with: a single model-runtime config abstraction (model id, role, bounded context/output,
sequential execution, timeout), capture of run metadata, and an explicit "advisory-only,
no business-table write, no connector call" guard. Do NOT build a generic AI platform, no
new infrastructure (no Kafka/ClickHouse/OpenSearch), no agent auto-edit loop, no parallel
model loading.

Harness-hardening sub-task (carry-over): make the local review harness resilient to a model
that crashes the Ollama server — detect server loss, restart-or-skip, and cap 32B context/
output budget (or drop 32B from the default rota) so one failed model never blocks the rest.

Hard constraints: no connector execution; no AI-to-business-table write; no frontend-to-DB
path; no secrets in prompts/logs/reports; preserve tenant isolation, audit, idempotency,
external-write safety; no broad refactor. Targeted tests only. Report in the OrderPilot
task output format.
```
