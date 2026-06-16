package com.orderpilot.application.services.aiwork;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.runtime.RuntimeFeatureType;
import com.orderpilot.application.services.runtime.RuntimeGuardRequest;
import com.orderpilot.application.services.runtime.RuntimeGuardService;
import com.orderpilot.application.services.runtime.RuntimeOperationType;
import com.orderpilot.application.services.runtime.RuntimeUnitEstimateRequest;
import com.orderpilot.application.services.runtime.RuntimeUnitEstimator;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.aiwork.AiWorkSourceType;
import com.orderpilot.domain.aiwork.AiWorkStatus;
import com.orderpilot.domain.aiwork.AiWorkSuggestion;
import com.orderpilot.domain.aiwork.AiWorkSuggestionRepository;
import com.orderpilot.domain.aiwork.AiWorkType;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-07A AI Agent Work Layer application service.
 *
 * <p>Safety model — AI suggests, rules validate, human approves if risky, backend writes, audit
 * records. This service only ever creates and decides ADVISORY suggestion rows. It performs no
 * quote/order/inventory/pricing/customer mutation and no external/connector write. Accepting a
 * suggestion records operator intent only; downstream workflows must call existing typed command
 * services explicitly to effect any real business change.
 *
 * <p>Every read and write is tenant-scoped via {@link TenantContext}; cross-tenant access is not
 * reachable. Suggestion creation and every accept/reject decision emit an audit event.
 */
@Service
public class AiWorkService {
  private static final int MAX_RECENT_LIMIT = 100;

  private final AiWorkSuggestionRepository repository;
  private final AiWorkProvider provider;
  private final AuditEventService auditEventService;
  private final RuntimeGuardService runtimeGuardService;
  private final RuntimeUnitEstimator runtimeUnitEstimator;
  private final Clock clock;

  public AiWorkService(
      AiWorkSuggestionRepository repository,
      AiWorkProvider provider,
      AuditEventService auditEventService,
      RuntimeGuardService runtimeGuardService,
      RuntimeUnitEstimator runtimeUnitEstimator,
      Clock clock) {
    this.repository = repository;
    this.provider = provider;
    this.auditEventService = auditEventService;
    this.runtimeGuardService = runtimeGuardService;
    this.runtimeUnitEstimator = runtimeUnitEstimator;
    this.clock = clock;
  }

  @Transactional
  public AiWorkSuggestion createSuggestion(
      AiWorkType workType,
      AiWorkSourceType sourceType,
      UUID sourceId,
      String contextText,
      String idempotencyKey,
      UUID createdByUserId) {
    UUID tenantId = TenantContext.requireTenantId();
    if (workType == null) throw new IllegalArgumentException("work_type is required");
    if (sourceType == null) throw new IllegalArgumentException("source_type is required");
    if (sourceId == null) throw new IllegalArgumentException("source_id is required");

    String normalizedKey = normalize(idempotencyKey);
    if (normalizedKey != null) {
      // Retry-safe create: a repeated idempotency key returns the existing advisory suggestion
      // without re-generating or re-auditing.
      var existing = repository.findByTenantIdAndIdempotencyKey(tenantId, normalizedKey);
      if (existing.isPresent()) {
        return existing.get();
      }
    }

    // OP-CAP-16G runtime guard: entitlement -> quota -> rate, BEFORE the advisory AI explanation/
    // summary provider call. A denial throws a stable mapped exception (403 feature/quota, 429 rate)
    // and the provider is never invoked and no suggestion row is created. This runs after the
    // idempotency short-circuit above, so a retried key returns the existing suggestion without
    // consuming guard budget. Requested units are estimated cheaply from the already-in-memory
    // context line count (no parsing, no I/O); absent context falls back to 1.
    int requestedUnits =
        runtimeUnitEstimator.estimate(
            RuntimeUnitEstimateRequest.forExplanation(tenantId, lineCountOf(contextText), null));
    runtimeGuardService.enforce(
        RuntimeGuardRequest.of(
            tenantId, RuntimeOperationType.AI_VALIDATION_EXPLANATION, requestedUnits),
        RuntimeFeatureType.AI_VALIDATION_EXPLANATION);

    AiWorkGenerationResult generated =
        provider.generate(new AiWorkGenerationRequest(tenantId, workType, sourceType, sourceId, contextText));

    AiWorkSuggestion suggestion = new AiWorkSuggestion(
        tenantId,
        workType,
        sourceType,
        sourceId,
        generated.strategyVersion(),
        generated.riskLevel(),
        generated.confidence(),
        generated.generatedText(),
        generated.structuredPayloadJson(),
        generated.evidenceRefsJson(),
        normalizedKey,
        createdByUserId,
        clock.instant());
    AiWorkSuggestion saved = repository.save(suggestion);
    auditEventService.record(
        "AI_WORK_SUGGESTION_CREATED", "AI_WORK_SUGGESTION", saved.getId().toString(),
        createdByUserId, auditJson(saved, null));
    return saved;
  }

