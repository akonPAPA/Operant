package com.orderpilot.application.services.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.ChannelRfqHandoffDtos.ChannelRfqHandoffResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.channel.ChannelRfqHandoff;
import com.orderpilot.domain.channel.ChannelRfqHandoffRepository;
import com.orderpilot.domain.channel.ChannelRfqHandoffStatus;
import com.orderpilot.domain.channel.InboundChannelEvent;
import com.orderpilot.domain.channel.InboundChannelEventRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-06B controlled Bot Runtime RFQ Handoff command/query service.
 *
 * <p>Creates and reads reviewable internal RFQ handoff records produced from verified channel/bot
 * events. Normal handoff commands go through this service. The trusted draft-quote bridge is the
 * narrow internal exception: it uses the repository directly for a tenant-scoped locked lookup
 * before idempotent conversion.
 *
 * <p>Safety invariants:
 * <ul>
 *   <li>Tenant is resolved from {@link TenantContext}; the owning inbound channel event must belong
 *       to that tenant, so a caller can never create or read across tenants.</li>
 *   <li>Idempotent on {@code (tenant, inboundChannelEventId)}: a re-delivered source event returns
 *       the existing handoff instead of inserting a duplicate.</li>
 *   <li>Create and dedup both emit an audit event with safe metadata only (no raw payload/secrets).</li>
 *   <li>This path only ever produces a {@code PENDING_REVIEW} draft request — it never approves a
 *       quote/order or mutates inventory/price/customer master data, and triggers no external write.</li>
 * </ul>
 */
@Service
public class ChannelRfqHandoffService {
  private static final String ENTITY_TYPE = "CHANNEL_RFQ_HANDOFF";
  static final int DEFAULT_PAGE_SIZE = 50;
  static final int MAX_PAGE_SIZE = 100;

