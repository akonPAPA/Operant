package com.orderpilot.application.services.validation;

import com.orderpilot.api.dto.AiValidationHandoffDtos.AiValidationHandoffView;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractionResult;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.validation.AiExtractionValidation;
import com.orderpilot.domain.validation.AiExtractionValidationIssue;
import com.orderpilot.domain.validation.AiExtractionValidationIssueRepository;
import com.orderpilot.domain.validation.AiExtractionValidationRepository;
import com.orderpilot.domain.validation.AiValidationHandoff;
import com.orderpilot.domain.validation.AiValidationHandoffRepository;
import com.orderpilot.domain.validation.AiValidationHandoffStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-07F — safe handoff foundation: turns a deterministic AI validation routing decision
 * (OP-CAP-07E) into one tenant-scoped, operator-reviewable handoff work item.
 *
 * <p>Hard boundaries: tenant authority comes from {@code TenantContext} only; the validation and
 * extraction result are fetched tenant-scoped and a mismatch fails closed. This service only ever
 * reads the advisory validation/extraction state and writes its own {@code ai_validation_handoff}
 * row. It NEVER creates a quote/order/draft, never mutates product/customer/inventory/price/discount/
 * margin master data, and never triggers a connector/outbox/ERP/1C write. The handoff is advisory
 * routing metadata only; {@code READY_FOR_DRAFT_REVIEW} merely flags a future draft candidate.
 */
@Service
public class AiValidationHandoffService {
  private static final int MAX_INTENT = 120;
  private static final int MAX_CUSTOMER_REF = 160;
  private static final int MAX_SUMMARY = 500;
  private static final int MAX_LIST_LIMIT = 200;
  private static final int DEFAULT_LIST_LIMIT = 50;

  private final AiExtractionValidationRepository validationRepository;
  private final AiExtractionValidationIssueRepository issueRepository;
  private final ExtractionResultRepository extractionResultRepository;
  private final AiValidationHandoffRepository handoffRepository;
  private final AuditEventService auditEventService;
  private final JsonSupport json;
  private final Clock clock;

  public AiValidationHandoffService(
      AiExtractionValidationRepository validationRepository,
      AiExtractionValidationIssueRepository issueRepository,
      ExtractionResultRepository extractionResultRepository,
      AiValidationHandoffRepository handoffRepository,
      AuditEventService auditEventService,
      JsonSupport json,
      Clock clock) {
    this.validationRepository = validationRepository;
    this.issueRepository = issueRepository;
    this.extractionResultRepository = extractionResultRepository;
    this.handoffRepository = handoffRepository;
    this.auditEventService = auditEventService;
    this.json = json;
    this.clock = clock;
  }

  @Transactional
  public AiValidationHandoffView generate(UUID validationId) {
    UUID tenantId = TenantContext.requireTenantId();
    // Fail closed: the validation must exist for THIS tenant. AI payload is never the authority.
    AiExtractionValidation validation = validationRepository.findByIdAndTenantId(validationId, tenantId)
        .orElseThrow(() -> new IllegalArgumentException("AI validation not found for tenant"));

    ExtractionResult extraction = extractionResultRepository
        .findByIdAndTenantId(validation.getExtractionResultId(), tenantId).orElse(null);
    List<AiExtractionValidationIssue> issues =
        issueRepository.findByTenantIdAndAiExtractionValidationIdOrderByCreatedAtAsc(tenantId, validation.getId());

    AiValidationHandoffStatus status = statusFor(validation.getRoutingDecision());
    String intent = bounded(extraction == null ? null : extraction.getDetectedIntent(), MAX_INTENT);
    String customerRef = bounded(customerRefFrom(extraction), MAX_CUSTOMER_REF);
    int lineCount = lineCountFrom(extraction);
    String issueSummary = bounded(summarize(issues), MAX_SUMMARY);

    Instant now = clock.instant();
    boolean created = false;
    AiValidationHandoff handoff = handoffRepository
        .findByTenantIdAndValidationId(tenantId, validation.getId()).orElse(null);
    if (handoff == null) {
      handoff = new AiValidationHandoff(tenantId, validation.getId(), validation.getExtractionResultId(),
          validation.getExtractionRunId(), validation.getProcessingJobId(),
          validation.getSourceType(), validation.getSourceId(), now);
      created = true;
    }
    handoff.apply(status, validation.getRoutingDecision(), validation.getRiskLevel(), intent, customerRef,
        lineCount, validation.getIssueCount(), validation.getHighestSeverity(),
        validation.getPromptInjectionSignalCount(), validation.isUnknownCustomer(), issueSummary, now);
    handoff = handoffRepository.save(handoff);

    audit(handoff, created);
    return toView(handoff);
  }

