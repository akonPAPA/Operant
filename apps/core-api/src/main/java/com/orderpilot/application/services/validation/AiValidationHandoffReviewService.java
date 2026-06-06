package com.orderpilot.application.services.validation;

import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffCorrectionRequest;
import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffDecisionRequest;
import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffReviewView;
import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffStartReviewRequest;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.validation.AiHandoffReviewDecision;
import com.orderpilot.domain.validation.AiHandoffReviewStatus;
import com.orderpilot.domain.validation.AiValidationHandoff;
import com.orderpilot.domain.validation.AiValidationHandoffRepository;
import com.orderpilot.domain.validation.AiValidationHandoffReview;
import com.orderpilot.domain.validation.AiValidationHandoffReviewRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-08B operator review workflow for AI validation handoffs.
 *
 * <p>This service records review state and bounded operator annotations only. It never creates a
 * quote/order/draft, never mutates customer/product/inventory/price state, and never triggers
 * connector/outbox/external execution.
 */
@Service
public class AiValidationHandoffReviewService {
  private static final String EXTERNAL_EXECUTION = "DISABLED";
  private static final int MAX_REASON_CODE = 80;
  private static final int MAX_NOTE = 500;
  private static final int MAX_ACTOR = 120;
  private static final int MAX_CORRECTION_SUMMARY = 500;
  private static final int MAX_INTENT = 120;
  private static final int MAX_CUSTOMER_REF = 160;

  private final AiValidationHandoffRepository handoffRepository;
  private final AiValidationHandoffReviewRepository reviewRepository;
  private final AuditEventService auditEventService;
  private final JsonSupport json;
  private final Clock clock;

  public AiValidationHandoffReviewService(
      AiValidationHandoffRepository handoffRepository,
      AiValidationHandoffReviewRepository reviewRepository,
      AuditEventService auditEventService,
      JsonSupport json,
      Clock clock) {
    this.handoffRepository = handoffRepository;
    this.reviewRepository = reviewRepository;
    this.auditEventService = auditEventService;
    this.json = json;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public AiHandoffReviewView get(UUID handoffId) {
    UUID tenantId = TenantContext.requireTenantId();
    AiValidationHandoff handoff = requireHandoff(tenantId, handoffId);
    return toView(handoff, reviewRepository.findByTenantIdAndHandoffId(tenantId, handoffId).orElse(null));
  }

  @Transactional
  public AiHandoffReviewView startReview(UUID handoffId, AiHandoffStartReviewRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    AiValidationHandoff handoff = requireHandoff(tenantId, handoffId);
    AiValidationHandoffReview review = getOrCreateReview(tenantId, handoffId);
    if (statusOf(review).isTerminal()) {
      throw new IllegalArgumentException("AI handoff review is already terminal");
    }
    review.markStatus(AiHandoffReviewStatus.IN_REVIEW, bounded(request == null ? null : request.reviewedBy(), MAX_ACTOR), clock.instant());
    review = reviewRepository.save(review);
    audit("ai_validation_handoff_review.started", handoff, review, null);
    return toView(handoff, review);
  }

  @Transactional
  public AiHandoffReviewView decide(UUID handoffId, AiHandoffDecisionRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    AiValidationHandoff handoff = requireHandoff(tenantId, handoffId);
    AiValidationHandoffReview review = getOrCreateReview(tenantId, handoffId);
    if (statusOf(review).isTerminal()) {
      throw new IllegalArgumentException("AI handoff review is already terminal");
    }
    AiHandoffReviewDecision decision = parseDecision(request == null ? null : request.decision());
    AiHandoffReviewStatus next = statusForDecision(handoff, decision);
    review.recordDecision(
        next,
        decision,
        bounded(request == null ? null : request.reasonCode(), MAX_REASON_CODE),
        bounded(request == null ? null : request.note(), MAX_NOTE),
        bounded(request == null ? null : request.reviewedBy(), MAX_ACTOR),
        clock.instant());
    review = reviewRepository.save(review);
    audit("ai_validation_handoff_review.decision_recorded", handoff, review, decision);
    return toView(handoff, review);
  }

  @Transactional
  public AiHandoffReviewView recordCorrection(UUID handoffId, AiHandoffCorrectionRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    AiValidationHandoff handoff = requireHandoff(tenantId, handoffId);
    AiValidationHandoffReview review = getOrCreateReview(tenantId, handoffId);
    if (statusOf(review).isTerminal()) {
      throw new IllegalArgumentException("AI handoff review is already terminal");
    }
    Integer correctedLineCount = request == null ? null : request.correctedLineCount();
    if (correctedLineCount != null && correctedLineCount < 0) {
      throw new IllegalArgumentException("correctedLineCount must not be negative");
    }
    review.recordCorrection(
        bounded(request == null ? null : request.correctionSummary(), MAX_CORRECTION_SUMMARY),
        bounded(request == null ? null : request.correctedIntent(), MAX_INTENT),
        bounded(request == null ? null : request.correctedCustomerRef(), MAX_CUSTOMER_REF),
        correctedLineCount,
        clock.instant());
    review.markStatus(AiHandoffReviewStatus.CORRECTION_REQUESTED, bounded(request == null ? null : request.reviewedBy(), MAX_ACTOR), clock.instant());
    review = reviewRepository.save(review);
    audit("ai_validation_handoff_review.correction_recorded", handoff, review, null);
    return toView(handoff, review);
  }

  private AiValidationHandoff requireHandoff(UUID tenantId, UUID handoffId) {
    return handoffRepository.findByIdAndTenantId(handoffId, tenantId)
        .orElseThrow(() -> new IllegalArgumentException("AI validation handoff not found for tenant"));
  }

  private AiValidationHandoffReview getOrCreateReview(UUID tenantId, UUID handoffId) {
    return reviewRepository.findLockedByTenantIdAndHandoffId(tenantId, handoffId)
        .orElseGet(() -> reviewRepository.save(new AiValidationHandoffReview(
            tenantId, handoffId, AiHandoffReviewStatus.PENDING_REVIEW, clock.instant())));
  }

  private AiHandoffReviewStatus statusOf(AiValidationHandoffReview review) {
    try {
      return AiHandoffReviewStatus.valueOf(review.getReviewStatus());
    } catch (IllegalArgumentException | NullPointerException ex) {
      return AiHandoffReviewStatus.FAILED;
    }
  }

  private AiHandoffReviewStatus statusForDecision(AiValidationHandoff handoff, AiHandoffReviewDecision decision) {
    return switch (decision) {
      case APPROVE_FOR_DRAFT_PREPARATION -> {
        if (!handoff.isDraftEligible()) {
          throw new IllegalArgumentException("AI validation handoff is not draft eligible");
        }
        yield AiHandoffReviewStatus.DRAFT_PREPARATION_READY;
      }
      case REQUEST_CORRECTION -> AiHandoffReviewStatus.CORRECTION_REQUESTED;
      case DISMISS_INVALID -> AiHandoffReviewStatus.DISMISSED;
      case BLOCK_RISK -> AiHandoffReviewStatus.BLOCKED;
      case KEEP_FOR_HUMAN_REVIEW -> AiHandoffReviewStatus.IN_REVIEW;
    };
  }

  private AiHandoffReviewDecision parseDecision(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("decision is required");
    }
    try {
      return AiHandoffReviewDecision.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unsupported AI handoff review decision");
    }
  }

