package com.orderpilot.application.services.commerce;

import com.orderpilot.api.dto.CommerceIntelligenceDtos.Bottleneck;
import com.orderpilot.api.dto.CommerceIntelligenceDtos.CommerceIntelligenceDemoFlowResponse;
import com.orderpilot.api.dto.CommerceIntelligenceDtos.NotProven;
import com.orderpilot.api.dto.CommerceIntelligenceDtos.RecentFlow;
import com.orderpilot.api.dto.CommerceIntelligenceDtos.RuntimeControl;
import com.orderpilot.api.dto.CommerceIntelligenceDtos.Safety;
import com.orderpilot.api.dto.CommerceIntelligenceDtos.Summary;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.aiwork.AiWorkSchemaVersion;
import com.orderpilot.domain.aiwork.AiWorkSuggestion;
import com.orderpilot.domain.aiwork.AiWorkSuggestionRepository;
import com.orderpilot.domain.channel.ChannelRfqHandoff;
import com.orderpilot.domain.channel.ChannelRfqHandoffRepository;
import com.orderpilot.domain.channel.ChannelRfqHandoffStatus;
import com.orderpilot.domain.workspace.DraftQuote;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.domain.workspace.QuoteValidationIssue;
import com.orderpilot.domain.workspace.QuoteValidationIssueRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped, side-effect-free read model for the visible RFQ/AI/draft/safe-terminal demo flow.
 */
@Service
public class CommerceIntelligenceDemoFlowService {
  static final int RECENT_FLOW_LIMIT = 10;
  static final int RECENT_AI_LIMIT = 50;
  static final int RECENT_ISSUE_LIMIT = 200;
  static final int BOTTLENECK_LIMIT = 20;
  private static final int REQUEST_PREVIEW_LIMIT = 180;
  private static final String RFQ_HANDOFF = "RFQ_HANDOFF";
  private static final String DRAFT_IDEMPOTENCY_PREFIX = "rfq-handoff-draft-quote:";
  private static final Set<String> SAFE_TERMINAL_STATES =
      Set.of("DEMO_COMPLETED", "DEMO_DECLINED");

  private final ChannelRfqHandoffRepository handoffRepository;
  private final AiWorkSuggestionRepository aiSuggestionRepository;
  private final DraftQuoteRepository draftQuoteRepository;
  private final QuoteValidationIssueRepository issueRepository;
  private final Clock clock;

