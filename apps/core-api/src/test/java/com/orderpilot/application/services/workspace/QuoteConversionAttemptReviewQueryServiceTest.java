package com.orderpilot.application.services.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.Stage12CDtos.QuoteConversionAttemptReviewFilter;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.intake.InboundDocument;
import com.orderpilot.domain.intake.InboundDocumentRepository;
import com.orderpilot.domain.workspace.QuoteConversionAttempt;
import com.orderpilot.domain.workspace.QuoteConversionAttemptRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuoteConversionAttemptReviewQueryService.class)
class QuoteConversionAttemptReviewQueryServiceTest {
  private static final Instant NOW = Instant.parse("2026-06-02T00:00:00Z");

  @Autowired private QuoteConversionAttemptReviewQueryService service;
  @Autowired private QuoteConversionAttemptRepository attemptRepository;
  @Autowired private ChannelMessageRepository channelMessageRepository;
  @Autowired private InboundDocumentRepository inboundDocumentRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void listReturnsOnlyCurrentTenantAttempts() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    ChannelMessage messageA = channelMessageRepository.save(message(tenantA, "TELEGRAM"));
    ChannelMessage messageB = channelMessageRepository.save(message(tenantB, "EMAIL"));
    QuoteConversionAttempt attemptA = rejectedAttempt(tenantA, "CHANNEL_MESSAGE", messageA.getId(), "CUSTOMER_UNRESOLVED");
    rejectedAttempt(tenantB, "CHANNEL_MESSAGE", messageB.getId(), "CUSTOMER_UNRESOLVED");

    TenantContext.setTenantId(tenantA);

    var items = service.list(new QuoteConversionAttemptReviewFilter(null, null, null, null, null, null, null));

    assertThat(items).hasSize(1);
    assertThat(items.get(0).id()).isEqualTo(attemptA.getId());
    assertThat(items.get(0).sourceChannel()).isEqualTo("TELEGRAM");
  }

  @Test
  void preDraftFailureAppearsWithNullDraftQuoteAndSafeReasonMetadata() {
    UUID tenantId = UUID.randomUUID();
    ChannelMessage message = channelMessageRepository.save(message(tenantId, "TELEGRAM"));
    QuoteConversionAttempt attempt = rejectedAttempt(tenantId, "CHANNEL_MESSAGE", message.getId(), "CUSTOMER_UNRESOLVED");
    TenantContext.setTenantId(tenantId);

    var item = service.list(new QuoteConversionAttemptReviewFilter(null, true, "CUSTOMER_UNRESOLVED", "TELEGRAM", false, null, null)).getFirst();
    var detail = service.detail(attempt.getId());

    assertThat(item.draftQuoteLinked()).isFalse();
    assertThat(item.reviewRequired()).isTrue();
    assertThat(item.reasonCode()).isEqualTo("CUSTOMER_UNRESOLVED");
    assertThat(item.reasonCodes()).containsExactly("CUSTOMER_UNRESOLVED");
    assertThat(item.issueCount()).isEqualTo(1);
    assertThat(detail.validationIssues()).hasSize(1);
    assertThat(detail.safeMetadata()).containsEntry("customerResolution", "UNRESOLVED").containsEntry("lineCount", 1);
  }

  @Test
  void draftLinkedAttemptAppearsWithDraftQuoteIdAndSourceDocumentChannel() {
    UUID tenantId = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    InboundDocument document = inboundDocumentRepository.save(document(tenantId, "EMAIL"));
    QuoteConversionAttempt attempt = new QuoteConversionAttempt(tenantId, "INBOUND_DOCUMENT", document.getId(), "READY_FOR_DRAFT_QUOTE", summaryJson(List.of(), "RESOLVED", 2), UUID.randomUUID(), "USER", "linked", "CREATE", NOW);
    attempt.markDraftCreated(quoteId, "READY_FOR_DRAFT_QUOTE", summaryJson(List.of(), "RESOLVED", 2));
    attempt = attemptRepository.save(attempt);
    TenantContext.setTenantId(tenantId);

    var item = service.list(new QuoteConversionAttemptReviewFilter(null, false, null, "EMAIL", true, null, null)).getFirst();
    var detail = service.detail(attempt.getId());

    assertThat(item.draftQuoteLinked()).isTrue();
    assertThat(item.reviewRequired()).isFalse();
    assertThat(item.reasonCodes()).isEmpty();
    assertThat(detail.sourceChannel()).isEqualTo("EMAIL");
  }

  @Test
  void reasonCodeFilterReturnsMatchingAttemptOnly() {
    UUID tenantId = UUID.randomUUID();
    ChannelMessage first = channelMessageRepository.save(message(tenantId, "TELEGRAM"));
    ChannelMessage second = channelMessageRepository.save(message(tenantId, "TELEGRAM"));
    QuoteConversionAttempt noLines = rejectedAttempt(tenantId, "CHANNEL_MESSAGE", first.getId(), "NO_LINE_ITEMS");
    rejectedAttempt(tenantId, "CHANNEL_MESSAGE", second.getId(), "CUSTOMER_UNRESOLVED");
    TenantContext.setTenantId(tenantId);

    var items = service.list(new QuoteConversionAttemptReviewFilter(null, true, "NO_LINE_ITEMS", null, null, null, null));

    assertThat(items).extracting("id").containsExactly(noLines.getId());
  }

  @Test
  void detailForForeignTenantAttemptReturnsNotFound() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    ChannelMessage messageB = channelMessageRepository.save(message(tenantB, "TELEGRAM"));
    QuoteConversionAttempt attemptB = rejectedAttempt(tenantB, "CHANNEL_MESSAGE", messageB.getId(), "NO_LINE_ITEMS");
    TenantContext.setTenantId(tenantA);

    assertThatThrownBy(() -> service.detail(attemptB.getId()))
        .isInstanceOf(NotFoundException.class);
  }

  private QuoteConversionAttempt rejectedAttempt(UUID tenantId, String sourceType, UUID sourceId, String reasonCode) {
    QuoteConversionAttempt attempt = new QuoteConversionAttempt(tenantId, sourceType, sourceId, "NEEDS_REVIEW", summaryJson(List.of(reasonCode), "UNRESOLVED", 1), UUID.randomUUID(), "USER", reasonCode.toLowerCase(), "CREATE", NOW);
    attempt.markTerminal("NEEDS_REVIEW", reasonCode, "NEEDS_REVIEW", summaryJson(List.of(reasonCode), "UNRESOLVED", 1));
    return attemptRepository.save(attempt);
  }

  private ChannelMessage message(UUID tenantId, String channel) {
    return new ChannelMessage(tenantId, channel, UUID.randomUUID().toString(), "chat", "sender", "Sender", null, "INBOUND", "TEXT", "Need BRK-001 2 ea", "{}", "RECEIVED", NOW);
  }

  private InboundDocument document(UUID tenantId, String channel) {
    return new InboundDocument(tenantId, channel, "RFQ", "RECEIVED", "rfq.pdf", "application/pdf", 123L, "object-key", UUID.randomUUID().toString(), "buyer@example.com", "RFQ", "{}", NOW);
  }

  private String summaryJson(List<String> issueCodes, String customerResolution, int lineCount) {
    String issues = issueCodes.stream()
        .map(code -> "{\"code\":\"" + code + "\",\"severity\":\"ERROR\",\"blocking\":true,\"message\":\"" + code + "\",\"lineId\":null}")
        .reduce((left, right) -> left + "," + right)
        .orElse("");
    return "{\"lineCount\":" + lineCount + ",\"customerResolution\":\"" + customerResolution + "\",\"issues\":[" + issues + "]}";
  }
}
