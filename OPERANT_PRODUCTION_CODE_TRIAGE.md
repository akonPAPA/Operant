# OPERANT PRODUCTION CODE TRIAGE — v6 FINAL (Full Audit Completion)

## 0. Closure Status

- **Total production files**: 1169 (`46 + 425 + 69 + 49 + 303 + 66 + 1 + 151 + 39 + 13 + 7 = 1169` ✓)
- **Status counts**: AUDITED_LINE_BY_LINE 1164 + SKIPPED 5 = 1169 ✓; NOT_AUDITED 0
- **C AI Worker**: exact **39** (boundary `±2` resolved — see manifest "AI Worker Boundary Resolution"); 2 excluded = `apps/ai-worker/Dockerfile` (→E), `apps/ai-worker/README.md` (doc)
- **`.audit_git_files.txt`**: deleted (confirmed absent)
- **Final status**: **COMPLETE**

## 1. P0 ID Reconciliation

| ID | Title | Class | Status | Confirmed P0? | Caller/Exposure Path Proven? |
|---|---|---|---|---|---|
| P0-001 | WorkspaceController ~32 methods return domain entities | Runtime/API | CONFIRMED | Yes | Yes — controller directly returns JPA entities via 32 endpoints |
| P0-002 | ValidationWorkspaceActionController 3 methods return entities | Runtime/API | CONFIRMED | Yes | Yes |
| P0-003 | ValidationReviewController 2 methods return entities | Runtime/API | CONFIRMED | Yes | Yes |
| P0-004 | Cross-tenant JPQL stale reaper | Cross-tenant | CONFIRMED | Yes | Yes — product decision |
| P0-005 | @JsonIgnore secretReferenceId in Stage12Dtos | Data leak | CONFIRMED | Yes | Partial — DTO field declared |
| P0-006 | @JsonIgnore entity fields belt-and-suspenders | Defense-in-depth | — | No | No proven active exposure path |
| P0-007 | @JsonIgnore entity secretRef | Data leak | CONFIRMED | Yes | Yes — via entity getter + controller entity return |
| P0-008 | IncidentAlertRecordRepository no tenantId | Cross-tenant | CONFIRMED | Yes | Yes — unscoped findByIncidentId |
| P0-009 | BreakGlassAccessRequestRepository no tenantId | Cross-tenant | CONFIRMED | Yes | Yes |
| P0-010 | ChangeRequest entity getters | Reclassified P1 | — | No | Not active P0 after reclassification |
| P0-011 | NEXT_PUBLIC_DEMO_TENANT_ID | Demo architecture | CONFIRMED | Yes | Yes — demo limitation |
| **P0-012** | **ExtractionValidationController entity return** | **Runtime/API** | **CONFIRMED** | **Yes** | **Yes — L23** |

**Final: 10 confirmed Runtime/API/Business P0. 2 CI/Supply-Chain CRITICAL (CI-001, CI-002).**

## 2. DTO Finding Reconciliation (Summary)

| DTO File | Field | Request/Response | Controller Usage? | Classification |
|---|---|---|---|---|
| Stage12Dtos | secretRef, secretValue | Request | Yes | P0 — client authority |
| Stage12Dtos | secretReferenceId | Response | Yes | P0 — data leak |
| Stage11EDtos | payloadJson, payloadHash, idempotencyKey | Response | Yes | P0 — data leak |
| Stage11EDtos | executionStatus | Response | Yes | P1 — internal status leak |
| Stage10DOmnichannelDtos | linkedByUserId | Request | Yes | P0 — client authority |
| Stage7Dtos | reviewedBy | Request+Response | Yes | P0 — client authority + leak |
| Stage10BDtos | correctedByUserId | Request+Response | Yes | P0 — client authority |
| Stage9Dtos | requestPayloadJson | Request | Yes | P0 — client authority |
| Stage9Dtos | secretReferenceId, connectorFailureType | Response | Yes | P0/P1 — leak |
| Stage3Dtos | sha256Fingerprint, fingerprintSha256 | Response | Yes | P1 — content hash leak |
| Stage2Dtos | createdBy (request), tenantId (response) | Both | Yes | P1 |
| Stage8Dtos | tenantId (x15 records) | Response | Yes | P1 |
| Stage11ADtos | actorId, actorRole | Request | Partial | P1 — client authority |
| Stage12ADtos | actorId, decidedBy, auditCorrelationId | Response | Yes | P1 — leak |
| AiMemoryDtos | createdBy, actorId | Response | Yes | P1 — leak |
| TrustAnalyticsDtos | tenantId | Response | Yes | P1 |
| BotRuntimeConfigurationDtos | externalExecution | Response | Yes | P1 |
| 36 remaining DTOs | Various | — | Yes | P1/P2 mixed |

