package com.orderpilot.application.services.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.Stage4Dtos.ExtractionRunRequest;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractedFieldRepository;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.intake.ChannelMessage;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.application.services.runtime.FeatureEntitlementGuard;
import com.orderpilot.application.services.runtime.QuotaGuard;
import com.orderpilot.application.services.runtime.RateLimitService;
import com.orderpilot.application.services.runtime.RuntimeGuardService;
import com.orderpilot.application.services.runtime.UsageMeterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    ExtractionPipelineService.class,
    ExtractionRunService.class,
    TextExtractionService.class,
    SemanticExtractionService.class,
    ConfidenceScoringService.class,
    ExtractionOutputSanitizer.class,
    RuleBasedMockSemanticExtractionProvider.class,
    MessageTextExtractionProvider.class,
    MockDocumentTextExtractionProvider.class,
    PromptInjectionGuardService.class,
    AuditEventService.class,
    JsonSupport.class,
    CoreConfiguration.class,
    // OP-CAP-16D: ExtractionPipelineService now depends on the runtime guard chain.
  com.orderpilot.application.services.runtime.RuntimeControlService.class,
  com.orderpilot.application.services.runtime.AiWorkloadClassifier.class,
    RuntimeGuardService.class,
    QuotaGuard.class,
    RateLimitService.class,
    FeatureEntitlementGuard.class,
    UsageMeterService.class,
    AiUnderstandingPipelineServiceTest.JacksonTestConfig.class
})
class AiUnderstandingPipelineServiceTest {
  @Autowired private ExtractionPipelineService pipelineService;
  @Autowired private ChannelMessageRepository messageRepository;
  @Autowired private ExtractionResultRepository resultRepository;
  @Autowired private ExtractedFieldRepository fieldRepository;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private DraftOrderRepository draftOrderRepository;

  @TestConfiguration
  static class JacksonTestConfig {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void mockMessageExtractionStoresStructuredAdvisoryFields() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage message = messageRepository.save(new ChannelMessage(tenantId, "EMAIL", "msg-1", "thread-1", "buyer@example.test", "Buyer", null, "INBOUND", "TEXT", "Customer: Acme\nNeed 10 EA SKU-001 ship to Almaty by 2026-06-01", "{}", "QUEUED", Instant.parse("2026-05-24T00:00:00Z")));

    var run = pipelineService.runNow(new ExtractionRunRequest("CHANNEL_MESSAGE", message.getId(), null, "RULE_BASED"));
    var result = resultRepository.findFirstByTenantIdAndExtractionRunId(tenantId, run.getId()).orElseThrow();

    assertThat(result.getDetectedIntent()).isEqualTo("RFQ");
    assertThat(result.getResultJson()).contains("\"advisoryOnly\":true");
    assertThat(fieldRepository.findByTenantIdAndExtractionResultId(tenantId, result.getId()))
        .extracting("fieldName")
        .contains("customer_hint", "product_sku_hint", "product_description", "quantity", "uom", "requested_date", "delivery_location_hint", "raw_line_items");
  }

  @Test
  void lowConfidenceRoutesExtractionRunToNeedsReview() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChannelMessage message = messageRepository.save(new ChannelMessage(tenantId, "EMAIL", "msg-2", "thread-2", "buyer@example.test", "Buyer", null, "INBOUND", "TEXT", "Hello there", "{}", "QUEUED", Instant.parse("2026-05-24T00:00:00Z")));

    var run = pipelineService.runNow(new ExtractionRunRequest("CHANNEL_MESSAGE", message.getId(), null, "RULE_BASED"));
    var result = resultRepository.findFirstByTenantIdAndExtractionRunId(tenantId, run.getId()).orElseThrow();

    assertThat(run.getStatus()).isEqualTo("NEEDS_REVIEW");
    assertThat(result.getValidationStatus()).isEqualTo("NEEDS_REVIEW");
  }

  @Test
  void aiExtractionDoesNotCreateQuoteOrOrderDirectly() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    long quotesBefore = draftQuoteRepository.count();
    long ordersBefore = draftOrderRepository.count();
    ChannelMessage message = messageRepository.save(new ChannelMessage(tenantId, "EMAIL", "msg-3", "thread-3", "buyer@example.test", "Buyer", null, "INBOUND", "TEXT", "Purchase order PO 123 for 2 EA ABC-123", "{}", "QUEUED", Instant.parse("2026-05-24T00:00:00Z")));

    pipelineService.runNow(new ExtractionRunRequest("CHANNEL_MESSAGE", message.getId(), null, "RULE_BASED"));

    assertThat(draftQuoteRepository.count()).isEqualTo(quotesBefore);
    assertThat(draftOrderRepository.count()).isEqualTo(ordersBefore);
  }
}
