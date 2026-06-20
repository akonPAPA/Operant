package com.orderpilot.application.services.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.AiResultIntakeDtos.AiProcessingResultIntakeRequest;
import com.orderpilot.api.dto.AiResultIntakeDtos.AiProcessingResultIntakeResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.extraction.ExtractionRunRepository;
import com.orderpilot.domain.integration.ConnectorCommandRepository;
import com.orderpilot.domain.integration.IntegrationConnectionRepository;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Stage 39C hostile AI-result fixtures: Core API must accept only advisory extraction data and must
 * reject hostile/malformed/replayed results without creating business or connector state.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    AiWorkerResultIntakeService.class,
    AuditEventService.class,
    JsonSupport.class,
    CoreConfiguration.class,
    AiWorkerHostileFixtureStage39CTest.JacksonTestConfig.class
})
class AiWorkerHostileFixtureStage39CTest {
  private static final Instant T0 = Instant.parse("2026-06-20T00:00:00Z");

  @Autowired private AiWorkerResultIntakeService service;
  @Autowired private ProcessingJobRepository jobs;
  @Autowired private ExtractionRunRepository runs;
  @Autowired private ExtractionResultRepository results;
  @Autowired private AuditEventRepository audits;
  @Autowired private DraftQuoteRepository draftQuotes;
  @Autowired private DraftOrderRepository draftOrders;
  @Autowired private CustomerAccountRepository customers;
  @Autowired private ProductRepository products;
  @Autowired private PriceRuleRepository priceRules;
  @Autowired private InventorySnapshotRepository inventory;
  @Autowired private ConnectorCommandRepository connectorCommands;
  @Autowired private IntegrationConnectionRepository integrationConnections;

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
  void normalRfqResultIsAcceptedAsAdvisoryOnlyAndCreatesNoBusinessState() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, sourceId);
    BusinessCounts before = counts(tenantId);

    AiProcessingResultIntakeResponse response = service.intake(
        request(tenantId, job.getId(), sourceId, "SUCCEEDED", validExtraction()));

    assertThat(response.advisoryOnly()).isTrue();
    assertThat(response.duplicate()).isFalse();
    assertThat(results.findByIdAndTenantId(response.extractionResultId(), tenantId).orElseThrow().getResultJson())
        .contains("\"advisoryOnly\":true")
        .contains("\"untrustedUntilValidation\":true")
        .doesNotContain("createQuote")
        .doesNotContain("writeCommand");
    assertThat(counts(tenantId)).isEqualTo(before);
  }

  @Test
  void unsafeModelOutputIsRejectedSafelyAndReplayCreatesNoEffect() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, sourceId);
    BusinessCounts before = counts(tenantId);
    String rawHostile = "RAW_MODEL_OUTPUT:createQuote + writeCommand + tenant override";
    Map<String, Object> hostile = Map.of(
        "detected_intent", "RFQ",
        "document_type", "message",
        "overall_confidence", 0.9,
        "advisory_only", true,
        "operator_summary", rawHostile,
        "suggestions", List.of(Map.of(
            "toolCall", Map.of("name", "createQuote"),
            "writeCommand", Map.of("connector", "1C", "erp", "execute"))));

    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(() -> service.intake(request(tenantId, job.getId(), sourceId, "SUCCEEDED", hostile)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("forbidden_connector_command")
          .hasMessageNotContaining(rawHostile)
          .hasMessageNotContaining("createQuote");
    }

    assertThat(runs.count()).isZero();
    assertThat(results.count()).isZero();
    assertThat(counts(tenantId)).isEqualTo(before);
    assertThat(audits.findAll())
        .allSatisfy(event -> assertThat(event.getMetadata()).doesNotContain(rawHostile).doesNotContain("createQuote"));
  }

  @Test
  void authorityOverrideOutputIsRejectedWithoutTenantActorStatusOrApprovalTrust() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, sourceId);
    Map<String, Object> hostile = Map.of(
        "detected_intent", "RFQ",
        "document_type", "message",
        "overall_confidence", 0.8,
        "advisory_only", true,
        "customer", Map.of("tenantId", UUID.randomUUID().toString(), "actorId", "root"),
        "approval", Map.of("status", "APPROVED", "execution", "RUN"));

    assertThatThrownBy(() -> service.intake(request(tenantId, job.getId(), sourceId, "SUCCEEDED", hostile)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden_authority_key")
        .hasMessageNotContaining("APPROVED");

    assertThat(runs.count()).isZero();
    assertThat(results.count()).isZero();
  }

  @Test
  void malformedAndOversizedModelOutputsFailClosed() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceA = UUID.randomUUID();
    UUID sourceB = UUID.randomUUID();
    ProcessingJob malformedJob = job(tenantId, sourceA);
    ProcessingJob oversizedJob = job(tenantId, sourceB);

    assertThatThrownBy(() -> service.intake(request(tenantId, malformedJob.getId(), sourceA, "SUCCEEDED",
        Map.of("document_type", "message", "overall_confidence", 0.7, "advisory_only", true))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("malformed_extraction_result");

    assertThatThrownBy(() -> service.intake(new AiProcessingResultIntakeRequest(
        oversizedJob.getId(), tenantId.toString(), "CHANNEL_MESSAGE", sourceB, "FAILED", Map.of(),
        List.of("x".repeat(AiWorkerResultIntakeService.MAX_ENTRY_CHARS + 1)),
        List.of(), List.of(), providerMetadata(), "op-cap-07c.v1", T0, T0.plusMillis(10), 10L,
        "provider_error")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("list_entry_too_large");

    assertThat(runs.count()).isZero();
    assertThat(results.count()).isZero();
  }

  @Test
  void conflictingTerminalReplayIsRejectedAndDoesNotDuplicateEffect() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, sourceId);
    BusinessCounts before = counts(tenantId);

    AiProcessingResultIntakeResponse accepted =
        service.intake(request(tenantId, job.getId(), sourceId, "SUCCEEDED", validExtraction()));
    AiProcessingResultIntakeResponse duplicate =
        service.intake(request(tenantId, job.getId(), sourceId, "SUCCEEDED", validExtraction()));

    assertThat(duplicate.duplicate()).isTrue();
    assertThat(duplicate.extractionResultId()).isEqualTo(accepted.extractionResultId());
    assertThatThrownBy(() -> service.intake(request(tenantId, job.getId(), sourceId, "FAILED", Map.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("conflicting_terminal_result");

    assertThat(runs.count()).isEqualTo(1);
    assertThat(results.count()).isEqualTo(1);
    assertThat(counts(tenantId)).isEqualTo(before);
  }

  private ProcessingJob job(UUID tenantId, UUID sourceId) {
    return jobs.save(new ProcessingJob(tenantId, "MESSAGE_PROCESSING", "CHANNEL_MESSAGE", sourceId, 100, T0));
  }

  private AiProcessingResultIntakeRequest request(
      UUID tenantId, UUID jobId, UUID sourceId, String status, Map<String, Object> extraction) {
    return new AiProcessingResultIntakeRequest(
        jobId, tenantId.toString(), "CHANNEL_MESSAGE", sourceId, status, extraction,
        List.of(), List.of(), List.of(), providerMetadata(), "op-cap-07c.v1",
        T0, T0.plusMillis(10), 10L, null);
  }

  private static Map<String, Object> providerMetadata() {
    return Map.of("provider_name", "stage39c-fixture-provider", "mode", "RULE_BASED");
  }

  private static Map<String, Object> validExtraction() {
    return Map.of(
        "detected_intent", "RFQ",
        "document_type", "message",
        "overall_confidence", 0.86,
        "advisory_only", true,
        "line_items", List.of(Map.of(
            "line_number", 1,
            "raw_sku", "PAD-OE-04465",
            "raw_description", "brake pads Toyota Camry 2018",
            "raw_quantity", "20",
            "raw_uom", "pcs",
            "confidence", 0.82)));
  }

  private BusinessCounts counts(UUID tenantId) {
    return new BusinessCounts(
        draftQuotes.countByTenantId(tenantId),
        draftOrders.countByTenantId(tenantId),
        customers.count(),
        products.count(),
        priceRules.count(),
        inventory.findTop50ByTenantIdOrderByCapturedAtDesc(tenantId).size(),
        connectorCommands.findByTenantIdOrderByCreatedAtDesc(tenantId).size(),
        integrationConnections.findByTenantIdOrderByCreatedAtDesc(tenantId).size());
  }

  private record BusinessCounts(
      long draftQuotes,
      long draftOrders,
      long customers,
      long products,
      long priceRules,
      long inventorySnapshots,
      long connectorCommands,
      long integrationConnections) {}
}
