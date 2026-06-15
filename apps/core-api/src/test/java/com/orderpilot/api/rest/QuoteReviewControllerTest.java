package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.Stage12BDtos.QuoteValidationIssueDto;
import com.orderpilot.api.dto.Stage12CDtos.CorrectQuoteLineCommand;
import com.orderpilot.api.dto.Stage12CDtos.QuoteConversionAttemptReviewDetail;
import com.orderpilot.api.dto.Stage12CDtos.QuoteConversionAttemptReviewFilter;
import com.orderpilot.api.dto.Stage12CDtos.QuoteConversionAttemptReviewItem;
import com.orderpilot.api.dto.Stage12CDtos.QuoteReviewCommandResult;
import com.orderpilot.api.dto.Stage12CDtos.ResolveValidationIssueCommand;
import com.orderpilot.application.services.workspace.QuoteConversionAttemptReviewQueryService;
import com.orderpilot.application.services.workspace.QuoteReviewService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.idempotency.IdempotencyService;
import com.orderpilot.common.tenant.TenantContextFilter;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.RequestActorResolver;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(QuoteReviewController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class, RequestActorResolver.class, TenantContextFilter.class})
class QuoteReviewControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private QuoteReviewService quoteReviewService;
  @MockBean private QuoteConversionAttemptReviewQueryService conversionAttemptQueryService;
  @MockBean private IdempotencyService idempotencyService;

  private static final String TENANT_HEADER = "X-Tenant-Id";
  private static final String ACTOR_HEADER = "X-OrderPilot-Actor-Id";
  private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

  @BeforeEach
  void setUpIdempotencyWrapper() {
    lenient().when(idempotencyService.execute(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(8)).get());
  }


  @Test
  void conversionAttemptListReturnsSafePreDraftAndDraftLinkedContract() throws Exception {
    UUID preDraftAttemptId = UUID.randomUUID();
    UUID linkedAttemptId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-06-02T00:00:00Z");

    when(conversionAttemptQueryService.list(any())).thenReturn(List.of(
        new QuoteConversionAttemptReviewItem(
            preDraftAttemptId,
            "CHANNEL_MESSAGE",
            "TELEGRAM",
            false,
            "NEEDS_REVIEW",
            true,
            "CUSTOMER_UNRESOLVED",
            List.of("CUSTOMER_UNRESOLVED"),
            1,
            "UNRESOLVED",
            1,
            "CREATE",
            "OPERATOR",
            createdAt),
        new QuoteConversionAttemptReviewItem(
            linkedAttemptId,
            "INBOUND_DOCUMENT",
            "EMAIL",
            true,
            "READY_FOR_DRAFT_QUOTE",
            false,
            null,
            List.of(),
            0,
            "RESOLVED",
            2,
            "CREATE",
            "OPERATOR",
            createdAt.plusSeconds(60))));

    String response = mockMvc.perform(get("/api/v1/quote-review/conversion-attempts")
            .param("status", "NEEDS_REVIEW")
            .param("reviewRequired", "true")
            .param("reasonCode", "CUSTOMER_UNRESOLVED")
            .param("sourceChannel", "TELEGRAM")
            .param("draftQuoteLinked", "false")
            .param("createdFrom", "2026-06-01T00:00:00Z")
            .param("createdTo", "2026-06-03T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(preDraftAttemptId.toString()))
        .andExpect(jsonPath("$[0].sourceType").value("CHANNEL_MESSAGE"))
        .andExpect(jsonPath("$[0].sourceChannel").value("TELEGRAM"))
        .andExpect(jsonPath("$[0].draftQuoteLinked").value(false))
        .andExpect(jsonPath("$[0].status").value("NEEDS_REVIEW"))
        .andExpect(jsonPath("$[0].reviewRequired").value(true))
        .andExpect(jsonPath("$[0].reasonCode").value("CUSTOMER_UNRESOLVED"))
        .andExpect(jsonPath("$[0].reasonCodes[0]").value("CUSTOMER_UNRESOLVED"))
        .andExpect(jsonPath("$[0].issueCount").value(1))
        .andExpect(jsonPath("$[0].customerResolution").value("UNRESOLVED"))
        .andExpect(jsonPath("$[0].lineCount").value(1))
        .andExpect(jsonPath("$[1].id").value(linkedAttemptId.toString()))
        .andExpect(jsonPath("$[1].sourceType").value("INBOUND_DOCUMENT"))
        .andExpect(jsonPath("$[1].draftQuoteLinked").value(true))
        .andExpect(jsonPath("$[0].sourceId").doesNotExist())
        .andExpect(jsonPath("$[0].channelMessageId").doesNotExist())
        .andExpect(jsonPath("$[0].inboundDocumentId").doesNotExist())
        .andExpect(jsonPath("$[0].draftQuoteId").doesNotExist())
        .andExpect(jsonPath("$[0].triggeredBy").doesNotExist())
        .andExpect(content().string(not(containsString("rawPayload"))))
        .andExpect(content().string(not(containsString("rawText"))))
        .andExpect(content().string(not(containsString("rawDocumentText"))))
        .andExpect(content().string(not(containsString("textContent"))))
        .andExpect(content().string(not(containsString("objectStorageKey"))))
        .andExpect(content().string(not(containsString("connectorCredential"))))
        .andExpect(content().string(not(containsString("secret"))))
        .andReturn()
        .getResponse()
        .getContentAsString();

    assertThat(response).doesNotContain("metadataJson", "aiRawOutput");
    ArgumentCaptor<QuoteConversionAttemptReviewFilter> filterCaptor = ArgumentCaptor.forClass(QuoteConversionAttemptReviewFilter.class);
    verify(conversionAttemptQueryService).list(filterCaptor.capture());
    QuoteConversionAttemptReviewFilter filter = filterCaptor.getValue();
    assertThat(filter.status()).isEqualTo("NEEDS_REVIEW");
    assertThat(filter.reviewRequired()).isTrue();
    assertThat(filter.reasonCode()).isEqualTo("CUSTOMER_UNRESOLVED");
    assertThat(filter.sourceChannel()).isEqualTo("TELEGRAM");
    assertThat(filter.draftQuoteLinked()).isFalse();
    assertThat(filter.createdFrom()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
    assertThat(filter.createdTo()).isEqualTo(Instant.parse("2026-06-03T00:00:00Z"));
  }

  @Test
  void conversionAttemptDetailReturnsSanitizedMetadataAndIssuesOnly() throws Exception {
    UUID attemptId = UUID.randomUUID();
    Map<String, Object> safeMetadata = new LinkedHashMap<>();
    safeMetadata.put("lineCount", 0);
    safeMetadata.put("customerResolution", "UNRESOLVED");
    safeMetadata.put("issueCount", 1);

    when(conversionAttemptQueryService.detail(attemptId)).thenReturn(new QuoteConversionAttemptReviewDetail(
        attemptId,
        "CHANNEL_MESSAGE",
        "TELEGRAM",
        false,
        "REJECTED_VALIDATION_FAILED",
        true,
        "NO_LINE_ITEMS",
        List.of("NO_LINE_ITEMS"),
        1,
        "UNRESOLVED",
        0,
        "CREATE",
        "OPERATOR",
        Instant.parse("2026-06-02T01:00:00Z"),
        safeMetadata,
        List.of(new QuoteValidationIssueDto(
            "NO_LINE_ITEMS",
            "ERROR",
            true,
            "Source did not contain extracted or parseable line items",
            null))));

    mockMvc.perform(get("/api/v1/quote-review/conversion-attempts/" + attemptId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(attemptId.toString()))
        .andExpect(jsonPath("$.draftQuoteLinked").value(false))
        .andExpect(jsonPath("$.status").value("REJECTED_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.reasonCode").value("NO_LINE_ITEMS"))
        .andExpect(jsonPath("$.safeMetadata.lineCount").value(0))
        .andExpect(jsonPath("$.safeMetadata.customerResolution").value("UNRESOLVED"))
        .andExpect(jsonPath("$.safeMetadata.issueCount").value(1))
        .andExpect(jsonPath("$.validationIssues[0].code").value("NO_LINE_ITEMS"))
        .andExpect(jsonPath("$.validationIssues[0].blocking").value(true))
        .andExpect(jsonPath("$.sourceId").doesNotExist())
        .andExpect(jsonPath("$.channelMessageId").doesNotExist())
        .andExpect(jsonPath("$.inboundDocumentId").doesNotExist())
        .andExpect(jsonPath("$.draftQuoteId").doesNotExist())
        .andExpect(jsonPath("$.triggeredBy").doesNotExist())
        .andExpect(content().string(not(containsString("rawPayload"))))
        .andExpect(content().string(not(containsString("rawText"))))
        .andExpect(content().string(not(containsString("rawDocumentText"))))
        .andExpect(content().string(not(containsString("textContent"))))
        .andExpect(content().string(not(containsString("objectStorageKey"))))
        .andExpect(content().string(not(containsString("connectorCredential"))))
        .andExpect(content().string(not(containsString("secret"))))
        .andExpect(content().string(not(containsString("metadataJson"))))
        .andExpect(content().string(not(containsString("aiRawOutput"))));
  }

  @Test
  void conversionAttemptDetailReturnsStructuredNotFound() throws Exception {
    UUID attemptId = UUID.randomUUID();
    when(conversionAttemptQueryService.detail(attemptId)).thenThrow(new NotFoundException("Quote conversion attempt not found: " + attemptId));

    mockMvc.perform(get("/api/v1/quote-review/conversion-attempts/" + attemptId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Quote conversion attempt not found: " + attemptId))
        .andExpect(jsonPath("$.path").value("/api/v1/quote-review/conversion-attempts/" + attemptId));
  }

  @Test
  void resolveIssueUsesTrustedActorAndIgnoresClientAuthorityFields() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID spoofActor = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    UUID issueId = UUID.randomUUID();
    when(quoteReviewService.resolveIssue(any(), any(), any()))
        .thenReturn(new QuoteReviewCommandResult(quoteId, "NEEDS_REVIEW", "NEEDS_REVIEW", "VALIDATION_ISSUE_RESOLVED", List.of(), List.of(), false, "openIssues=0"));

    mockMvc.perform(post("/api/v1/quote-review/{quoteId}/issues/{issueId}/resolve", quoteId, issueId)
            .header(TENANT_HEADER, tenant.toString())
            .header(ACTOR_HEADER, trustedActor.toString())
            .header(IDEMPOTENCY_HEADER, "resolve-issue-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "tenantId": "00000000-0000-4000-8000-000000000001",
                  "actorId": "%s",
                  "actorRole": "TENANT_ADMIN",
                  "status": "APPROVED",
                  "approvalStatus": "APPROVED",
                  "marginPercent": 99,
                  "validationPassed": true,
                  "reasonCode": "OPERATOR_CONFIRMED",
                  "note": "resolved by operator"
                }
                """.formatted(spoofActor)))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("00000000-0000-4000-8000-000000000001"))))
        .andExpect(content().string(not(containsString(spoofActor.toString()))));

    ArgumentCaptor<ResolveValidationIssueCommand> commandCaptor = ArgumentCaptor.forClass(ResolveValidationIssueCommand.class);
    verify(quoteReviewService).resolveIssue(any(), any(), commandCaptor.capture());
    ResolveValidationIssueCommand command = commandCaptor.getValue();
    assertThat(command.tenantId()).isNull();
    assertThat(command.actorId()).isEqualTo(trustedActor);
    assertThat(command.actorRole()).isNull();
    assertThat(command.reasonCode()).isEqualTo("OPERATOR_CONFIRMED");
    assertThat(command.note()).isEqualTo("resolved by operator");
  }

  @Test
  void lineCorrectionPreservesBusinessInputButIgnoresClientStateAndCalculatedTruth() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID trustedActor = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    when(quoteReviewService.correctLine(any(), any(), any()))
        .thenReturn(new QuoteReviewCommandResult(quoteId, "NEEDS_REVIEW", "NEEDS_REVIEW", "QUOTE_LINE_CORRECTED", List.of(), List.of("PRICE_NOT_RESOLVED"), false, "openIssues=1"));

    mockMvc.perform(post("/api/v1/quote-review/{quoteId}/lines/{lineId}/correct", quoteId, lineId)
            .header(TENANT_HEADER, tenant.toString())
            .header(ACTOR_HEADER, trustedActor.toString())
            .header(IDEMPOTENCY_HEADER, "line-correction-key")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "tenantId": "00000000-0000-4000-8000-000000000002",
                  "actorId": "00000000-0000-4000-8000-000000000003",
                  "actorRole": "SUPERUSER",
                  "quantity": 2,
                  "uom": "EA",
                  "productId": "%s",
                  "status": "APPROVED",
                  "riskLevel": "LOW",
                  "availableStock": 99999,
                  "finalPrice": 0,
                  "discountApproved": true,
                  "reasonCode": "LINE_CORRECTED",
                  "note": "operator corrected visible line data"
                }
                """.formatted(productId)))
        .andExpect(status().isOk());

    ArgumentCaptor<CorrectQuoteLineCommand> commandCaptor = ArgumentCaptor.forClass(CorrectQuoteLineCommand.class);
    verify(quoteReviewService).correctLine(any(), any(), commandCaptor.capture());
    CorrectQuoteLineCommand command = commandCaptor.getValue();
    assertThat(command.tenantId()).isNull();
    assertThat(command.actorId()).isEqualTo(trustedActor);
    assertThat(command.actorRole()).isNull();
    assertThat(command.quantity()).isEqualByComparingTo(new BigDecimal("2"));
    assertThat(command.uom()).isEqualTo("EA");
    assertThat(command.productId()).isEqualTo(productId);
    assertThat(command.reasonCode()).isEqualTo("LINE_CORRECTED");
    assertThat(command.note()).isEqualTo("operator corrected visible line data");
  }
}