  @Transactional(readOnly = true)
  public AiValidationHandoffView get(UUID handoffId) {
    UUID tenantId = TenantContext.requireTenantId();
    AiValidationHandoff handoff = handoffRepository.findByIdAndTenantId(handoffId, tenantId)
        .orElseThrow(() -> new IllegalArgumentException("AI validation handoff not found for tenant"));
    return toView(handoff);
  }

  @Transactional(readOnly = true)
  public List<AiValidationHandoffView> list(String status, String routingDecision, Integer limit) {
    UUID tenantId = TenantContext.requireTenantId();
    Pageable page = PageRequest.of(0, clampLimit(limit));
    // routingDecision mirrors status 1:1; a status filter takes precedence, else routingDecision.
    String filter = status != null && !status.isBlank() ? status
        : (routingDecision != null && !routingDecision.isBlank() ? routingDecision : null);
    List<AiValidationHandoff> rows = filter == null
        ? handoffRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId, page)
        : handoffRepository.findByTenantIdAndStatusOrderByUpdatedAtDesc(
            tenantId, filter.trim().toUpperCase(Locale.ROOT), page);
    return rows.stream().map(this::toView).toList();
  }

  private AiValidationHandoffStatus statusFor(String routingDecision) {
    try {
      return AiValidationHandoffStatus.valueOf(routingDecision);
    } catch (IllegalArgumentException | NullPointerException ex) {
      // Fail safe: an unrecognized routing decision is never draft-eligible.
      return AiValidationHandoffStatus.FAILED_VALIDATION;
    }
  }

  private String summarize(List<AiExtractionValidationIssue> issues) {
    if (issues.isEmpty()) {
      return "";
    }
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (AiExtractionValidationIssue issue : issues) {
      counts.merge(issue.getIssueCode(), 1, Integer::sum);
    }
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, Integer> e : counts.entrySet()) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(e.getValue() > 1 ? e.getKey() + "(x" + e.getValue() + ")" : e.getKey());
    }
    return sb.toString();
  }

  private String customerRefFrom(ExtractionResult extraction) {
    if (extraction == null) {
      return null;
    }
    Map<String, Object> payload = asMap(json.parseObject(extraction.getResultJson()).get("extraction"));
    Object hints = payload.get("customer_hints");
    if (hints instanceof List<?> list && !list.isEmpty() && list.get(0) != null) {
      String hint = list.get(0).toString().trim();
      return hint.isBlank() ? null : hint;
    }
    return null;
  }

  private int lineCountFrom(ExtractionResult extraction) {
    if (extraction == null) {
      return 0;
    }
    Map<String, Object> payload = asMap(json.parseObject(extraction.getResultJson()).get("extraction"));
    return payload.get("line_items") instanceof List<?> list ? list.size() : 0;
  }

  private void audit(AiValidationHandoff handoff, boolean created) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("handoffId", String.valueOf(handoff.getId()));
    metadata.put("validationId", String.valueOf(handoff.getValidationId()));
    metadata.put("extractionResultId", String.valueOf(handoff.getExtractionResultId()));
    metadata.put("routingDecision", handoff.getRoutingDecision());
    metadata.put("riskLevel", handoff.getRiskLevel());
    metadata.put("status", handoff.getStatus());
    metadata.put("draftEligible", handoff.isDraftEligible());
    metadata.put("issueCount", handoff.getIssueCount());
    metadata.put("lineCount", handoff.getLineCount());
    metadata.put("advisoryOnly", true);
    auditEventService.record(created ? "ai_validation_handoff.created" : "ai_validation_handoff.updated",
        "ai_validation_handoff", handoff.getId().toString(), null, json.writeObject(metadata));
  }

  private AiValidationHandoffView toView(AiValidationHandoff h) {
    return new AiValidationHandoffView(
        h.getId(), h.getValidationId(), h.getExtractionResultId(), h.getExtractionRunId(),
        h.getProcessingJobId(), h.getSourceType(), h.getSourceId(), h.getStatus(), h.getRoutingDecision(),
        h.getRiskLevel(), h.getIntent(), h.getCustomerRef(), h.getLineCount(), h.getIssueCount(),
        h.getHighestSeverity(), h.getPromptInjectionSignalCount(), h.isUnknownCustomer(),
        h.isDraftEligible(), h.getIssueSummary(), h.getCreatedAt(), h.getUpdatedAt());
  }

  private int clampLimit(Integer limit) {
    if (limit == null || limit <= 0) {
      return DEFAULT_LIST_LIMIT;
    }
    return Math.min(limit, MAX_LIST_LIMIT);
  }

  private static String bounded(String value, int max) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }
}
