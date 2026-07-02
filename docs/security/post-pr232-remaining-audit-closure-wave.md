# POST-PR232 remaining audit closure wave

Branch: `fix/post-pr232-remaining-audit-closure-wave`  
Base main SHA: `a2a054c4d7808dcd5d0ed76b7a10bf88ac0f6de9` (PR #232 merge)  
Verification date: 2026-07-02

This note classifies closure items from the post-PR232 wave. Claims reference files and tests only.

## Classification key

| Label | Meaning |
|---|---|
| CODE CLOSED | Implemented in code with targeted tests in this branch |
| PARTIAL / PROOF ONLY | Code change and/or existing tests; not full-suite or end-to-end proven |
| PRODUCT DECISION STILL REQUIRED | Needs API/product choice before safe closure |
| INFRA / REPOSITORY SETTING REQUIRED | Cannot be proven from repository code |
| OUT OF SCOPE | Explicitly not attempted in this branch |

---

## A. AI Work typed response / idempotency

| Item | Status | Evidence |
|---|---|---|
| Remove raw `structuredPayloadJson` / `evidenceRefsJson` from public API | CODE CLOSED | `AiWorkDtos.AiWorkSuggestionResponse`; `AiWorkPublicResponseMapper.java`; `AiWorkController.java` |
| Typed projection (`summary`, `displayFields`, `evidence`, `nextActionCandidates`, `riskFlags`) | CODE CLOSED | `AiWorkDtos.java`, `AiWorkPublicResponseMapper.java` |
| Request body must not carry `idempotencyKey` | CODE CLOSED | `CreateAiWorkSuggestionRequest`, `CreateContextualAiWorkSuggestionRequest`; `AiWorkResponseBoundaryTest` |
| Idempotency via `Idempotency-Key` header | CODE CLOSED | `AiWorkController.java`, `ClientIdempotencyKey.java`; `AiWorkControllerAuthorityBoundaryTest` |
| Frontend contract | CODE CLOSED | `lib/ai-work-api.ts`, `components/ai-work-assistant-workspace.tsx`, `lib/rfq-handoff-api.ts`, `components/rfq-handoff-workspace.tsx` (`summary`); `tests/ui-data-boundary.test.mjs` |
| Entity defense-in-depth | PARTIAL / PROOF ONLY | `@JsonIgnore` on `AiWorkSuggestion` sensitive getters; `AiWorkSuggestionSerializationTest` |
| Advisory-only (no trusted writes) | PARTIAL / PROOF ONLY | Existing `AiWorkServiceTest`, `AiWorkControllerAuthorityBoundaryTest`; no new end-to-end write-path test in this branch |

**Remaining:** Richer typed schema for non-deterministic LLM providers (PRODUCT DECISION if provider output shape diverges).

---

## B. Fleet stale-processing recovery audit / metrics

| Item | Status | Evidence |
|---|---|---|
| No tenant HTTP surface for fleet reaper | CODE CLOSED (unchanged) | `WorkerJobLeaseService.recoverSystemWideStaleProcessing`; no controller mapping |
| Bounded recovery | CODE CLOSED (unchanged) | `WorkerRuntimeLifecycleStage29Test` |
| Tenant-attributed audit per recovered job | CODE CLOSED | `AuditEventService.recordForTenant`; `WorkerJobLeaseService`; action `PROCESSING_JOB_STALE_RECOVERED`; `WorkerRuntimeLifecycleStage29Test.staleProcessingRecoveredToFailedFreshUntouchedAndBounded` |
| Metrics counter `processing_jobs_recovered_stale_total` | OUT OF SCOPE | No metrics abstraction added; job row + audit only |

---

## C. Bounded list APIs

| Endpoint / area | Status | Default / max | Evidence |
|---|---|---|---|
| Workspace exception cases | CODE CLOSED | 25 / 100 | `ExceptionCaseService`, `WorkspaceController` |
| Workspace draft quotes / orders | CODE CLOSED | 25 / 100 | `DraftQuoteService`, `DraftOrderService`, `WorkspaceController` |
| Change requests / outbox (v1) | CODE CLOSED | 50 / 100; outbox 20 / 100 | `ChangeRequestService`, `ChangeRequestController` |
| Stage9 integrations / change requests / sync runs | CODE CLOSED | 50 / 100 | `Stage9IntegrationController`, integration services |
| Webhook events list | CODE CLOSED | 50 / 100 | `WebhookEventService`, `WebhookController` |
| Processing jobs | CODE CLOSED (pre-existing) | 25 / 100 | `ProcessingJobService`, `ProcessingJobStatusControlStage28Test` |
| AI Work list by source | CODE CLOSED | limit param + service cap 100 | `AiWorkService.listForSource`, `AiWorkSuggestionRepository` |
| Many other tenant `findByTenantIdOrderBy*` services | PRODUCT DECISION STILL REQUIRED | — | Not migrated in this branch (e.g. `InboundDocumentService.list()`, `ValidationRunService.list()`, bot runtime lists) |
| Dedicated workspace list limit integration tests | PARTIAL / PROOF ONLY | — | `TenantScopedListLimitsTest`; no new controller test per workspace list |

---

## D. Support / internal grant lifecycle

| Item | Status | Evidence |
|---|---|---|
| Route permission matrix | PARTIAL / PROOF ONLY (pre-existing) | `SupportAccessRoutePermissionTest`, `InternalSupportVisibilityBoundaryTest`, `IncidentBreakGlassRoutePermissionTest` |
| Service grant expiry / wrong-tenant / scope | PARTIAL / PROOF ONLY (pre-existing) | `SupportAccessServiceTest`, `SupportGrantApprovalServiceTest`, `SupportTenantLocatorServiceTest` |
| New tests added in this branch | OUT OF SCOPE | No new support tests in this wave |
| Staff SSO / BFF | PRODUCT DECISION STILL REQUIRED | Frontend fail-closed by design |

---

## E. Entity defense-in-depth

| Item | Status | Evidence |
|---|---|---|
| `AiWorkSuggestion` serialization guards | CODE CLOSED | `AiWorkSuggestion.java`, `AiWorkSuggestionSerializationTest` |
| Broad high-risk entity sweep | OUT OF SCOPE | DTO boundary remains primary; no sweep on `ExtractionResult`, `OutboxEvent`, etc. |

---

## F. CI / repository settings

| Item | Status | Evidence |
|---|---|---|
| Workflow / Dockerfile gates unchanged | PARTIAL / PROOF ONLY | `docs/security/ci-supply-chain-gates.md` (pre-existing); no workflow edits in this branch |
| Branch protection / CodeQL Default Setup / Snyk required vs advisory | INFRA / REPOSITORY SETTING REQUIRED | See section below |

### Repository settings that require manual GitHub verification

- Branch protection required checks (exact names must match `CI / Backend tests`, `Frontend / build`, etc.)
- CodeQL Default Setup enabled state (no in-repo `codeql.yml` by design)
- Whether Snyk is required or advisory on PRs
- Dismiss stale reviews / admin bypass policies

---

## Commands used for verification (verification pass)

See verification pass final report for executed commands and outcomes.