  private final ChannelRfqHandoffRepository handoffRepository;
  private final InboundChannelEventRepository eventRepository;
  private final AuditEventService auditEventService;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public ChannelRfqHandoffService(
      ChannelRfqHandoffRepository handoffRepository,
      InboundChannelEventRepository eventRepository,
      AuditEventService auditEventService,
      ObjectMapper objectMapper,
      Clock clock) {
    this.handoffRepository = handoffRepository;
    this.eventRepository = eventRepository;
    this.auditEventService = auditEventService;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /**
   * Create (or return the existing) reviewable RFQ handoff for a verified channel/bot event.
   * Idempotent and tenant-scoped.
   */
  @Transactional
  public ChannelRfqHandoffResponse createFromChannelEvent(CreateChannelRfqHandoffCommand command) {
    UUID tenantId = TenantContext.requireTenantId();
    if (command == null || command.inboundChannelEventId() == null) {
      throw new IllegalArgumentException("Source channel event reference is required for RFQ handoff");
    }

    // Enforce tenant ownership in the database query, before the source event can be accessed.
    InboundChannelEvent event = eventRepository.findByIdAndTenantId(command.inboundChannelEventId(), tenantId)
        .orElseThrow(() -> new NotFoundException("Source channel event not found for tenant"));

    // Idempotency: a re-delivered source event must not create a duplicate handoff.
    var existing = handoffRepository.findFirstByTenantIdAndInboundChannelEventId(tenantId, event.getId());
    if (existing.isPresent()) {
      ChannelRfqHandoff handoff = existing.get();
      audit("CHANNEL_RFQ_HANDOFF_DEDUPLICATED", handoff);
      return toResponse(handoff);
    }

    ChannelRfqHandoff handoff = new ChannelRfqHandoff(
        tenantId,
        event.getId(),
        command.channelConnectionId(),
        safe(command.sourceChannel()),
        command.sourceExternalEventId(),
        command.sourceActorExternalId(),
        command.customerAccountId(),
        command.customerContactId(),
        command.requestText(),
        command.detectedIntent(),
        clock.instant());
    handoff = handoffRepository.save(handoff);
    audit("CHANNEL_RFQ_HANDOFF_CREATED", handoff);
    return toResponse(handoff);
  }

  @Transactional(readOnly = true)
  public List<ChannelRfqHandoffResponse> list(ChannelRfqHandoffStatus status) {
    return list(status, null, null);
  }

  @Transactional(readOnly = true)
  public List<ChannelRfqHandoffResponse> list(
      ChannelRfqHandoffStatus status, Integer page, Integer size) {
    UUID tenantId = TenantContext.requireTenantId();
    Pageable pageable = boundedPage(page, size);
    List<ChannelRfqHandoff> handoffs = status == null
        ? handoffRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable)
        : handoffRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(
            tenantId, status, pageable);
    return handoffs.stream().map(ChannelRfqHandoffService::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public ChannelRfqHandoffResponse get(UUID id) {
    UUID tenantId = TenantContext.requireTenantId();
    ChannelRfqHandoff handoff = handoffRepository.findByIdAndTenantId(id, tenantId)
        .orElseThrow(() -> new NotFoundException("RFQ handoff not found"));
    return toResponse(handoff);
  }

  /**
   * OP-CAP-06C operator command: take a handoff into review (PENDING_REVIEW -&gt; IN_REVIEW).
   * Tenant-scoped, strict transition, audited. Never creates a quote/order or external write.
   */
  @Transactional
  public ChannelRfqHandoffResponse startReview(UUID id, UUID reviewerUserId) {
    ChannelRfqHandoff handoff = getForMutation(id);
    requireTransitionAllowed(handoff, ChannelRfqHandoffStatus.IN_REVIEW, ChannelRfqHandoffStatus.PENDING_REVIEW);
    String previousStatus = handoff.getStatus().name();
    handoff.startReview(reviewerUserId, clock.instant());
    handoff = handoffRepository.save(handoff);
    auditTransition("CHANNEL_RFQ_HANDOFF_REVIEW_STARTED", handoff, previousStatus, null, reviewerUserId);
    return toResponse(handoff);
  }

  /**
   * OP-CAP-06C operator command: dismiss a handoff as invalid/irrelevant
   * (PENDING_REVIEW|IN_REVIEW -&gt; DISMISSED). Requires a non-blank reason. Terminal, audited.
   */
  @Transactional
  public ChannelRfqHandoffResponse dismiss(UUID id, String reason, UUID actorUserId) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("A non-blank dismiss reason is required");
    }
    ChannelRfqHandoff handoff = getForMutation(id);
    requireTransitionAllowed(handoff, ChannelRfqHandoffStatus.DISMISSED,
        ChannelRfqHandoffStatus.PENDING_REVIEW, ChannelRfqHandoffStatus.IN_REVIEW);
    String previousStatus = handoff.getStatus().name();
    String trimmedReason = reason.trim();
    handoff.dismiss(trimmedReason, actorUserId, clock.instant());
    handoff = handoffRepository.save(handoff);
    auditTransition("CHANNEL_RFQ_HANDOFF_DISMISSED", handoff, previousStatus, trimmedReason, actorUserId);
    return toResponse(handoff);
  }

  /**
   * OP-CAP-06C operator command: mark a handoff converted — the operator-review workflow is complete
   * and it is ready for a later, separately-gated quote/order workflow
   * (PENDING_REVIEW|IN_REVIEW -&gt; CONVERTED). This is a safe internal placeholder state only: it does
   * NOT create any quote/order, approve anything, mutate business data, or trigger an external write.
  */
  @Transactional
  public ChannelRfqHandoffResponse markConverted(UUID id, String conversionNote, UUID actorUserId) {
    if (conversionNote == null || conversionNote.isBlank()) {
      throw new IllegalArgumentException("A non-blank conversion note is required");
    }
    ChannelRfqHandoff handoff = getForMutation(id);
    requireTransitionAllowed(handoff, ChannelRfqHandoffStatus.CONVERTED,
        ChannelRfqHandoffStatus.PENDING_REVIEW, ChannelRfqHandoffStatus.IN_REVIEW);
    String previousStatus = handoff.getStatus().name();
    String trimmedNote = conversionNote.trim();
    handoff.markConverted(trimmedNote, actorUserId, clock.instant());
    handoff = handoffRepository.save(handoff);
    auditTransition("CHANNEL_RFQ_HANDOFF_CONVERTED", handoff, previousStatus, trimmedNote, actorUserId);
    return toResponse(handoff);
  }

  /** Pessimistic-write locked fetch for operator transition commands; tenant-scoped not-found. */
  private ChannelRfqHandoff getForMutation(UUID id) {
    UUID tenantId = TenantContext.requireTenantId();
    return handoffRepository.findWithLockByIdAndTenantId(id, tenantId)
        .orElseThrow(() -> new NotFoundException("RFQ handoff not found"));
  }

