package com.orderpilot.application.services.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage12BDtos.QuoteValidationIssueDto;
import com.orderpilot.api.dto.Stage12CDtos.QuoteConversionAttemptReviewDetail;
import com.orderpilot.api.dto.Stage12CDtos.QuoteConversionAttemptReviewFilter;
import com.orderpilot.api.dto.Stage12CDtos.QuoteConversionAttemptReviewItem;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.intake.InboundDocument;
import com.orderpilot.domain.intake.InboundDocumentRepository;
import com.orderpilot.domain.workspace.QuoteConversionAttempt;
import com.orderpilot.domain.workspace.QuoteConversionAttemptRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuoteConversionAttemptReviewQueryService {
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final TypeReference<List<QuoteValidationIssueDto>> ISSUE_LIST_TYPE = new TypeReference<>() {};

  private final QuoteConversionAttemptRepository attemptRepository;
  private final ChannelMessageRepository channelMessageRepository;
  private final InboundDocumentRepository inboundDocumentRepository;
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  public QuoteConversionAttemptReviewQueryService(
      QuoteConversionAttemptRepository attemptRepository,
      ChannelMessageRepository channelMessageRepository,
      InboundDocumentRepository inboundDocumentRepository) {
    this.attemptRepository = attemptRepository;
    this.channelMessageRepository = channelMessageRepository;
    this.inboundDocumentRepository = inboundDocumentRepository;
  }

  @Transactional(readOnly = true)
  public List<QuoteConversionAttemptReviewItem> list(QuoteConversionAttemptReviewFilter nullableFilter) {
    UUID tenantId = TenantContext.requireTenantId();
    QuoteConversionAttemptReviewFilter filter = nullableFilter == null ? emptyFilter() : nullableFilter;
    List<QuoteConversionAttempt> attempts = filter.status() == null || filter.status().isBlank()
        ? attemptRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
        : attemptRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, filter.status().trim());
    SourceLookups sources = sourceLookups(tenantId, attempts);
    return attempts.stream()
        .filter(attempt -> filter.createdFrom() == null || !attempt.getCreatedAt().isBefore(filter.createdFrom()))
        .filter(attempt -> filter.createdTo() == null || !attempt.getCreatedAt().isAfter(filter.createdTo()))
        .map(attempt -> item(attempt, sources))
        .filter(item -> filter.reviewRequired() == null || item.reviewRequired() == filter.reviewRequired())
        .filter(item -> filter.reasonCode() == null || filter.reasonCode().isBlank() || item.reasonCodes().contains(filter.reasonCode().trim()))
        .filter(item -> filter.sourceChannel() == null || filter.sourceChannel().isBlank() || filter.sourceChannel().trim().equals(item.sourceChannel()))
        .filter(item -> filter.draftQuoteLinked() == null || item.draftQuoteLinked() == filter.draftQuoteLinked())
        .toList();
  }

  @Transactional(readOnly = true)
  public QuoteConversionAttemptReviewDetail detail(UUID attemptId) {
    UUID tenantId = TenantContext.requireTenantId();
    QuoteConversionAttempt attempt = attemptRepository.findByIdAndTenantId(attemptId, tenantId)
        .orElseThrow(() -> new NotFoundException("Quote conversion attempt not found: " + attemptId));
    SourceLookups sources = sourceLookups(tenantId, List.of(attempt));
    QuoteConversionAttemptReviewItem item = item(attempt, sources);
    Map<String, Object> summary = validationSummary(attempt);
    return new QuoteConversionAttemptReviewDetail(
        item.id(),
        item.sourceType(),
        item.sourceChannel(),
        item.draftQuoteLinked(),
        item.status(),
        item.reviewRequired(),
        item.reasonCode(),
        item.reasonCodes(),
        item.issueCount(),
        item.customerResolution(),
        item.lineCount(),
        item.requestMode(),
        item.triggeredByType(),
        item.createdAt(),
        safeMetadata(summary),
        issues(summary));
  }

  private QuoteConversionAttemptReviewItem item(QuoteConversionAttempt attempt, SourceLookups sources) {
    Map<String, Object> summary = validationSummary(attempt);
    List<QuoteValidationIssueDto> issues = issues(summary);
    List<String> reasonCodes = reasonCodes(attempt, issues);
    String reasonCode = reasonCodes.isEmpty() ? null : reasonCodes.get(0);
    String sourceChannel = sourceChannel(attempt, sources);
    return new QuoteConversionAttemptReviewItem(
        attempt.getId(),
        attempt.getSourceType(),
        sourceChannel,
        attempt.getQuoteId() != null,
        attempt.getStatus(),
        reviewRequired(attempt),
        reasonCode,
        reasonCodes,
        issues.size(),
        stringValue(summary.get("customerResolution")),
        intValue(summary.get("lineCount")),
        attempt.getRequestMode(),
        attempt.getTriggeredByType(),
        attempt.getCreatedAt());
  }

  private SourceLookups sourceLookups(UUID tenantId, List<QuoteConversionAttempt> attempts) {
    List<UUID> channelMessageIds = attempts.stream()
        .filter(attempt -> "CHANNEL_MESSAGE".equals(attempt.getSourceType()))
        .map(QuoteConversionAttempt::getSourceId)
        .distinct()
        .toList();
    List<UUID> documentIds = attempts.stream()
        .filter(attempt -> "INBOUND_DOCUMENT".equals(attempt.getSourceType()))
        .map(QuoteConversionAttempt::getSourceId)
        .distinct()
        .toList();
    Map<UUID, ChannelMessage> messages = channelMessageIds.isEmpty()
        ? Map.of()
        : channelMessageRepository.findByTenantIdAndIdIn(tenantId, channelMessageIds).stream().collect(Collectors.toMap(ChannelMessage::getId, Function.identity()));
    Map<UUID, InboundDocument> documents = documentIds.isEmpty()
        ? Map.of()
        : inboundDocumentRepository.findByTenantIdAndIdIn(tenantId, documentIds).stream().collect(Collectors.toMap(InboundDocument::getId, Function.identity()));
    return new SourceLookups(messages, documents);
  }

  private String sourceChannel(QuoteConversionAttempt attempt, SourceLookups sources) {
    if ("CHANNEL_MESSAGE".equals(attempt.getSourceType())) {
      ChannelMessage message = sources.messages().get(attempt.getSourceId());
      return message == null ? null : message.getChannel();
    }
    if ("INBOUND_DOCUMENT".equals(attempt.getSourceType())) {
      InboundDocument document = sources.documents().get(attempt.getSourceId());
      return document == null ? null : document.getSourceChannel();
    }
    return attempt.getSourceType();
  }

  private boolean reviewRequired(QuoteConversionAttempt attempt) {
    return !"READY_FOR_DRAFT_QUOTE".equals(attempt.getStatus());
  }

  private List<String> reasonCodes(QuoteConversionAttempt attempt, List<QuoteValidationIssueDto> issues) {
    List<String> codes = issues.stream().map(QuoteValidationIssueDto::code).distinct().toList();
    if (!codes.isEmpty()) {
      return codes;
    }
    if (attempt.getFailureCode() != null && !attempt.getFailureCode().isBlank()) {
      return List.of(attempt.getFailureCode());
    }
    return reviewRequired(attempt) ? List.of(attempt.getStatus()) : List.of();
  }

  private Map<String, Object> validationSummary(QuoteConversionAttempt attempt) {
    try {
      return objectMapper.readValue(attempt.getValidationSummaryJson() == null || attempt.getValidationSummaryJson().isBlank() ? "{}" : attempt.getValidationSummaryJson(), MAP_TYPE);
    } catch (Exception ex) {
      return Map.of();
    }
  }

  private List<QuoteValidationIssueDto> issues(Map<String, Object> summary) {
    Object issues = summary.get("issues");
    if (issues == null) {
      return List.of();
    }
    try {
      return objectMapper.convertValue(issues, ISSUE_LIST_TYPE);
    } catch (IllegalArgumentException ex) {
      return List.of();
    }
  }

  private Map<String, Object> safeMetadata(Map<String, Object> summary) {
    Map<String, Object> safe = new LinkedHashMap<>();
    safe.put("lineCount", intValue(summary.get("lineCount")));
    safe.put("customerResolution", stringValue(summary.get("customerResolution")));
    safe.put("issueCount", issues(summary).size());
    return safe;
  }

  private static QuoteConversionAttemptReviewFilter emptyFilter() {
    return new QuoteConversionAttemptReviewFilter(null, null, null, null, null, null, null);
  }

  private static String stringValue(Object value) {
    return value == null ? null : value.toString();
  }

  private static int intValue(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value == null) {
      return 0;
    }
    try {
      return Integer.parseInt(value.toString());
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  private record SourceLookups(Map<UUID, ChannelMessage> messages, Map<UUID, InboundDocument> documents) {}
}