  public CommerceIntelligenceDemoFlowService(
      ChannelRfqHandoffRepository handoffRepository,
      AiWorkSuggestionRepository aiSuggestionRepository,
      DraftQuoteRepository draftQuoteRepository,
      QuoteValidationIssueRepository issueRepository,
      Clock clock) {
    this.handoffRepository = handoffRepository;
    this.aiSuggestionRepository = aiSuggestionRepository;
    this.draftQuoteRepository = draftQuoteRepository;
    this.issueRepository = issueRepository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public CommerceIntelligenceDemoFlowResponse readDemoFlow() {
    UUID tenantId = TenantContext.requireTenantId();
    long completed =
        draftQuoteRepository.countByTenantIdAndSourceTypeAndStatus(
            tenantId, RFQ_HANDOFF, "DEMO_COMPLETED");
    long declined =
        draftQuoteRepository.countByTenantIdAndSourceTypeAndStatus(
            tenantId, RFQ_HANDOFF, "DEMO_DECLINED");

    Summary summary =
        new Summary(
            handoffRepository.countByTenantId(tenantId),
            handoffRepository.countByTenantIdAndStatus(
                tenantId, ChannelRfqHandoffStatus.PENDING_REVIEW),
            handoffRepository.countByTenantIdAndStatus(
                tenantId, ChannelRfqHandoffStatus.IN_REVIEW),
            handoffRepository.countByTenantIdAndStatus(
                tenantId, ChannelRfqHandoffStatus.CONVERTED),
            handoffRepository.countByTenantIdAndStatus(
                tenantId, ChannelRfqHandoffStatus.DISMISSED),
            aiSuggestionRepository.countByTenantIdAndSourceType(tenantId, RFQ_HANDOFF),
            draftQuoteRepository.countByTenantIdAndSourceTypeAndRequiresHumanReviewTrue(
                tenantId, RFQ_HANDOFF),
            draftQuoteRepository.countByTenantIdAndSourceTypeAndStatusIn(
                tenantId, RFQ_HANDOFF, SAFE_TERMINAL_STATES),
            completed,
            declined);

    List<NotProven> notProven = notProven();
    return new CommerceIntelligenceDemoFlowResponse(
        clock.instant(),
        "Tenant-observed demo flow (all retained records)",
        summary,
        safety(),
        runtimeControl(),
        bottlenecks(tenantId),
        recentFlows(tenantId),
        notProven);
  }

  private Safety safety() {
    return new Safety(
        "DISABLED",
        "NOT_INVOKED",
        "NOT_REQUESTED",
        null,
        null,
        null,
        "NOT_MEASURED",
        "The demo workflow contract disables external writes. External-row counts are not measured by this read model.",
        List.of(
            "Connector command rows are not measured.",
            "Change request rows are not measured.",
            "Outbox external-execution rows are not measured."));
  }

  private RuntimeControl runtimeControl() {
    return new RuntimeControl(
        true,
        "RATE_BACKPRESSURE_GATED",
        "AI_VALIDATION_EXPLANATION_GUARDED",
        "RATE_BACKPRESSURE_GATED",
        "RATE_BACKPRESSURE_GATED",
        "NOT_APPLICABLE_FOR_DEMO_OPS",
        "NOT_MEASURED",
        "PR #244 guards the write and advisory boundaries. This read model does not invoke the guard or expose guard internals.");
  }

  private List<Bottleneck> bottlenecks(UUID tenantId) {
    return issueRepository
        .summarizeOpenBlockingIssuesForSourceType(
            tenantId, RFQ_HANDOFF, PageRequest.of(0, BOTTLENECK_LIMIT))
        .stream()
        .map(row -> bottleneck(row.getCode(), row.getTotal()))
        .toList();
  }

  private List<RecentFlow> recentFlows(UUID tenantId) {
    List<ChannelRfqHandoff> handoffs =
        handoffRepository.findByTenantIdOrderByCreatedAtDescIdDesc(
            tenantId, PageRequest.of(0, RECENT_FLOW_LIMIT));
    if (handoffs.isEmpty()) {
      return List.of();
    }

    List<UUID> handoffIds = handoffs.stream().map(ChannelRfqHandoff::getId).toList();
    Map<UUID, AiWorkSuggestion> latestAiByHandoff =
        aiSuggestionRepository
            .findByTenantIdAndSourceTypeAndSourceIdInOrderByCreatedAtDesc(
                tenantId, RFQ_HANDOFF, handoffIds, PageRequest.of(0, RECENT_AI_LIMIT))
            .stream()
            .collect(
                Collectors.toMap(
                    AiWorkSuggestion::getSourceId,
                    Function.identity(),
                    (newest, ignored) -> newest,
                    LinkedHashMap::new));

    Map<String, UUID> handoffByIdempotencyKey =
        handoffs.stream()
            .collect(
                Collectors.toMap(
                    handoff -> DRAFT_IDEMPOTENCY_PREFIX + handoff.getId(),
                    ChannelRfqHandoff::getId));
    Map<UUID, DraftQuote> quoteByHandoff = new HashMap<>();
    draftQuoteRepository
        .findByTenantIdAndIdempotencyKeyIn(tenantId, handoffByIdempotencyKey.keySet())
        .forEach(
            quote -> {
              UUID handoffId = handoffByIdempotencyKey.get(quote.getIdempotencyKey());
              if (handoffId != null) {
                quoteByHandoff.put(handoffId, quote);
              }
            });

    List<UUID> quoteIds = quoteByHandoff.values().stream().map(DraftQuote::getId).toList();
    Map<UUID, List<String>> blockerCodesByQuote = blockerCodes(tenantId, quoteIds);

    return handoffs.stream()
        .map(
            handoff -> {
              AiWorkSuggestion ai = latestAiByHandoff.get(handoff.getId());
              DraftQuote quote = quoteByHandoff.get(handoff.getId());
              return new RecentFlow(
                  handoff.getId(),
                  safeText(handoff.getSourceChannel(), "UNKNOWN"),
                  preview(handoff.getRequestText()),
                  safeText(handoff.getDetectedIntent(), "NOT_DETECTED"),
                  handoff.getStatus().name(),
                  ai == null ? "NOT_GENERATED" : safeText(ai.getStatus(), "UNKNOWN"),
                  ai == null ? null : schemaVersion(ai),
                  ai == null ? null : safeText(ai.getRiskLevel(), "UNKNOWN"),
                  quote == null ? "NOT_CREATED" : safeText(quote.getStatus(), "UNKNOWN"),
                  quote == null
                      ? "NOT_AVAILABLE"
                      : safeText(quote.getValidationStatus(), "NOT_AVAILABLE"),
                  quote != null && SAFE_TERMINAL_STATES.contains(quote.getStatus())
                      ? "SAFE_DEMO_TERMINAL"
                      : "NOT_RECORDED",
                  quote == null
                      ? List.of()
                      : blockerCodesByQuote.getOrDefault(quote.getId(), List.of()),
                  handoff.getCreatedAt(),
                  latestUpdate(handoff, ai, quote));
            })
        .toList();
  }

  private Map<UUID, List<String>> blockerCodes(UUID tenantId, List<UUID> quoteIds) {
    if (quoteIds.isEmpty()) {
      return Map.of();
    }
    return issueRepository
        .findByTenantIdAndDraftQuoteIdInAndBlockingTrueAndStatusOrderByCreatedAtAsc(
            tenantId, quoteIds, "OPEN", PageRequest.of(0, RECENT_ISSUE_LIMIT))
        .stream()
        .collect(
            Collectors.groupingBy(
                QuoteValidationIssue::getDraftQuoteId,
                LinkedHashMap::new,
                Collectors.mapping(
                    QuoteValidationIssue::getIssueCode,
                    Collectors.collectingAndThen(
                        Collectors.toCollection(java.util.LinkedHashSet::new), List::copyOf))));
  }

  private static String schemaVersion(AiWorkSuggestion suggestion) {
    try {
      return AiWorkSchemaVersion.forWorkType(suggestion.getWorkType()).name();
    } catch (IllegalArgumentException ex) {
      return "UNKNOWN";
    }
  }

  private static Instant latestUpdate(
      ChannelRfqHandoff handoff, AiWorkSuggestion ai, DraftQuote quote) {
    return java.util.stream.Stream.of(
            handoff.getUpdatedAt(),
            ai == null ? null : ai.getUpdatedAt(),
            quote == null ? null : quote.getUpdatedAt())
        .filter(java.util.Objects::nonNull)
        .max(Comparator.naturalOrder())
        .orElse(handoff.getUpdatedAt());
  }

  private static Bottleneck bottleneck(String code, long count) {
    String safeCode = safeText(code, "VALIDATION_NEEDS_REVIEW");
    return new Bottleneck(
        safeCode,
        label(safeCode),
        count,
        "BLOCKING",
        "An open blocking validation issue requires operator review before any later workflow.");
  }

  private static String label(String code) {
    return switch (code) {
      case "CUSTOMER_NOT_RESOLVED" -> "Customer not resolved";
      case "PRICE_NOT_RESOLVED" -> "Price not resolved";
      case "PRODUCT_NOT_RESOLVED" -> "Product not resolved";
      case "INSUFFICIENT_STOCK" -> "Stock not confirmed";
      case "PRODUCT_MATCH_AMBIGUOUS" -> "Product match ambiguous";
      default -> code.toLowerCase(Locale.ROOT).replace('_', ' ');
    };
  }

  private static String preview(String value) {
    String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
    if (normalized.isEmpty()) {
      return "Request preview unavailable";
    }
    if (normalized.length() <= REQUEST_PREVIEW_LIMIT) {
      return normalized;
    }
    return normalized.substring(0, REQUEST_PREVIEW_LIMIT - 3) + "...";
  }

  private static String safeText(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static List<NotProven> notProven() {
    return List.of(
        new NotProven(
            "EXTERNAL_WRITE_ROWS_NOT_MEASURED",
            "External-write row counts",
            "Connector, change request, and outbox execution row counts are not queried by this read model."),
        new NotProven(
            "RUNTIME_DENIAL_TELEMETRY_NOT_MEASURED",
            "Runtime denial telemetry",
            "Runtime guard denials are not aggregated or displayed in this PR."),
        new NotProven(
            "DISTRIBUTED_RUNTIME_GUARD_NOT_PROVEN",
            "Distributed runtime guard",
            "Multi-node runtime-control behavior remains outside this read model."),
        new NotProven(
            "PRODUCTION_CONVERSION_NOT_MEASURED",
            "Production conversion",
            "Demo completion is not a real order, sale, revenue event, invoice, ERP sync, or customer commitment."));
  }
}
