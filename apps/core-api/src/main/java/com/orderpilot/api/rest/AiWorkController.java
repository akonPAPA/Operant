package com.orderpilot.api.rest;

import com.orderpilot.api.dto.AiWorkDtos.AiWorkDecisionRequest;
import com.orderpilot.api.dto.AiWorkDtos.AiWorkSuggestionResponse;
import com.orderpilot.api.dto.AiWorkDtos.CreateContextualAiWorkSuggestionRequest;
import com.orderpilot.api.dto.AiWorkDtos.CreateAiWorkSuggestionRequest;
import com.orderpilot.application.services.aiwork.AiWorkService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.channel.ChannelRfqHandoff;
import com.orderpilot.domain.channel.ChannelRfqHandoffRepository;
import com.orderpilot.domain.channel.ChannelRfqHandoffStatus;
import com.orderpilot.domain.aiwork.AiWorkSourceType;
import com.orderpilot.domain.aiwork.AiWorkSuggestion;
import com.orderpilot.domain.aiwork.AiWorkType;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-07A AI Agent Work Layer read/command contract (AI Work Assistant).
 *
 * <p>All operations are tenant-scoped (tenant resolved server-side from {@code TenantContext}).
 * Permission guard (enforced by {@code ApiPermissionInterceptor}): GET requires {@code REVIEW_READ}
 * (the AI Work Assistant is an operator review surface); mutations require {@code AI_WORK_ACTION}.
 *
 * <p>Suggestions are advisory only. Accept/reject record operator intent and emit audit events; they
 * never approve a quote/order, approve a discount/substitute, or perform any external/ERP write.
 */
@RestController
@RequestMapping("/api/v1/ai-work")
public class AiWorkController {
  private final AiWorkService service;
  private final RequestActorResolver actorResolver;
  private final ChannelRfqHandoffRepository handoffRepository;

  public AiWorkController(
      AiWorkService service,
      RequestActorResolver actorResolver,
      ChannelRfqHandoffRepository handoffRepository) {
    this.service = service;
    this.actorResolver = actorResolver;
    this.handoffRepository = handoffRepository;
  }

  @PostMapping("/suggestions")
  public AiWorkSuggestionResponse create(@RequestBody CreateAiWorkSuggestionRequest request, HttpServletRequest http) {
    AiWorkSuggestion saved = service.createSuggestion(
        parseWorkType(request.workType()),
        parseSourceType(request.sourceType()),
        request.sourceId(),
        request.contextText(),
        request.idempotencyKey(),
        trustedActor(http));
    return toResponse(saved);
  }

  @PostMapping("/rfq-handoffs/{handoffId}/suggestions")
  public AiWorkSuggestionResponse createForRfqHandoff(
      @PathVariable UUID handoffId,
      @RequestBody(required = false) CreateContextualAiWorkSuggestionRequest request,
      HttpServletRequest http) {
    UUID tenantId = TenantContext.requireTenantId();
    ChannelRfqHandoff handoff = handoffRepository.findByIdAndTenantId(handoffId, tenantId)
        .orElseThrow(() -> new NotFoundException("RFQ handoff not found"));
    if (handoff.getStatus() == ChannelRfqHandoffStatus.DISMISSED
        || handoff.getStatus() == ChannelRfqHandoffStatus.CONVERTED) {
      throw new IllegalArgumentException("RFQ handoff is terminal and cannot generate AI work");
    }
    AiWorkType workType = request == null || request.workType() == null || request.workType().isBlank()
        ? AiWorkType.NEXT_ACTION_SUGGESTION
        : parseWorkType(request.workType());
    AiWorkSuggestion saved = service.createSuggestion(
        workType,
        AiWorkSourceType.RFQ_HANDOFF,
        handoffId,
        rfqHandoffContext(handoff),
        request == null ? null : request.idempotencyKey(),
        trustedActor(http));
    return toResponse(saved);
  }

