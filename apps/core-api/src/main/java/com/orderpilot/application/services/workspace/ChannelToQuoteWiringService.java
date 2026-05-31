package com.orderpilot.application.services.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage12ADtos;
import com.orderpilot.api.dto.Stage12BDtos.*;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.extraction.ExtractedLineItem;
import com.orderpilot.domain.extraction.ExtractedLineItemRepository;
import com.orderpilot.domain.extraction.ExtractionResult;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.intake.InboundDocument;
import com.orderpilot.domain.intake.InboundDocumentRepository;
import com.orderpilot.domain.workspace.*;
import com.orderpilot.security.policy.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChannelToQuoteWiringService {
  private static final Pattern TEXT_LINE_PATTERN = Pattern.compile("(?i)\\b([A-Z0-9][A-Z0-9._/-]{2,})\\b.*?\\b(\\d+(?:[.,]\\d+)?)\\s*(ea|pcs|pc|unit|units)?\\b");
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final ChannelMessageRepository channelMessageRepository;
  private final InboundDocumentRepository inboundDocumentRepository;
  private final ExtractionResultRepository extractionResultRepository;
  private final ExtractedLineItemRepository extractedLineItemRepository;
  private final CustomerAccountRepository customerAccountRepository;
  private final QuoteDraftService quoteDraftService;
  private final DraftQuoteRepository draftQuoteRepository;
  private final QuoteConversionAttemptRepository attemptRepository;
  private final QuoteSourceLinkRepository sourceLinkRepository;
  private final AuditEventService auditEventService;
  private final TenantPolicyService tenantPolicyService;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public ChannelToQuoteWiringService(
      ChannelMessageRepository channelMessageRepository,
      InboundDocumentRepository inboundDocumentRepository,
      ExtractionResultRepository extractionResultRepository,
      ExtractedLineItemRepository extractedLineItemRepository,
      CustomerAccountRepository customerAccountRepository,
      QuoteDraftService quoteDraftService,
      DraftQuoteRepository draftQuoteRepository,
      QuoteConversionAttemptRepository attemptRepository,
      QuoteSourceLinkRepository sourceLinkRepository,
      AuditEventService auditEventService,
      TenantPolicyService tenantPolicyService,
      ObjectMapper objectMapper,
      Clock clock) {
    this.channelMessageRepository = channelMessageRepository;
    this.inboundDocumentRepository = inboundDocumentRepository;
    this.extractionResultRepository = extractionResultRepository;
    this.extractedLineItemRepository = extractedLineItemRepository;
    this.customerAccountRepository = customerAccountRepository;
    this.quoteDraftService = quoteDraftService;
    this.draftQuoteRepository = draftQuoteRepository;
    this.attemptRepository = attemptRepository;
    this.sourceLinkRepository = sourceLinkRepository;
    this.auditEventService = auditEventService;
    this.tenantPolicyService = tenantPolicyService;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public ChannelToQuoteResponse createFromChannelMessage(UUID messageId, ChannelToQuoteRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    ChannelMessage message = channelMessageRepository.findByIdAndTenantId(messageId, tenantId)
        .orElseThrow(() -> new NotFoundException("Channel message not found: " + messageId));
    SourceContext source = new SourceContext("CHANNEL_MESSAGE", message.getId(), message.getChannel(), message.getExternalMessageId(), message.getReceivedAt(), message.getCustomerAccountId(), firstNonBlank(message.getNormalizedText(), message.getTextContent(), message.getSenderDisplayName()));
    return convert(source, request);
  }

  @Transactional
  public ChannelToQuoteResponse createFromInboundDocument(UUID documentId, ChannelToQuoteRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    InboundDocument document = inboundDocumentRepository.findByIdAndTenantId(documentId, tenantId)
        .orElseThrow(() -> new NotFoundException("Inbound document not found: " + documentId));
    SourceContext source = new SourceContext("INBOUND_DOCUMENT", document.getId(), document.getSourceChannel(), document.getSha256Fingerprint(), document.getReceivedAt(), null, firstNonBlank(document.getOriginalFilename(), document.getObjectStorageKey()));
    return convert(source, request);
  }

  @Transactional
  public ChannelToQuoteResponse createFromExtraction(UUID extractionId, ChannelToQuoteRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    ExtractionResult extraction = extractionResultRepository.findByIdAndTenantId(extractionId, tenantId)
        .orElseThrow(() -> new NotFoundException("Extraction result not found: " + extractionId));
    SourceContext source = new SourceContext("EXTRACTION_RESULT", extraction.getId(), extraction.getSourceType(), extraction.getSourceId().toString(), clock.instant(), null, extraction.getResultJson());
    return convert(source, request);
  }

  @Transactional(readOnly = true)
  public ChannelToQuoteResponse getAttempt(UUID attemptId) {
    UUID tenantId = TenantContext.requireTenantId();
    QuoteConversionAttempt attempt = attemptRepository.findByIdAndTenantId(attemptId, tenantId)
        .orElseThrow(() -> new NotFoundException("Quote conversion attempt not found: " + attemptId));
    List<QuoteValidationIssueDto> issues = issuesFromJson(attempt.getValidationSummaryJson());
    return new ChannelToQuoteResponse(attempt.getStatus(), attempt.getQuoteId(), attempt.getId(), attempt.getSourceType(), attempt.getSourceId(), null, 0, 0, issues, "NEEDS_REVIEW".equals(attempt.getStatus()), List.of());
  }

  @Transactional(readOnly = true)
  public QuoteSourceContextDto sourceContext(UUID quoteId) {
    UUID tenantId = TenantContext.requireTenantId();
    draftQuoteRepository.findByIdAndTenantId(quoteId, tenantId).orElseThrow(() -> new NotFoundException("Draft quote not found: " + quoteId));
    QuoteSourceLink link = sourceLinkRepository.findFirstByTenantIdAndQuoteId(tenantId, quoteId)
        .orElseThrow(() -> new NotFoundException("Quote source context not found: " + quoteId));
    Optional<QuoteConversionAttempt> attempt = attemptRepository.findFirstByTenantIdAndQuoteIdOrderByCreatedAtDesc(tenantId, quoteId);
    return new QuoteSourceContextDto(
        link.getSourceType(),
        link.getSourceId(),
        link.getSourceChannel(),
        link.getSourceExternalRef(),
        link.getSourceReceivedAt(),
        link.getCreatedBy() == null ? null : link.getCreatedBy().toString(),
        link.getCreatedByType(),
        attempt.map(QuoteConversionAttempt::getId).orElse(null),
        attempt.map(QuoteConversionAttempt::getStatus).orElse(null),
        candidateLinesFor(link.getSourceType(), link.getSourceId()),
        attempt.map(QuoteConversionAttempt::getValidationSummaryJson).map(this::issuesFromJson).orElse(List.of()),
        readMap(link.getMetadataJson()));
  }

  private ChannelToQuoteResponse convert(SourceContext source, ChannelToQuoteRequest nullableRequest) {
    ChannelToQuoteRequest request = nullableRequest == null ? new ChannelToQuoteRequest(null, null, null, null, false, false, List.of(), Map.of(), null, "USER") : nullableRequest;
    UUID tenantId = TenantContext.requireTenantId();
    requireCreatePermission(tenantId, request);
    String requestMode = request.dryRun() ? "DRY_RUN" : "CREATE";
    String idempotencyKey = firstNonBlank(request.idempotencyKey(), source.sourceType + ":" + source.sourceId);
    Optional<QuoteConversionAttempt> replay = isBlank(request.idempotencyKey())
        ? Optional.empty()
        : attemptRepository.findFirstByTenantIdAndSourceTypeAndSourceIdAndIdempotencyKeyAndRequestModeOrderByCreatedAtDesc(tenantId, source.sourceType, source.sourceId, request.idempotencyKey().trim(), requestMode);
    if (replay.isPresent()) {
      AuditEvent audit = auditEventService.record("CHANNEL_TO_QUOTE_ATTEMPTED", "QUOTE_CONVERSION_ATTEMPT", replay.get().getId().toString(), request.actorId(), auditJson(source, request, replay.get().getId(), List.of("IDEMPOTENT_REPLAY"), replay.get().getQuoteId()));
      return new ChannelToQuoteResponse(replay.get().getStatus(), replay.get().getQuoteId(), replay.get().getId(), source.sourceType, source.sourceId, null, 0, 0, issuesFromJson(replay.get().getValidationSummaryJson()), "NEEDS_REVIEW".equals(replay.get().getStatus()), List.of(audit.getId()));
    }

    List<QuoteCandidateLineDto> availableLines = candidateLinesFor(source.sourceType, source.sourceId);
    List<QuoteCandidateLineDto> lines = filterSelectedLines(availableLines, request.selectedLineItemIds());
    CustomerAccount customer = resolveCustomer(tenantId, source, request).orElse(null);
    List<QuoteValidationIssueDto> issues = previewIssues(lines, customer, availableLines, request.selectedLineItemIds());
    String candidateStatus = candidateStatus(lines, customer, issues, request.forceReview());
    String summary = validationSummaryJson(issues, lines.size(), customer);
    QuoteConversionAttempt attempt = attemptRepository.save(new QuoteConversionAttempt(tenantId, source.sourceType, source.sourceId, candidateStatus, summary, request.actorId(), actorType(request.actorType()), request.idempotencyKey(), requestMode, clock.instant()));
    AuditEvent attempted = auditEventService.record("CHANNEL_TO_QUOTE_ATTEMPTED", "QUOTE_CONVERSION_ATTEMPT", attempt.getId().toString(), request.actorId(), auditJson(source, request, attempt.getId(), reasonCodes(issues), null));

    if (request.dryRun()) {
      attempt.markTerminal(candidateStatus, null, null, summary);
      attemptRepository.save(attempt);
      AuditEvent dryRun = auditEventService.record("CHANNEL_TO_QUOTE_DRY_RUN", "QUOTE_CONVERSION_ATTEMPT", attempt.getId().toString(), request.actorId(), auditJson(source, request, attempt.getId(), reasonCodes(issues), null));
      return response(candidateStatus, null, attempt.getId(), source, customer, lines, issues, List.of(attempted.getId(), dryRun.getId()));
    }
    if (!"READY_FOR_DRAFT_QUOTE".equals(candidateStatus)) {
      String action = "NEEDS_REVIEW".equals(candidateStatus) ? "CHANNEL_TO_QUOTE_REVIEW_REQUIRED" : "CHANNEL_TO_QUOTE_REJECTED";
      String failureCode = issues.isEmpty() ? candidateStatus : issues.get(0).code();
      attempt.markTerminal(candidateStatus, failureCode, candidateStatus, summary);
      attemptRepository.save(attempt);
      AuditEvent terminal = auditEventService.record(action, "QUOTE_CONVERSION_ATTEMPT", attempt.getId().toString(), request.actorId(), auditJson(source, request, attempt.getId(), reasonCodes(issues), null));
      return response(candidateStatus, null, attempt.getId(), source, customer, lines, issues, List.of(attempted.getId(), terminal.getId()));
    }

    Stage12ADtos.QuoteTransactionResponse quote = quoteDraftService.createFromRfq(new Stage12ADtos.CreateDraftQuoteFromRfqCommand(
        tenantId,
        request.actorId(),
        actorType(request.actorType()),
        customer == null ? null : customer.getAccountCode(),
        customer == null ? null : customer.getDisplayName(),
        lines.stream().map(line -> new Stage12ADtos.RequestedItem(line.rawSkuOrAlias(), line.description(), line.quantity(), line.uom())).toList(),
        null,
        BigDecimal.ZERO,
        "channel-to-quote:" + source.sourceType + ":" + source.sourceId + ":" + idempotencyKey));
    List<QuoteValidationIssueDto> mergedIssues = new ArrayList<>(issues);
    mergedIssues.addAll(quote.validationIssues().stream().map(issue -> new QuoteValidationIssueDto(issue.issueCode(), issue.severity(), issue.blocking(), issue.message(), issue.lineId())).toList());
    String finalStatus = quote.approvalRequired() || mergedIssues.stream().anyMatch(QuoteValidationIssueDto::blocking) || !"DRAFT".equals(quote.status())
        ? "NEEDS_REVIEW"
        : "READY_FOR_DRAFT_QUOTE";
    attempt.markDraftCreated(quote.draftQuoteId(), finalStatus, validationSummaryJson(mergedIssues, lines.size(), customer));
    attemptRepository.save(attempt);
    sourceLinkRepository.save(new QuoteSourceLink(tenantId, quote.draftQuoteId(), source.sourceType, source.sourceId, source.channel, source.externalRef, source.receivedAt, request.actorId(), actorType(request.actorType()), metadataJson(source, attempt.getId(), lines), clock.instant()));
    AuditEvent created = auditEventService.record("CHANNEL_TO_QUOTE_DRAFT_CREATED", "DRAFT_QUOTE", quote.draftQuoteId().toString(), request.actorId(), auditJson(source, request, attempt.getId(), reasonCodes(mergedIssues), quote.draftQuoteId()));
    AuditEvent linked = auditEventService.record("QUOTE_SOURCE_LINKED", "DRAFT_QUOTE", quote.draftQuoteId().toString(), request.actorId(), auditJson(source, request, attempt.getId(), reasonCodes(mergedIssues), quote.draftQuoteId()));
    return response(finalStatus, quote.draftQuoteId(), attempt.getId(), source, customer, lines, mergedIssues, List.of(attempted.getId(), created.getId(), linked.getId()));
  }

  private List<QuoteCandidateLineDto> candidateLinesFor(String sourceType, UUID sourceId) {
    UUID tenantId = TenantContext.requireTenantId();
    if ("EXTRACTION_RESULT".equals(sourceType)) {
      return extractedLineItemRepository.findByTenantIdAndExtractionResultId(tenantId, sourceId).stream().map(this::lineFromExtraction).toList();
    }
    List<ExtractionResult> results = extractionResultRepository.findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(tenantId, sourceType, sourceId);
    if (!results.isEmpty()) {
      return extractedLineItemRepository.findByTenantIdAndExtractionResultId(tenantId, results.get(0).getId()).stream().map(this::lineFromExtraction).toList();
    }
    if ("CHANNEL_MESSAGE".equals(sourceType)) {
      return channelMessageRepository.findByIdAndTenantId(sourceId, tenantId).map(this::lineFromMessageText).orElse(List.of());
    }
    return List.of();
  }

  private List<QuoteCandidateLineDto> filterSelectedLines(List<QuoteCandidateLineDto> lines, List<UUID> selectedLineItemIds) {
    if (selectedLineItemIds == null || selectedLineItemIds.isEmpty()) {
      return lines;
    }
    return lines.stream()
        .filter(line -> line.sourceLineItemId() != null && selectedLineItemIds.contains(line.sourceLineItemId()))
        .toList();
  }

  private QuoteCandidateLineDto lineFromExtraction(ExtractedLineItem item) {
    return new QuoteCandidateLineDto(item.getId(), item.getLineNumber(), item.getRawSku(), item.getRawDescription(), item.getNormalizedQuantity(), firstNonBlank(item.getNormalizedUom(), item.getRawUom()), item.getRequestedDate(), item.getSourceEvidenceId(), item.getValidationStatus());
  }

  private List<QuoteCandidateLineDto> lineFromMessageText(ChannelMessage message) {
    String text = firstNonBlank(message.getNormalizedText(), message.getTextContent());
    if (isBlank(text)) {
      return List.of();
    }
    Matcher matcher = TEXT_LINE_PATTERN.matcher(text);
    if (!matcher.find()) {
      return List.of(new QuoteCandidateLineDto(null, 1, text.length() > 80 ? text.substring(0, 80) : text, text, BigDecimal.ONE, "EA", null, null, "TEXT_HEURISTIC"));
    }
    return List.of(new QuoteCandidateLineDto(null, 1, matcher.group(1), text, new BigDecimal(matcher.group(2).replace(',', '.')), firstNonBlank(matcher.group(3), "EA"), null, null, "TEXT_HEURISTIC"));
  }

  private Optional<CustomerAccount> resolveCustomer(UUID tenantId, SourceContext source, ChannelToQuoteRequest request) {
    if (request.requestedCustomerAccountId() != null) {
      return customerAccountRepository.findByIdAndTenantIdAndDeletedAtIsNull(request.requestedCustomerAccountId(), tenantId);
    }
    if (source.customerAccountId != null) {
      return customerAccountRepository.findByIdAndTenantIdAndDeletedAtIsNull(source.customerAccountId, tenantId);
    }
    return Optional.empty();
  }

  private List<QuoteValidationIssueDto> previewIssues(List<QuoteCandidateLineDto> lines, CustomerAccount customer, List<QuoteCandidateLineDto> availableLines, List<UUID> selectedLineItemIds) {
    List<QuoteValidationIssueDto> issues = new ArrayList<>();
    if (customer == null) {
      issues.add(new QuoteValidationIssueDto("CUSTOMER_UNRESOLVED", "ERROR", true, "Customer account must be resolved before draft quote creation", null));
    }
    if (lines.isEmpty()) {
      issues.add(new QuoteValidationIssueDto("NO_LINE_ITEMS", "ERROR", true, "Source did not contain extracted or parseable line items", null));
    }
    if (selectedLineItemIds != null && !selectedLineItemIds.isEmpty()) {
      List<UUID> availableIds = availableLines.stream().map(QuoteCandidateLineDto::sourceLineItemId).filter(id -> id != null).toList();
      for (UUID selectedId : selectedLineItemIds) {
        if (!availableIds.contains(selectedId)) {
          issues.add(new QuoteValidationIssueDto("SELECTED_LINE_NOT_IN_SOURCE", "ERROR", true, "Selected line item does not belong to this tenant-scoped source", selectedId));
        }
      }
    }
    for (QuoteCandidateLineDto line : lines) {
      if (line.quantity() == null || line.quantity().compareTo(BigDecimal.ZERO) <= 0) {
        issues.add(new QuoteValidationIssueDto("INVALID_QUANTITY", "ERROR", true, "Quantity must be greater than zero", line.sourceLineItemId()));
      }
      if (isBlank(line.rawSkuOrAlias()) && isBlank(line.description())) {
        issues.add(new QuoteValidationIssueDto("SKU_OR_DESCRIPTION_REQUIRED", "ERROR", true, "Line item requires SKU, alias, OEM reference, or description", line.sourceLineItemId()));
      }
    }
    return issues;
  }

  private String candidateStatus(List<QuoteCandidateLineDto> lines, CustomerAccount customer, List<QuoteValidationIssueDto> issues, boolean forceReview) {
    if (lines.isEmpty()) return "REJECTED_NO_LINE_ITEMS";
    if (customer == null) return "NEEDS_REVIEW";
    if (issues.stream().anyMatch(QuoteValidationIssueDto::blocking)) return "REJECTED_VALIDATION_FAILED";
    if (forceReview) return "NEEDS_REVIEW";
    return "READY_FOR_DRAFT_QUOTE";
  }

  private void requireCreatePermission(UUID tenantId, ChannelToQuoteRequest request) {
    ActorRole role = "BOT".equalsIgnoreCase(actorType(request.actorType())) ? ActorRole.OPERATOR : ActorRole.OPERATOR;
    tenantPolicyService.requireAllowed(TenantPolicyContext.builder()
        .tenantId(tenantId)
        .targetTenantId(tenantId)
        .actorId(request.actorId())
        .actorRoles(java.util.Set.of(role))
        .action(TenantPolicyAction.CREATE_DRAFT_QUOTE)
        .resourceType(ResourceType.QUOTE)
        .systemActor(false)
        .build());
  }

  private ChannelToQuoteResponse response(String status, UUID quoteId, UUID attemptId, SourceContext source, CustomerAccount customer, List<QuoteCandidateLineDto> lines, List<QuoteValidationIssueDto> issues, List<UUID> auditEventIds) {
    return new ChannelToQuoteResponse(status, quoteId, attemptId, source.sourceType, source.sourceId, customer == null ? "UNRESOLVED" : "RESOLVED", lines.size(), issues.stream().noneMatch(QuoteValidationIssueDto::blocking) ? lines.size() : 0, issues, !"READY_FOR_DRAFT_QUOTE".equals(status), auditEventIds);
  }

  private String validationSummaryJson(List<QuoteValidationIssueDto> issues, int lineCount, CustomerAccount customer) {
    try {
      return objectMapper.writeValueAsString(Map.of("lineCount", lineCount, "customerResolution", customer == null ? "UNRESOLVED" : "RESOLVED", "issues", issues));
    } catch (Exception ex) {
      return "{}";
    }
  }

  private List<QuoteValidationIssueDto> issuesFromJson(String json) {
    try {
      Map<String, Object> map = readMap(json);
      Object issues = map.get("issues");
      if (issues == null) {
        return List.of();
      }
      return objectMapper.convertValue(issues, new TypeReference<List<QuoteValidationIssueDto>>() {});
    } catch (Exception ex) {
      return List.of();
    }
  }

  private Map<String, Object> readMap(String json) {
    try {
      return objectMapper.readValue(isBlank(json) ? "{}" : json, MAP_TYPE);
    } catch (Exception ex) {
      return Map.of();
    }
  }

  private String metadataJson(SourceContext source, UUID attemptId, List<QuoteCandidateLineDto> lines) {
    try {
      return objectMapper.writeValueAsString(Map.of("conversionAttemptId", attemptId, "sourceType", source.sourceType, "sourceId", source.sourceId, "lineCount", lines.size()));
    } catch (Exception ex) {
      return "{}";
    }
  }

  private String auditJson(SourceContext source, ChannelToQuoteRequest request, UUID conversionAttemptId, List<String> reasonCodes, UUID quoteId) {
    try {
      return objectMapper.writeValueAsString(Map.of(
          "sourceType", source.sourceType,
          "sourceId", source.sourceId,
          "sourceChannel", source.channel == null ? "" : source.channel,
          "quoteId", quoteId == null ? "" : quoteId.toString(),
          "conversionAttemptId", conversionAttemptId == null ? "" : conversionAttemptId.toString(),
          "actorType", actorType(request.actorType()),
          "reasonCodes", reasonCodes,
          "externalExecution", "DISABLED"));
    } catch (Exception ex) {
      return "{}";
    }
  }

  private List<String> reasonCodes(List<QuoteValidationIssueDto> issues) {
    return issues.stream().map(QuoteValidationIssueDto::code).distinct().toList();
  }

  private static String actorType(String actorType) {
    if (isBlank(actorType)) return "USER";
    String normalized = actorType.trim().toUpperCase(Locale.ROOT);
    if (List.of("USER", "BOT", "SYSTEM", "API").contains(normalized)) return normalized;
    return "USER";
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String firstNonBlank(String... values) {
    if (values == null) return null;
    for (String value : values) {
      if (!isBlank(value)) return value.trim();
    }
    return null;
  }

  private record SourceContext(String sourceType, UUID sourceId, String channel, String externalRef, Instant receivedAt, UUID customerAccountId, String customerHint) {}
}