  private void audit(String action, AiValidationHandoff handoff, AiValidationHandoffReview review, AiHandoffReviewDecision decision) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("handoffId", String.valueOf(handoff.getId()));
    metadata.put("reviewId", String.valueOf(review.getId()));
    metadata.put("validationId", String.valueOf(handoff.getValidationId()));
    metadata.put("routingDecision", handoff.getRoutingDecision());
    metadata.put("riskLevel", handoff.getRiskLevel());
    metadata.put("draftEligible", handoff.isDraftEligible());
    metadata.put("reviewStatus", review.getReviewStatus());
    metadata.put("decision", decision == null ? "" : decision.name());
    metadata.put("reasonCode", safe(review.getReasonCode()));
    metadata.put("reviewedByPresent", review.getReviewedBy() != null && !review.getReviewedBy().isBlank());
    metadata.put("externalExecution", EXTERNAL_EXECUTION);
    auditEventService.record(action, "ai_validation_handoff_review", review.getId().toString(), null, json.writeObject(metadata));
  }

  private AiHandoffReviewView toView(AiValidationHandoff handoff, AiValidationHandoffReview review) {
    return new AiHandoffReviewView(
        review == null ? null : review.getId(),
        handoff.getId(),
        handoff.getValidationId(),
        handoff.getStatus(),
        handoff.getRoutingDecision(),
        handoff.getRiskLevel(),
        handoff.isDraftEligible(),
        review == null ? AiHandoffReviewStatus.PENDING_REVIEW.name() : review.getReviewStatus(),
        review == null ? null : review.getDecision(),
        review == null ? null : review.getReasonCode(),
        review == null ? null : review.getNote(),
        review == null ? null : review.getCorrectionSummary(),
        review == null ? null : review.getCorrectedIntent(),
        review == null ? null : review.getCorrectedCustomerRef(),
        review == null ? null : review.getCorrectedLineCount(),
        review == null ? null : review.getReviewedBy(),
        EXTERNAL_EXECUTION,
        review == null ? null : review.getCreatedAt(),
        review == null ? null : review.getUpdatedAt());
  }

  private static String bounded(String value, int max) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}
