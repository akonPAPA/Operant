package com.orderpilot.application.services.validation;

import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffCorrectionRequest;
import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffDecisionRequest;
import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffDraftPreparationCandidate;
import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffReviewQueueItem;
import com.orderpilot.api.dto.AiValidationHandoffDtos.AiHandoffReviewView;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
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
  private static final int MAX_CORRECTION_SUMMARY = 500;
  private static final int MAX_INTENT = 120;
  private static final int MAX_CUSTOMER_REF = 160;
  private static final int DEFAULT_LIST_LIMIT = 50;
  private static final int MAX_LIST_LIMIT = 200;
  // Bounded scan window for the in-memory review-status derivation in the queue.
  private static final int QUEUE_SCAN_CAP = 200;

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

  /**
   * OP-CAP-08B operator review queue. Tenant-scoped, bounded, most-recently-updated first. Filters by
   * review status (effective: PENDING_REVIEW until a review row exists), routing decision, risk level,
   * and draft eligibility. DTO only — never raw result JSON / document text / customer message body.
   */
  @Transactional(readOnly = true)
  public List<AiHandoffReviewQueueItem> queue(
      String reviewStatus, String routingDecision, String riskLevel, Boolean draftEligible, Integer limit) {
    UUID tenantId = TenantContext.requireTenantId();
    int max = clampLimit(limit);
    List<AiValidationHandoff> handoffs =
        handoffRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId, PageRequest.of(0, QUEUE_SCAN_CAP));
    List<UUID> ids = handoffs.stream().map(AiValidationHandoff::getId).toList();
    Map<UUID, AiValidationHandoffReview> reviews = ids.isEmpty() ? Map.of()
        : reviewRepository.findByTenantIdAndHandoffIdIn(tenantId, ids).stream()
            .collect(Collectors.toMap(AiValidationHandoffReview::getHandoffId, r -> r, (a, b) -> a));

    String routingFilter = upper(routingDecision);
    String riskFilter = upper(riskLevel);
    String reviewFilter = upper(reviewStatus);
    List<AiHandoffReviewQueueItem> out = new ArrayList<>();
    for (AiValidationHandoff h : handoffs) {
      if (routingFilter != null && !routingFilter.equals(h.getRoutingDecision())) {
        continue;
      }
      if (riskFilter != null && !riskFilter.equals(h.getRiskLevel())) {
        continue;
      }
      if (draftEligible != null && draftEligible != h.isDraftEligible()) {
        continue;
      }
      String effective = effectiveReviewStatus(reviews.get(h.getId()));
      if (reviewFilter != null && !reviewFilter.equals(effective)) {
        continue;
      }
      out.add(toQueueItem(h, effective));
      if (out.size() >= max) {
        break;
      }
    }
    return out;
  }

  /**
   * OP-CAP-08B draft-preparation candidate contract. Succeeds only once the review status is
   * {@code DRAFT_PREPARATION_READY}. This is a CONTRACT/DTO only: it creates no quote/order/draft row
   * and triggers no connector/external write.
   */
  @Transactional(readOnly = true)
  public AiHandoffDraftPreparationCandidate draftPreparationCandidate(UUID handoffId) {
    UUID tenantId = TenantContext.requireTenantId();
    AiValidationHandoff handoff = requireHandoff(tenantId, handoffId);
    AiValidationHandoffReview review = reviewRepository.findByTenantIdAndHandoffId(tenantId, handoffId)
        .orElseThrow(() -> new IllegalArgumentException("AI validation handoff is not draft-preparation-ready"));
    if (!AiHandoffReviewStatus.DRAFT_PREPARATION_READY.name().equals(review.getReviewStatus())) {
      throw new IllegalArgumentException("AI validation handoff is not draft-preparation-ready");
    }
    return new AiHandoffDraftPreparationCandidate(
        handoff.getId(),
        handoff.getValidationId(),
        handoff.getExtractionResultId(),
        handoff.getProcessingJobId(),
        handoff.getIntent(),
        review.getCorrectedIntent(),
        handoff.getCustomerRef(),
        review.getCorrectedCustomerRef(),
        handoff.getLineCount(),
        review.getCorrectedLineCount(),
        handoff.getRoutingDecision(),
        handoff.getRiskLevel(),
        review.getDecision(),
        handoff.getIssueSummary(),
        review.getCorrectionSummary(),
        EXTERNAL_EXECUTION,
        true);
  }

  @Transactional
  public AiHandoffReviewView startReview(UUID handoffId, UUID actorId) {
    UUID tenantId = TenantContext.requireTenantId();
    String trustedActor = requireActor(actorId).toString();
    AiValidationHandoff handoff = requireHandoff(tenantId, handoffId);
    AiValidationHandoffReview review = getOrCreateReview(tenantId, handoffId);
    if (statusOf(review) == AiHandoffReviewStatus.IN_REVIEW) {
      // Idempotent: already in review — no state change, no duplicate audit.
      return toView(handoff, review);
    }
    if (statusOf(review).isTerminal()) {
      throw new IllegalArgumentException("AI handoff review is already terminal");
    }
    review.markStatus(AiHandoffReviewStatus.IN_REVIEW, trustedActor, clock.instant());
    review = reviewRepository.save(review);
    audit("ai_validation_handoff_review.started", handoff, review, null, actorId);
    return toView(handoff, review);
  }

  @Transactional
  public AiHandoffReviewView decide(
      UUID handoffId, AiHandoffDecisionRequest request, UUID actorId) {
    UUID tenantId = TenantContext.requireTenantId();
    String trustedActor = requireActor(actorId).toString();
    AiValidationHandoff handoff = requireHandoff(tenantId, handoffId);
    AiValidationHandoffReview review = getOrCreateReview(tenantId, handoffId);
    if (statusOf(review).isTerminal()) {
      throw new IllegalArgumentException("AI handoff review is already terminal");
    }
    AiHandoffReviewDecision decision = parseDecision(request == null ? null : request.decision());
    String reasonCode = bounded(request == null ? null : request.reasonCode(), MAX_REASON_CODE);
    String note = bounded(request == null ? null : request.note(), MAX_NOTE);
    AiHandoffReviewStatus next = statusForDecision(handoff, decision, reasonCode, note);
    review.recordDecision(
        next,
        decision,
        reasonCode,
        note,
        trustedActor,
        clock.instant());
    review = reviewRepository.save(review);
    audit("ai_validation_handoff_review.decision_recorded", handoff, review, decision, actorId);
    return toView(handoff, review);
  }

  @Transactional
  public AiHandoffReviewView recordCorrection(
      UUID handoffId, AiHandoffCorrectionRequest request, UUID actorId) {
    UUID tenantId = TenantContext.requireTenantId();
    String trustedActor = requireActor(actorId).toString();
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
    review.markStatus(AiHandoffReviewStatus.CORRECTION_REQUESTED, trustedActor, clock.instant());
    review = reviewRepository.save(review);
    audit("ai_validation_handoff_review.correction_recorded", handoff, review, null, actorId);
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

  /**
   * Decision → next review status. Approval rules (OP-CAP-08B req #7):
   * <ul>
   *   <li>{@code READY_FOR_DRAFT_REVIEW} (draft-eligible) → approvable.</li>
   *   <li>{@code NEEDS_HUMAN_REVIEW} → approvable ONLY with an explicit bounded reason/note.</li>
   *   <li>{@code BLOCKED_INVALID_EXTRACTION} / {@code FAILED_VALIDATION} → never approvable.</li>
   * </ul>
   * Approval never creates a quote/order/draft; it only marks the handoff draft-preparation-ready.
   */
  private AiHandoffReviewStatus statusForDecision(
      AiValidationHandoff handoff, AiHandoffReviewDecision decision, String reasonCode, String note) {
    return switch (decision) {
      case APPROVE_FOR_DRAFT_PREPARATION -> {
        String routing = handoff.getRoutingDecision();
        if (handoff.isDraftEligible() || "READY_FOR_DRAFT_REVIEW".equals(routing)) {
          yield AiHandoffReviewStatus.DRAFT_PREPARATION_READY;
        }
        if ("NEEDS_HUMAN_REVIEW".equals(routing)) {
          if (isBlank(reasonCode) && isBlank(note)) {
            throw new IllegalArgumentException(
                "An explicit reason is required to approve a needs-human-review handoff for draft preparation");
          }
          yield AiHandoffReviewStatus.DRAFT_PREPARATION_READY;
        }
        // BLOCKED_INVALID_EXTRACTION / FAILED_VALIDATION cannot become draft-preparation-ready.
        throw new IllegalArgumentException("AI validation handoff is not draft eligible");
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

  private void audit(
      String action,
      AiValidationHandoff handoff,
      AiValidationHandoffReview review,
      AiHandoffReviewDecision decision,
      UUID actorId) {
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
    auditEventService.record(
        action,
        "ai_validation_handoff_review",
        review.getId().toString(),
        actorId,
        json.writeObject(metadata));
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
        EXTERNAL_EXECUTION,
        review == null ? null : review.getCreatedAt(),
        review == null ? null : review.getUpdatedAt());
  }

  private UUID requireActor(UUID actorId) {
    if (actorId == null) {
      throw new IllegalArgumentException("Trusted actor is required");
    }
    return actorId;
  }

  private String effectiveReviewStatus(AiValidationHandoffReview review) {
    return review == null ? AiHandoffReviewStatus.PENDING_REVIEW.name() : review.getReviewStatus();
  }

  private AiHandoffReviewQueueItem toQueueItem(AiValidationHandoff h, String reviewStatus) {
    return new AiHandoffReviewQueueItem(
        h.getId(), h.getValidationId(), h.getExtractionResultId(), h.getProcessingJobId(),
        h.getRoutingDecision(), h.getRiskLevel(), h.getStatus(), reviewStatus, h.isDraftEligible(),
        h.getIntent(), h.getCustomerRef(), h.getLineCount(), h.getIssueCount(), h.getHighestSeverity(),
        h.getUpdatedAt());
  }

  private int clampLimit(Integer limit) {
    if (limit == null || limit <= 0) {
      return DEFAULT_LIST_LIMIT;
    }
    return Math.min(limit, MAX_LIST_LIMIT);
  }

  private static String upper(String value) {
    return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
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