  @GetMapping("/suggestions/{id}")
  public AiWorkSuggestionResponse get(@PathVariable UUID id) {
    return toResponse(service.getSuggestion(id));
  }

  @GetMapping("/suggestions")
  public List<AiWorkSuggestionResponse> list(
      @RequestParam(name = "sourceType", required = false) String sourceType,
      @RequestParam(name = "sourceId", required = false) UUID sourceId,
      @RequestParam(name = "limit", required = false, defaultValue = "50") int limit) {
    List<AiWorkSuggestion> suggestions = (sourceType != null && sourceId != null)
        ? service.listForSource(parseSourceType(sourceType), sourceId)
        : service.listRecent(limit);
    return suggestions.stream().map(this::toResponse).toList();
  }

  @PostMapping("/suggestions/{id}/accept")
  public AiWorkSuggestionResponse accept(
      @PathVariable UUID id, @RequestBody(required = false) AiWorkDecisionRequest request, HttpServletRequest http) {
    return toResponse(service.accept(
        id, trustedActor(http), request == null ? null : request.reason()));
  }

  @PostMapping("/suggestions/{id}/reject")
  public AiWorkSuggestionResponse reject(
      @PathVariable UUID id, @RequestBody(required = false) AiWorkDecisionRequest request, HttpServletRequest http) {
    return toResponse(service.reject(
        id, trustedActor(http), request == null ? null : request.reason()));
  }

  private UUID trustedActor(HttpServletRequest http) {
    return actorResolver.resolveVerifiedActor(http, TenantContext.requireTenantId());
  }

  private static String rfqHandoffContext(ChannelRfqHandoff handoff) {
    StringBuilder context = new StringBuilder();
    append(context, "source", "RFQ_HANDOFF");
    append(context, "status", handoff.getStatus().name());
    append(context, "channel", handoff.getSourceChannel());
    append(context, "intent", handoff.getDetectedIntent());
    append(context, "customerAccountPresent", handoff.getCustomerAccountId() == null ? "false" : "true");
    append(context, "request", handoff.getRequestText());
    return context.toString();
  }

  private static void append(StringBuilder context, String key, String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    if (context.length() > 0) {
      context.append('\n');
    }
    context.append(key).append(": ").append(value.strip());
  }

  private static AiWorkType parseWorkType(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("work_type is required");
    try {
      return AiWorkType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unsupported work_type: " + value);
    }
  }

  private static AiWorkSourceType parseSourceType(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("source_type is required");
    try {
      return AiWorkSourceType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unsupported source_type: " + value);
    }
  }

  private AiWorkSuggestionResponse toResponse(AiWorkSuggestion s) {
    return new AiWorkSuggestionResponse(
        s.getId(),
        s.getWorkType(),
        s.getSourceType(),
        s.getStatus(),
        s.getStrategyVersion(),
        s.getRiskLevel(),
        s.getConfidence(),
        safeDisplayText(s.getGeneratedText()),
        safeJsonField(s.getStructuredPayloadJson(), "{}"),
        safeJsonField(s.getEvidenceRefsJson(), "[]"),
        true,
        s.getCreatedAt(),
        s.getUpdatedAt(),
        s.getDecidedAt(),
        s.getDecisionReason());
  }

  private static String safeDisplayText(String value) {
    return containsLeakMarker(value) ? "Advisory output withheld by safety filter." : value;
  }

  private static String safeJsonField(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return containsLeakMarker(value) ? fallback : value;
  }

  private static boolean containsLeakMarker(String value) {
    if (value == null) {
      return false;
    }
    String lower = value.toLowerCase(Locale.ROOT);
    return lower.contains("objectstoragekey")
        || lower.contains("storagekey")
        || lower.contains("rawpayload")
        || lower.contains("rawdocument")
        || lower.contains("prompttext")
        || lower.contains("stacktrace")
        || lower.contains("connectorcredential")
        || lower.contains("secret")
        || lower.contains("token");
  }
}
