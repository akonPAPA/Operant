package com.orderpilot.application.services.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage12ADtos.*;
import com.orderpilot.api.dto.Stage12BDtos.ChannelToQuoteRequest;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.extraction.ExtractedLineItem;
import com.orderpilot.domain.extraction.ExtractedLineItemRepository;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.intake.InboundDocument;
import com.orderpilot.domain.intake.InboundDocumentRepository;
import com.orderpilot.domain.workspace.*;
import com.orderpilot.security.policy.TenantPolicyException;
import com.orderpilot.security.policy.TenantPolicyService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ChannelToQuoteWiringServiceTest {
  private final ChannelMessageRepository channelMessageRepository = mock(ChannelMessageRepository.class);
  private final InboundDocumentRepository inboundDocumentRepository = mock(InboundDocumentRepository.class);
  private final ExtractionResultRepository extractionResultRepository = mock(ExtractionResultRepository.class);
  private final ExtractedLineItemRepository extractedLineItemRepository = mock(ExtractedLineItemRepository.class);
  private final CustomerAccountRepository customerAccountRepository = mock(CustomerAccountRepository.class);
  private final QuoteDraftService quoteDraftService = mock(QuoteDraftService.class);
  private final DraftQuoteRepository draftQuoteRepository = mock(DraftQuoteRepository.class);
  private final QuoteConversionAttemptRepository attemptRepository = mock(QuoteConversionAttemptRepository.class);
  private final QuoteSourceLinkRepository sourceLinkRepository = mock(QuoteSourceLinkRepository.class);
  private final AuditEventService auditEventService = mock(AuditEventService.class);
  private final TenantPolicyService tenantPolicyService = mock(TenantPolicyService.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-05-30T00:00:00Z"), ZoneOffset.UTC);
  private final ChannelToQuoteWiringService service = new ChannelToQuoteWiringService(
      channelMessageRepository,
      inboundDocumentRepository,
      extractionResultRepository,
      extractedLineItemRepository,
      customerAccountRepository,
      quoteDraftService,
      draftQuoteRepository,
      attemptRepository,
      sourceLinkRepository,
      auditEventService,
      tenantPolicyService,
      new ObjectMapper().findAndRegisterModules(),
      clock);

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void createsDraftQuoteFromValidChannelMessageAndLinksSource() {
    UUID tenantId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage message = message(messageId, customerId, "Need BRK-001 2 ea");
    CustomerAccount customer = customer(customerId, "CUST-1");
    when(channelMessageRepository.findByIdAndTenantId(messageId, tenantId)).thenReturn(Optional.of(message));
    when(customerAccountRepository.findByIdAndTenantIdAndDeletedAtIsNull(customerId, tenantId)).thenReturn(Optional.of(customer));
    when(extractionResultRepository.findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(tenantId, "CHANNEL_MESSAGE", messageId)).thenReturn(List.of());
    stubAttemptSave();
    when(sourceLinkRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(auditEventService.record(any(), any(), any(), any(), any())).thenReturn(audit());
    when(quoteDraftService.createFromRfq(any())).thenReturn(new QuoteTransactionResponse(quoteId, "DRAFT", new ResolvedCustomer(customerId, null, "CUST-1", "ACME", "RESOLVED"), List.of(), List.of(), List.of(), false, List.of(), UUID.randomUUID(), List.of()));

    var response = service.createFromChannelMessage(messageId, request("idem-1", customerId, false, "USER"));

    assertThat(response.status()).isEqualTo("READY_FOR_DRAFT_QUOTE");
    assertThat(response.quoteId()).isEqualTo(quoteId);
    verify(quoteDraftService).createFromRfq(any());
    verify(sourceLinkRepository).save(any(QuoteSourceLink.class));
    verify(auditEventService).record(eq("QUOTE_SOURCE_LINKED"), eq("DRAFT_QUOTE"), eq(quoteId.toString()), any(), any());
  }

  @Test
  void createsDraftQuoteFromValidInboundDocumentExtractionLines() {
    UUID tenantId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID extractionId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    InboundDocument document = document(documentId);
    when(inboundDocumentRepository.findByIdAndTenantId(documentId, tenantId)).thenReturn(Optional.of(document));
    var extraction = mock(com.orderpilot.domain.extraction.ExtractionResult.class);
    when(extraction.getId()).thenReturn(extractionId);
    when(extractionResultRepository.findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(tenantId, "INBOUND_DOCUMENT", documentId)).thenReturn(List.of(extraction));
    when(extractedLineItemRepository.findByTenantIdAndExtractionResultId(tenantId, extractionId)).thenReturn(List.of(extractedLine(extractionId, "BRK-001")));
    when(customerAccountRepository.findByIdAndTenantIdAndDeletedAtIsNull(customerId, tenantId)).thenReturn(Optional.of(customer(customerId, "CUST-1")));
    stubAttemptSave();
    when(auditEventService.record(any(), any(), any(), any(), any())).thenReturn(audit());
    when(quoteDraftService.createFromRfq(any())).thenReturn(new QuoteTransactionResponse(quoteId, "DRAFT", null, List.of(), List.of(), List.of(), false, List.of(), UUID.randomUUID(), List.of()));

    var response = service.createFromInboundDocument(documentId, request("doc-idem", customerId, false, "API"));

    assertThat(response.quoteId()).isEqualTo(quoteId);
    assertThat(response.lineCount()).isEqualTo(1);
    verify(sourceLinkRepository).save(any(QuoteSourceLink.class));
  }

  @Test
  void tenantIsolationReturnsTenantSafeNotFoundForForeignSource() {
    UUID tenantId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    when(channelMessageRepository.findByIdAndTenantId(messageId, tenantId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.createFromChannelMessage(messageId, request("idem", UUID.randomUUID(), false, "USER")))
        .isInstanceOf(NotFoundException.class);
    verifyNoInteractions(quoteDraftService);
  }

  @Test
  void permissionDenialPreventsConversionMutation() {
    UUID tenantId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    when(channelMessageRepository.findByIdAndTenantId(messageId, tenantId)).thenReturn(Optional.of(message(messageId, customerId, "Need BRK-001 2 ea")));
    doThrow(new TenantPolicyException("not allowed")).when(tenantPolicyService).requireAllowed(any());

    assertThatThrownBy(() -> service.createFromChannelMessage(messageId, request("denied", customerId, false, "USER")))
        .isInstanceOf(TenantPolicyException.class);
    verifyNoInteractions(quoteDraftService);
    verifyNoInteractions(attemptRepository);
    verifyNoInteractions(sourceLinkRepository);
    verify(auditEventService).record(eq("CHANNEL_TO_QUOTE_CONVERSION_REJECTED"), eq("CHANNEL_MESSAGE"), eq(messageId.toString()), any(), contains("POLICY_DENIED"));
  }

  @Test
  void dryRunDoesNotCreateQuote() {
    UUID tenantId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    when(channelMessageRepository.findByIdAndTenantId(messageId, tenantId)).thenReturn(Optional.of(message(messageId, customerId, "Need BRK-001 2 ea")));
    when(customerAccountRepository.findByIdAndTenantIdAndDeletedAtIsNull(customerId, tenantId)).thenReturn(Optional.of(customer(customerId, "CUST-1")));
    when(extractionResultRepository.findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(tenantId, "CHANNEL_MESSAGE", messageId)).thenReturn(List.of());
    stubAttemptSave();
    when(auditEventService.record(any(), any(), any(), any(), any())).thenReturn(audit());

    var response = service.createFromChannelMessage(messageId, request("dry-1", customerId, true, "USER"));

    assertThat(response.quoteId()).isNull();
    assertThat(response.status()).isEqualTo("READY_FOR_DRAFT_QUOTE");
    verifyNoInteractions(quoteDraftService);
    verifyNoInteractions(sourceLinkRepository);
    verify(auditEventService).record(eq("CHANNEL_TO_QUOTE_DRY_RUN"), any(), any(), any(), any());
  }

  @Test
  void dryRunIdempotencyDoesNotBlockLaterCreateWithSameKey() {
    UUID tenantId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    when(channelMessageRepository.findByIdAndTenantId(messageId, tenantId)).thenReturn(Optional.of(message(messageId, customerId, "Need BRK-001 2 ea")));
    when(customerAccountRepository.findByIdAndTenantIdAndDeletedAtIsNull(customerId, tenantId)).thenReturn(Optional.of(customer(customerId, "CUST-1")));
    when(extractionResultRepository.findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(tenantId, "CHANNEL_MESSAGE", messageId)).thenReturn(List.of());
    stubAttemptSave();
    when(auditEventService.record(any(), any(), any(), any(), any())).thenReturn(audit());
    when(quoteDraftService.createFromRfq(any())).thenReturn(new QuoteTransactionResponse(quoteId, "DRAFT", null, List.of(), List.of(), List.of(), false, List.of(), UUID.randomUUID(), List.of()));

    service.createFromChannelMessage(messageId, request("same-key", customerId, true, "USER"));
    var response = service.createFromChannelMessage(messageId, request("same-key", customerId, false, "USER"));

    assertThat(response.quoteId()).isEqualTo(quoteId);
    verify(attemptRepository).findFirstByTenantIdAndSourceTypeAndSourceIdAndIdempotencyKeyAndRequestModeOrderByCreatedAtDesc(tenantId, "CHANNEL_MESSAGE", messageId, "same-key", "DRY_RUN");
    verify(attemptRepository).findFirstByTenantIdAndSourceTypeAndSourceIdAndIdempotencyKeyAndRequestModeOrderByCreatedAtDesc(tenantId, "CHANNEL_MESSAGE", messageId, "same-key", "CREATE");
    verify(quoteDraftService).createFromRfq(any());
  }

  @Test
  void idempotencyPreventsDuplicateQuoteCreation() {
    UUID tenantId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    when(channelMessageRepository.findByIdAndTenantId(messageId, tenantId)).thenReturn(Optional.of(message(messageId, customerId, "Need BRK-001 2 ea")));
    QuoteConversionAttempt previous = mock(QuoteConversionAttempt.class);
    when(previous.getId()).thenReturn(UUID.randomUUID());
    when(previous.getStatus()).thenReturn("READY_FOR_DRAFT_QUOTE");
    when(previous.getQuoteId()).thenReturn(quoteId);
    when(previous.getValidationSummaryJson()).thenReturn("{}");
    when(attemptRepository.findFirstByTenantIdAndSourceTypeAndSourceIdAndIdempotencyKeyAndRequestModeOrderByCreatedAtDesc(tenantId, "CHANNEL_MESSAGE", messageId, "idem-dup", "CREATE")).thenReturn(Optional.of(previous));
    when(auditEventService.record(any(), any(), any(), any(), any())).thenReturn(audit());

    var response = service.createFromChannelMessage(messageId, request("idem-dup", customerId, false, "API"));

    assertThat(response.quoteId()).isEqualTo(quoteId);
    verifyNoInteractions(quoteDraftService);
    verify(attemptRepository, never()).save(any());
  }

  @Test
  void unresolvedCustomerRoutesToReviewAndDoesNotCreateQuote() {
    UUID tenantId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    when(channelMessageRepository.findByIdAndTenantId(messageId, tenantId)).thenReturn(Optional.of(message(messageId, customerId, "Need BRK-001 2 ea")));
    when(customerAccountRepository.findByIdAndTenantIdAndDeletedAtIsNull(customerId, tenantId)).thenReturn(Optional.empty());
    when(extractionResultRepository.findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(tenantId, "CHANNEL_MESSAGE", messageId)).thenReturn(List.of());
    stubAttemptSave();
    when(auditEventService.record(any(), any(), any(), any(), any())).thenReturn(audit());

    var response = service.createFromChannelMessage(messageId, request("review-1", null, false, "USER"));

    assertThat(response.status()).isEqualTo("NEEDS_REVIEW");
    assertThat(response.quoteId()).isNull();
    assertThat(response.reviewRequired()).isTrue();
    assertThat(response.validationIssues()).extracting("code").contains("CUSTOMER_UNRESOLVED");
    verifyNoInteractions(quoteDraftService);
    verifyNoInteractions(sourceLinkRepository);
    verify(auditEventService).record(eq("CHANNEL_TO_QUOTE_VALIDATION_ISSUE_CREATED"), eq("QUOTE_CONVERSION_ATTEMPT"), any(), any(), contains("CUSTOMER_UNRESOLVED"));
    verify(auditEventService).record(eq("CHANNEL_TO_QUOTE_REVIEW_REQUIRED"), eq("QUOTE_CONVERSION_ATTEMPT"), any(), any(), contains("CUSTOMER_UNRESOLVED"));
    ArgumentCaptor<QuoteConversionAttempt> attempts = ArgumentCaptor.forClass(QuoteConversionAttempt.class);
    verify(attemptRepository, atLeastOnce()).save(attempts.capture());
    assertThat(attempts.getAllValues()).anySatisfy(attempt -> {
      assertThat(attempt.getTenantId()).isEqualTo(tenantId);
      assertThat(attempt.getQuoteId()).isNull();
      assertThat(attempt.getFailureCode()).isEqualTo("CUSTOMER_UNRESOLVED");
      assertThat(attempt.getValidationSummaryJson()).contains("CUSTOMER_UNRESOLVED");
    });
    ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
    verify(auditEventService, atLeastOnce()).record(any(), any(), any(), any(), metadata.capture());
    assertThat(metadata.getAllValues()).anySatisfy(value -> {
      assertThat(value).contains("\"sourceType\":\"CHANNEL_MESSAGE\"");
      assertThat(value).contains("\"sourceChannel\":\"TELEGRAM\"");
      assertThat(value).contains("\"draftQuoteId\":null");
      assertThat(value).contains("\"customerId\":null");
      assertThat(value).contains("\"externalExecution\":\"DISABLED\"");
    });
  }

  @Test
  void noLineItemsRejectsWithoutDraftQuote() {
    UUID tenantId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    when(channelMessageRepository.findByIdAndTenantId(messageId, tenantId)).thenReturn(Optional.of(message(messageId, customerId, "")));
    when(customerAccountRepository.findByIdAndTenantIdAndDeletedAtIsNull(customerId, tenantId)).thenReturn(Optional.of(customer(customerId, "CUST-1")));
    when(extractionResultRepository.findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(tenantId, "CHANNEL_MESSAGE", messageId)).thenReturn(List.of());
    stubAttemptSave();
    when(auditEventService.record(any(), any(), any(), any(), any())).thenReturn(audit());

    var response = service.createFromChannelMessage(messageId, request("empty-1", customerId, false, "USER"));

    assertThat(response.status()).isEqualTo("REJECTED_NO_LINE_ITEMS");
    assertThat(response.quoteId()).isNull();
    assertThat(response.reviewRequired()).isTrue();
    assertThat(response.validationIssues()).extracting("code").contains("NO_LINE_ITEMS");
    verifyNoInteractions(quoteDraftService);
    verifyNoInteractions(sourceLinkRepository);
    verify(auditEventService).record(eq("CHANNEL_TO_QUOTE_VALIDATION_ISSUE_CREATED"), eq("QUOTE_CONVERSION_ATTEMPT"), any(), any(), contains("NO_LINE_ITEMS"));
    verify(auditEventService).record(eq("CHANNEL_TO_QUOTE_CONVERSION_REJECTED"), eq("QUOTE_CONVERSION_ATTEMPT"), any(), any(), contains("NO_LINE_ITEMS"));
    ArgumentCaptor<QuoteConversionAttempt> attempts = ArgumentCaptor.forClass(QuoteConversionAttempt.class);
    verify(attemptRepository, atLeastOnce()).save(attempts.capture());
    assertThat(attempts.getAllValues()).anySatisfy(attempt -> {
      assertThat(attempt.getTenantId()).isEqualTo(tenantId);
      assertThat(attempt.getStatus()).isEqualTo("REJECTED_NO_LINE_ITEMS");
      assertThat(attempt.getQuoteId()).isNull();
      assertThat(attempt.getFailureCode()).isEqualTo("NO_LINE_ITEMS");
    });
  }

  @Test
  void downstreamInvalidSkuValidationIsReturnedAsReviewRequired() {
    UUID tenantId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    UUID issueId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    when(channelMessageRepository.findByIdAndTenantId(messageId, tenantId)).thenReturn(Optional.of(message(messageId, customerId, "Need BAD-SKU 2 ea")));
    when(customerAccountRepository.findByIdAndTenantIdAndDeletedAtIsNull(customerId, tenantId)).thenReturn(Optional.of(customer(customerId, "CUST-1")));
    when(extractionResultRepository.findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(tenantId, "CHANNEL_MESSAGE", messageId)).thenReturn(List.of());
    stubAttemptSave();
    when(auditEventService.record(any(), any(), any(), any(), any())).thenReturn(audit());
    when(quoteDraftService.createFromRfq(any())).thenReturn(new QuoteTransactionResponse(
        quoteId,
        "NEEDS_REVIEW",
        null,
        List.of(),
        List.of(new ValidationIssue(issueId, null, "PRODUCT_NOT_RESOLVED", "ERROR", true, "Product could not be resolved", "OPEN")),
        List.of(),
        false,
        List.of(),
        UUID.randomUUID(),
        List.of()));

    var response = service.createFromChannelMessage(messageId, request("bad-sku", customerId, false, "USER"));

    assertThat(response.status()).isEqualTo("NEEDS_REVIEW");
    assertThat(response.reviewRequired()).isTrue();
    assertThat(response.validationIssues()).extracting("code").contains("PRODUCT_NOT_RESOLVED");
    verify(sourceLinkRepository).save(any(QuoteSourceLink.class));
    ArgumentCaptor<QuoteConversionAttempt> attempts = ArgumentCaptor.forClass(QuoteConversionAttempt.class);
    verify(attemptRepository, atLeastOnce()).save(attempts.capture());
    assertThat(attempts.getAllValues()).anySatisfy(attempt -> assertThat(attempt.getStatus()).isEqualTo("NEEDS_REVIEW"));
  }

  @Test
  void selectedLineItemIdsCannotSelectLineOutsideTenantScopedSource() {
    UUID tenantId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID extractionId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    UUID sourceLineId = UUID.randomUUID();
    UUID foreignLineId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    when(inboundDocumentRepository.findByIdAndTenantId(documentId, tenantId)).thenReturn(Optional.of(document(documentId)));
    var extraction = mock(com.orderpilot.domain.extraction.ExtractionResult.class);
    when(extraction.getId()).thenReturn(extractionId);
    when(extractionResultRepository.findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(tenantId, "INBOUND_DOCUMENT", documentId)).thenReturn(List.of(extraction));
    when(extractedLineItemRepository.findByTenantIdAndExtractionResultId(tenantId, extractionId)).thenReturn(List.of(extractedLine(extractionId, sourceLineId, "BRK-001")));
    when(customerAccountRepository.findByIdAndTenantIdAndDeletedAtIsNull(customerId, tenantId)).thenReturn(Optional.of(customer(customerId, "CUST-1")));
    stubAttemptSave();
    when(auditEventService.record(any(), any(), any(), any(), any())).thenReturn(audit());

    var response = service.createFromInboundDocument(documentId, new ChannelToQuoteRequest("foreign-line", customerId, "RFQ", null, false, false, List.of(foreignLineId), Map.of(), UUID.randomUUID(), "USER"));

    assertThat(response.status()).isEqualTo("REJECTED_NO_LINE_ITEMS");
    assertThat(response.validationIssues()).extracting("code").contains("SELECTED_LINE_NOT_IN_SOURCE");
    verifyNoInteractions(quoteDraftService);
    verifyNoInteractions(sourceLinkRepository);
  }

  @Test
  void sourceContextEndpointDoesNotRevealOtherTenantQuote() {
    UUID tenantId = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    when(draftQuoteRepository.findByIdAndTenantId(quoteId, tenantId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.sourceContext(quoteId))
        .isInstanceOf(NotFoundException.class);
    verifyNoInteractions(sourceLinkRepository);
  }

  @Test
  void auditMetadataIncludesConversionAttemptAndSourceReference() {
    UUID tenantId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    when(channelMessageRepository.findByIdAndTenantId(messageId, tenantId)).thenReturn(Optional.of(message(messageId, customerId, "Need BRK-001 2 ea")));
    when(customerAccountRepository.findByIdAndTenantIdAndDeletedAtIsNull(customerId, tenantId)).thenReturn(Optional.of(customer(customerId, "CUST-1")));
    when(extractionResultRepository.findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(tenantId, "CHANNEL_MESSAGE", messageId)).thenReturn(List.of());
    stubAttemptSave();
    when(auditEventService.record(any(), any(), any(), any(), any())).thenReturn(audit());
    when(quoteDraftService.createFromRfq(any())).thenReturn(new QuoteTransactionResponse(quoteId, "DRAFT", null, List.of(), List.of(), List.of(), false, List.of(), UUID.randomUUID(), List.of()));

    service.createFromChannelMessage(messageId, request("audit-1", customerId, false, "API"));

    ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
    verify(auditEventService, atLeastOnce()).record(any(), any(), any(), any(), metadata.capture());
    assertThat(metadata.getAllValues()).anySatisfy(value -> {
      assertThat(value).contains("\"conversionAttemptId\"");
      assertThat(value).contains("\"sourceType\":\"CHANNEL_MESSAGE\"");
      assertThat(value).contains(messageId.toString());
    });
  }

  @Test
  void botActorCanOnlyProduceDraftWorkflowNotApprovedQuote() {
    UUID tenantId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    UUID customerId = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    when(channelMessageRepository.findByIdAndTenantId(messageId, tenantId)).thenReturn(Optional.of(message(messageId, customerId, "Need BRK-001 2 ea")));
    when(customerAccountRepository.findByIdAndTenantIdAndDeletedAtIsNull(customerId, tenantId)).thenReturn(Optional.of(customer(customerId, "CUST-1")));
    when(extractionResultRepository.findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(tenantId, "CHANNEL_MESSAGE", messageId)).thenReturn(List.of());
    stubAttemptSave();
    when(auditEventService.record(any(), any(), any(), any(), any())).thenReturn(audit());
    when(quoteDraftService.createFromRfq(any())).thenReturn(new QuoteTransactionResponse(quoteId, "DRAFT", null, List.of(), List.of(), List.of(), false, List.of(), UUID.randomUUID(), List.of()));

    var response = service.createFromChannelMessage(messageId, request("bot-1", customerId, false, "BOT"));

    assertThat(response.quoteId()).isEqualTo(quoteId);
    verify(quoteDraftService).createFromRfq(argThat(command -> !"APPROVED".equals(command.actorRole())));
  }

  private ChannelToQuoteRequest request(String key, UUID customerId, boolean dryRun, String actorType) {
    return new ChannelToQuoteRequest(key, customerId, "RFQ", null, dryRun, false, List.of(), Map.of(), UUID.randomUUID(), actorType);
  }

  private void stubAttemptSave() {
    when(attemptRepository.save(any())).thenAnswer(invocation -> {
      QuoteConversionAttempt attempt = invocation.getArgument(0);
      if (attempt.getId() == null) {
        ReflectionTestUtils.setField(attempt, "id", UUID.randomUUID());
      }
      return attempt;
    });
  }

  private ChannelMessage message(UUID id, UUID customerId, String text) {
    ChannelMessage message = new ChannelMessage(UUID.randomUUID(), "TELEGRAM", "tg-1", "chat-1", "sender", "Sender", customerId, "INBOUND", "TEXT", text, "{}", "RECEIVED", clock.instant());
    ReflectionTestUtils.setField(message, "id", id);
    return message;
  }

  private InboundDocument document(UUID id) {
    InboundDocument document = new InboundDocument(UUID.randomUUID(), "EMAIL", "RFQ", "RECEIVED", "rfq.pdf", "application/pdf", 123L, "object-key", "sha256", "buyer@example.com", "RFQ", "{}", clock.instant());
    ReflectionTestUtils.setField(document, "id", id);
    return document;
  }

  private ExtractedLineItem extractedLine(UUID extractionId, String sku) {
    return extractedLine(extractionId, UUID.randomUUID(), sku);
  }

  private ExtractedLineItem extractedLine(UUID extractionId, UUID lineId, String sku) {
    ExtractedLineItem item = new ExtractedLineItem(UUID.randomUUID(), extractionId, 1, sku, "Brake pads", "1", BigDecimal.ONE, "ea", "EA", BigDecimal.ONE, null, clock.instant());
    ReflectionTestUtils.setField(item, "id", lineId);
    return item;
  }

  private CustomerAccount customer(UUID id, String accountCode) {
    CustomerAccount customer = new CustomerAccount(UUID.randomUUID(), "ext", accountCode, "ACME LLC", "ACME", null, "ACTIVE", "USD", null, clock.instant());
    ReflectionTestUtils.setField(customer, "id", id);
    return customer;
  }

  private AuditEvent audit() {
    AuditEvent event = new AuditEvent(UUID.randomUUID(), UUID.randomUUID(), "ACTION", "ENTITY", "id", "{}", clock.instant());
    ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
    return event;
  }
}