## 3. Entity Getter Reconciliation (Summary)

| Entity | Field | Class | Direct Serialization Path? | Finding |
|---|---|---|---|---|
| ChannelConnection | secretRef, secretReferenceId | P0 leak | Via controller entity return | DOM-001 |
| IntegrationConnection | secretRef, secretReferenceId | P0 leak | Via controller entity return | DOM-002 |
| OutboxEvent | payloadJson | P0 leak | — | DOM-003 |
| ConnectorCommand | payloadJson, idempotencyKey | P0 leak | — | DOM-004 |
| QuoteHandoffSnapshot | payloadJson, payloadHash, idempotencyKey, generatedBy | P0/P1 leak | — | DOM-005 |
| DraftQuote | approvedBy, auditCorrelationId, idempotencyKey | P1 leak | Via WorkspaceController | DOM-ENT-011 |
| QuoteApprovalDecision | decidedBy, auditCorrelationId, resolvedReasonsJson | P1 leak | — | DOM-ENT-004 |
| QuoteConversionAttempt | validationSummaryJson, triggeredBy, idempotencyKey | P1 leak | — | DOM-ENT-003 |
| QuoteInternalOrderBoundary | idempotencyKey, createdBy, externalExecutionStatus | P1 leak | — | DOM-ENT-005 |
| AiWorkSuggestion | structuredPayloadJson, createdByUserId, decidedByUserId | P0/P1 leak | — | DOM-ENT-009 |
| ExtractionResult | resultJson | P0/P1 leak | Via ExtractionValidationController | DOM-ENT-007 |
| AiSuggestion | suggestionJson | P0/P1 leak | — | DOM-ENT-008 |
| PromptTemplateVersion | all fields (zero getters) | — | Unscoped entity | DOM-ENT-001 |
| 160 remaining entities | tenantId (P2), various | — | — | LOW defense-in-depth |

## 4. Actor Authority Reconciliation

| Controller | Request DTO | Service | Actor Source | Used For | Backend-Resolved? | Classification |
|---|---|---|---|---|---|---|
| DraftQuoteController | CreateDraftQuoteFromRfqRequest | QuoteDraftService | actorId from body | Audit + entity ownership | No — body field | HIGH (SRV-001) |
| DraftQuoteController | CreateDraftQuoteFromRfqRequest | RfqToDraftQuoteService | actorId+actorRole from body | Policy evaluation + entity creation | No — body field | HIGH (SRV-002) |
| DraftReviewController | DraftLineCorrectionRequest | DraftReviewService | actorUserId from body | Status transitions | No — body field | HIGH (SRV-003) |
| ApprovalController | ApprovalDecision request | ApprovalWorkflowService | decidedBy caller parameter | Approval recording | No — caller parameter | MEDIUM |
| ChangeRequestController | ChangeRequestCreateRequest | ChangeRequestService | createdByUserId caller param | Change request creation | No — caller parameter | MEDIUM |
| OperatorController | OperatorActionRequest | OperatorActionService | actorUserId caller param | Action recording | No — caller parameter | MEDIUM |
| QuoteController | QuoteExternalWriteRequest | QuoteExternalWritePreparationService | actorId from body | Handoff preparation | No — body field | HIGH |
| ChannelIdentityController | ChannelIdentityLinkRequest | channel services | linkedByUserId from body | Identity linking | No — body field | HIGH (FE-014) |
| All controllers | Via RequestActorResolver | — | X-OrderPilot-Actor-Id header | Audit actor resolution | **Yes — trusted header** | Safe pattern exists but not universally used |

**Root cause**: `RequestActorResolver` exists (resolves from trusted `X-OrderPilot-Actor-Id` header with optional HMAC signature verification) but 7+ services bypass it, accepting actorId/actorUserId from the request body or caller parameter instead.

## 5. Cross-Tenant Repository Reconciliation

| Repository | Method | Tenant Scoped? | Caller Path Proven? | Impact | Classification |
|---|---|---|---|---|---|
| IncidentAlertRecordRepository | findByIncidentId | No | Yes — `IncidentResponseService` may call cross-tenant | Read cross-tenant alert records | P0-008 |
| BreakGlassAccessRequestRepository | findByIncidentId | No | Partial | Read cross-tenant break-glass records | P0-009 |
| PromptTemplateVersionRepository | inherited findAll/findById | No | Yes — no tenant filter on CRUD | Global template leak | DOM-REPO-001/HIGH |
| OrderJourneyTrackingLinkRepository | findByTokenHash | No (intentional) | Yes — public token lookup | Token hash is crypto lookup, not data leak | LOW |
| All other 165 repositories | All methods | Yes — tenantId parameter present | Verified | No cross-tenant risk | CLEAN |