  /**
   * Operator accepts an advisory suggestion. Idempotent when already ACCEPTED. Rejected suggestions
   * cannot be accepted. This NEVER approves a quote/order/discount/substitute or triggers a write.
   */
  @Transactional
  public AiWorkSuggestion accept(UUID id, UUID decidedByUserId, String reason) {
    UUID tenantId = TenantContext.requireTenantId();
    AiWorkSuggestion suggestion = lockedForDecision(id, tenantId);
    if (AiWorkStatus.ACCEPTED.name().equals(suggestion.getStatus())) {
      return suggestion;
    }
    if (AiWorkStatus.REJECTED.name().equals(suggestion.getStatus())) {
      throw new IllegalArgumentException("AI work suggestion was already rejected");
    }
    suggestion.accept(decidedByUserId, reason, clock.instant());
    AiWorkSuggestion saved = repository.save(suggestion);
    auditEventService.record(
        "AI_WORK_SUGGESTION_ACCEPTED", "AI_WORK_SUGGESTION", saved.getId().toString(),
        decidedByUserId, auditJson(saved, reason));
    return saved;
  }

  /** Operator rejects an advisory suggestion. Idempotent when already REJECTED. */
  @Transactional
  public AiWorkSuggestion reject(UUID id, UUID decidedByUserId, String reason) {
    UUID tenantId = TenantContext.requireTenantId();
    AiWorkSuggestion suggestion = lockedForDecision(id, tenantId);
    if (AiWorkStatus.REJECTED.name().equals(suggestion.getStatus())) {
      return suggestion;
    }
    if (AiWorkStatus.ACCEPTED.name().equals(suggestion.getStatus())) {
      throw new IllegalArgumentException("AI work suggestion was already accepted");
    }
    suggestion.reject(decidedByUserId, reason, clock.instant());
    AiWorkSuggestion saved = repository.save(suggestion);
    auditEventService.record(
        "AI_WORK_SUGGESTION_REJECTED", "AI_WORK_SUGGESTION", saved.getId().toString(),
        decidedByUserId, auditJson(saved, reason));
    return saved;
  }

  @Transactional(readOnly = true)
  public AiWorkSuggestion getSuggestion(UUID id) {
    return repository
        .findByIdAndTenantId(id, TenantContext.requireTenantId())
        .orElseThrow(() -> new IllegalArgumentException("AI work suggestion not found"));
  }

  @Transactional(readOnly = true)
  public List<AiWorkSuggestion> listForSource(AiWorkSourceType sourceType, UUID sourceId) {
    if (sourceType == null) throw new IllegalArgumentException("source_type is required");
    if (sourceId == null) throw new IllegalArgumentException("source_id is required");
    return repository.findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(
        TenantContext.requireTenantId(), sourceType.name(), sourceId);
  }

  @Transactional(readOnly = true)
  public List<AiWorkSuggestion> listRecent(int limit) {
    int bounded = Math.min(Math.max(limit, 1), MAX_RECENT_LIMIT);
    return repository.findByTenantIdOrderByCreatedAtDesc(
        TenantContext.requireTenantId(), PageRequest.of(0, bounded));
  }

  private AiWorkSuggestion lockedForDecision(UUID id, UUID tenantId) {
    return repository
        .findWithLockByIdAndTenantId(id, tenantId)
        .orElseThrow(() -> new IllegalArgumentException("AI work suggestion not found"));
  }

  private static String normalize(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /**
   * OP-CAP-16G: cheap line count of the already-in-memory advisory context (one O(n) scan over a
   * bounded string; no parsing, no I/O). Blank context → null so the estimator falls back to 1.
   */
  private static Integer lineCountOf(String contextText) {
    if (contextText == null || contextText.isBlank()) {
      return null;
    }
    int lines = 1;
    for (int i = 0; i < contextText.length(); i++) {
      if (contextText.charAt(i) == '\n') {
        lines++;
      }
    }
    return lines;
  }

  /** Safe audit metadata — IDs, type, status, and decision reason only. No prompt/generated text. */
  private static String auditJson(AiWorkSuggestion suggestion, String reason) {
    StringBuilder sb = new StringBuilder("{")
        .append("\"suggestionId\":\"").append(suggestion.getId()).append("\",")
        .append("\"workType\":\"").append(suggestion.getWorkType()).append("\",")
        .append("\"sourceType\":\"").append(suggestion.getSourceType()).append("\",")
        .append("\"sourceId\":\"").append(suggestion.getSourceId()).append("\",")
        .append("\"status\":\"").append(suggestion.getStatus()).append("\",")
        .append("\"riskLevel\":\"").append(suggestion.getRiskLevel()).append("\"");
    if (reason != null && !reason.isBlank()) {
      sb.append(",\"decisionReason\":").append(jsonStr(reason));
    }
    return sb.append("}").toString();
  }

  private static String jsonStr(String value) {
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append("\"").toString();
  }
}