  /**
   * Strict state-machine guard. Rejects any transition whose current status is not explicitly
   * allowed — including repeated transitions on an already-terminal state — with a structured 400.
   */
  private static void requireTransitionAllowed(
      ChannelRfqHandoff handoff, ChannelRfqHandoffStatus target, ChannelRfqHandoffStatus... allowedFrom) {
    for (ChannelRfqHandoffStatus from : allowedFrom) {
      if (handoff.getStatus() == from) {
        return;
      }
    }
    throw new IllegalArgumentException(
        "Invalid RFQ handoff transition " + handoff.getStatus() + " -> " + target);
  }

  private void audit(String action, ChannelRfqHandoff handoff) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("channelRfqHandoffId", str(handoff.getId()));
    metadata.put("inboundChannelEventId", str(handoff.getInboundChannelEventId()));
    metadata.put("channelConnectionId", str(handoff.getChannelConnectionId()));
    metadata.put("sourceChannel", safe(handoff.getSourceChannel()));
    metadata.put("sourceExternalEventId", safe(handoff.getSourceExternalEventId()));
    metadata.put("detectedIntent", safe(handoff.getDetectedIntent()));
    metadata.put("status", handoff.getStatus().name());
    metadata.put("externalExecution", "DISABLED");
    auditEventService.record(action, ENTITY_TYPE, handoff.getId().toString(), null, writeJson(metadata));
  }

  /**
   * Audit an operator workflow transition with safe metadata only: ids, previous/new status, source
   * references, the bounded reason/note, and the actor id. Never logs raw payloads or secrets.
   */
  private void auditTransition(
      String action, ChannelRfqHandoff handoff, String previousStatus, String reasonOrNote, UUID actorUserId) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("channelRfqHandoffId", str(handoff.getId()));
    metadata.put("previousStatus", safe(previousStatus));
    metadata.put("newStatus", handoff.getStatus().name());
    metadata.put("sourceChannel", safe(handoff.getSourceChannel()));
    metadata.put("sourceExternalEventId", safe(handoff.getSourceExternalEventId()));
    metadata.put("reason", truncate(reasonOrNote, 500));
    metadata.put("actorUserId", str(actorUserId));
    metadata.put("externalExecution", "DISABLED");
    auditEventService.record(action, ENTITY_TYPE, handoff.getId().toString(), actorUserId, writeJson(metadata));
  }

  private static ChannelRfqHandoffResponse toResponse(ChannelRfqHandoff handoff) {
    return new ChannelRfqHandoffResponse(
        handoff.getId(),
        handoff.getSourceChannel(),
        handoff.getSourceActorExternalId(),
        handoff.getCustomerAccountId(),
        handoff.getCustomerContactId(),
        handoff.getRequestText(),
        preview(handoff.getRequestText()),
        handoff.getDetectedIntent(),
        handoff.getStatus().name(),
        handoff.getReviewStartedAt(),
        handoff.getDismissedAt(),
        handoff.getDismissReason(),
        handoff.getConvertedAt(),
        handoff.getConversionNote(),
        handoff.getCreatedAt(),
        handoff.getUpdatedAt());
  }

  private static Pageable boundedPage(Integer requestedPage, Integer requestedSize) {
    int page = requestedPage == null ? 0 : requestedPage;
    int size = requestedSize == null ? DEFAULT_PAGE_SIZE : requestedSize;
    if (page < 0) {
      throw new IllegalArgumentException("page must be greater than or equal to zero");
    }
    if (size <= 0) {
      throw new IllegalArgumentException("size must be greater than zero");
    }
    return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
  }

  /** Short, single-line request preview for list views. */
  private static String preview(String requestText) {
    if (requestText == null) {
      return "";
    }
    String collapsed = requestText.strip().replaceAll("\\s+", " ");
    return collapsed.length() <= 160 ? collapsed : collapsed.substring(0, 160) + "…";
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(0, max);
  }

  private String writeJson(Map<String, Object> metadata) {
    try {
      return objectMapper.writeValueAsString(metadata);
    } catch (Exception ex) {
      return "{\"externalExecution\":\"DISABLED\"}";
    }
  }

  private static String str(UUID value) {
    return value == null ? "" : value.toString();
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }
}