## 6. Fix Waves

### wave-01 — CI/Infrastructure (4 items)
1. CI-001: Remove -DskipTests from core-api Dockerfile
2. CI-002: Make Snyk blocking (remove continue-on-error:true)
3. Fix snyk-infrastructure.yml path filter filename mismatch
4. Pin GitHub Actions to commit SHA (all 9 workflow files)

### wave-02 — Controller Entity Returns (4 items)
1. P0-001: WorkspaceController → DTO mapping (32 methods)
2. P0-002: ValidationWorkspaceActionController → DTO mapping (3 methods)
3. P0-003: ValidationReviewController → DTO mapping (2 methods)
4. P0-012: ExtractionValidationController → DTO mapping (1 method)

### wave-03 — Actor Authority Resolution (7 items)
1. SRV-001/002/003: Use RequestActorResolver instead of body, across QuoteDraftService, RfqToDraftQuoteService, DraftReviewService
2. Fix QuoteExternalWritePreparationService (actorId from body)
3. Fix OperatorActionService (actorUserId parameter)
4. Fix ApprovalWorkflowService (decidedBy parameter)
5. Fix ChangeRequestService (createdByUserId/approvedByUserId parameters)
6. Remove linkedByUserId from channel-identity request body (FE-014)
7. Generalize: all services use trusted header resolution

### wave-04 — Cross-Tenant Repositories (3 items)
1. P0-008: Add tenantId to IncidentAlertRecordRepository.findByIncidentId
2. P0-009: Add tenantId to BreakGlassAccessRequestRepository
3. DOM-REPO-001: Tenant-scope PromptTemplateVersionRepository

### wave-05 — DTO Response Leaks (prioritized)
1. Remove secretRef/secretValue from request DTOs (Stage12Dtos)
2. Remove payloadJson/payloadHash/idempotencyKey/executionStatus from response DTOs (Stage11EDtos)
3. Remove reviewedBy from request+response (Stage7Dtos)
4. Remove correctedByUserId from request (Stage10BDtos)
5. Remove requestPayloadJson acceptance (Stage9Dtos/Stage10CDtos)
6. Remove linkedByUserId from request (Stage10DOmnichannelDtos)
7. Remove actorId/actorRole from request (Stage11ADtos)
8. Remove decidedBy/auditCorrelationId from response (Stage12ADtos)
9. Remove tenantId from response DTOs where unnecessary (Stage8Dtos x15, TrustAnalyticsDtos)
10. Remove secretReferenceId/connectorFailureType from response (Stage9Dtos)
11. Remove sha256Fingerprint from response (Stage3Dtos)
12. Remove createdBy from response (Stage2Dtos)
13. Remove actorId/createdBy from memory/trust response DTOs

### wave-06 — Frontend Response Types (5 items)
1. Remove linkedByUserId from ChannelIdentityLinkRequest (FE-014/HIGH)
2. Remove decidedBy from QuoteApprovalState response type (FE-017/HIGH)
3. Remove approvalStatus/executionStatus/connectorIdempotencyKeyHash from Stage9ChangeRequest (FE-019/HIGH)
4. Remove actorId from AuditTimelineItem response types (FE-015/MEDIUM)
5. Remove createdBy/reviewedBy/auditCorrelationId from 18 response types

### wave-07 — Entity Defense-in-Depth (2 items)
1. Add @JsonIgnore to all entity secretRef/secretReferenceId/payloadJson getters
2. Add @Version to mutable workflow entities (DraftQuote, DraftQuoteLine, AiMemoryRecord)

### wave-08 — Remaining Service/Performance (3 items)
1. Fix unbounded list() queries (17+ methods) — add Pageable limits
2. Complete controller→DTO mapping verification for remaining entity-returning services
3. Add missing audit events on mutation paths identified by SRV findings

## 7. Do Not Fix Yet

| Category | Reason |
|---|---|
| P0-004 (stale reaper) | Product decision needed |
| P0-006 (@JsonIgnore belt-and-suspenders) | Defense-in-depth; no proven active exposure |
| P0-011 (NEXT_PUBLIC_DEMO_TENANT_ID) | Demo architecture limitation; requires auth BFF/session infrastructure |
| External excution enablement | Connector worker runtime not present; all stages EXECUTION_DISABLED by design |
