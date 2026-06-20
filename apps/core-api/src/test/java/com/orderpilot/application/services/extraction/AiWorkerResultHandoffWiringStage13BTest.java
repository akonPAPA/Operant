package com.orderpilot.application.services.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.AdvisoryValidationHandoffDtos.AdvisoryValidationHandoffResult;
import com.orderpilot.api.dto.AiResultIntakeDtos.AiProcessingResultIntakeRequest;
import com.orderpilot.api.dto.AiResultIntakeDtos.AiProcessingResultIntakeResponse;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.application.services.validation.AdvisoryExtractionValidationHandoffService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractedLineItemRepository;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import com.orderpilot.domain.validation.ValidationRunRepository;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-13B — auto-trigger wiring tests: an AI-worker advisory result intake now flows into the
 * deterministic validation handoff after commit. Proves SUCCEEDED/NEEDS_REVIEW trigger decomposition +
 * a validation run, FAILED/REJECTED and unsafe nested payloads fail closed with no rows/run, duplicate
 * intake and manual re-trigger stay idempotent, tenant isolation holds, and no business records are
 * created. Uses {@code @SpringBootTest} (non-transactional) so the after-commit trigger actually fires.
 */
@SpringBootTest
@ActiveProfiles("test")
class AiWorkerResultHandoffWiringStage13BTest {
  private static final Instant T0 = Instant.parse("2026-06-09T00:00:00Z");

  @Autowired private AiWorkerResultIntakeService intakeService;
  @Autowired private AdvisoryExtractionValidationHandoffService handoffService;
  @Autowired private ProcessingJobRepository jobs;
  @Autowired private ExtractionResultRepository extractionResults;
  @Autowired private ExtractedLineItemRepository lines;
  @Autowired private ValidationRunRepository validationRuns;
  @Autowired private DraftQuoteRepository draftQuotes;
  @Autowired private DraftOrderRepository draftOrders;
  @Autowired private JsonSupport json;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void succeededIntakeAutoTriggersHandoffCreatingRowsAndRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "CHANNEL_MESSAGE", sourceId);
    long quotesBefore = draftQuotes.count();
    long ordersBefore = draftOrders.count();

    AiProcessingResultIntakeResponse response = intakeService.intake(
        request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED",
            extraction(0.9, "Filter"), null));

    UUID resultId = response.extractionResultId();
    assertThat(lines.findByTenantIdAndExtractionResultId(tenantId, resultId)).hasSize(1);
    assertThat(validationRuns.findByTenantIdAndExtractionResultIdOrderByCreatedAtDesc(tenantId, resultId)).hasSize(1);
    // The handoff created only advisory rows + a validation run — never business records.
    assertThat(draftQuotes.count()).isEqualTo(quotesBefore);
    assertThat(draftOrders.count()).isEqualTo(ordersBefore);
  }

  @Test
  void needsReviewIntakeTriggersHandoffWhenPayloadValid() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "CHANNEL_MESSAGE", sourceId);

    AiProcessingResultIntakeResponse response = intakeService.intake(
        request(job.getId(), "CHANNEL_MESSAGE", sourceId, "NEEDS_REVIEW",
            extraction(0.4, "Filter"), null));

    UUID resultId = response.extractionResultId();
    assertThat(lines.findByTenantIdAndExtractionResultId(tenantId, resultId)).hasSize(1);
    assertThat(validationRuns.findByTenantIdAndExtractionResultIdOrderByCreatedAtDesc(tenantId, resultId)).hasSize(1);
  }

  @Test
  void failedIntakeDoesNotDecomposeOrValidate() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "INBOUND_DOCUMENT", sourceId);

    AiProcessingResultIntakeResponse response = intakeService.intake(
        request(job.getId(), "INBOUND_DOCUMENT", sourceId, "FAILED", Map.of(), "provider_error"));

    UUID resultId = response.extractionResultId();
    assertThat(lines.findByTenantIdAndExtractionResultId(tenantId, resultId)).isEmpty();
    assertThat(validationRuns.findByTenantIdAndExtractionResultIdOrderByCreatedAtDesc(tenantId, resultId)).isEmpty();
  }

  @Test
  void rejectedIntakeDoesNotDecomposeOrValidate() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "PDF_TEXT", sourceId);

    AiProcessingResultIntakeResponse response = intakeService.intake(
        request(job.getId(), "PDF_TEXT", sourceId, "REJECTED", Map.of(), "raw_text_too_large"));

    UUID resultId = response.extractionResultId();
    assertThat(lines.findByTenantIdAndExtractionResultId(tenantId, resultId)).isEmpty();
    assertThat(validationRuns.findByTenantIdAndExtractionResultIdOrderByCreatedAtDesc(tenantId, resultId)).isEmpty();
  }

  @Test
  void unsafeNestedActionKeyFailsClosedThroughWiring() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "CHANNEL_MESSAGE", sourceId);

    // Stage 39B rejects nested business-action keys at intake, before persistence or handoff.
    Map<String, Object> line = new LinkedHashMap<>(line(1, "SKU-X", "Thing", "2", "EA", 0.9));
    line.put("create_order", Map.of("sku", "SKU-X"));
    Map<String, Object> extraction = new LinkedHashMap<>();
    extraction.put("detected_intent", "RFQ");
    extraction.put("document_type", "message");
    extraction.put("overall_confidence", 0.9);
    extraction.put("advisory_only", true);
    extraction.put("line_items", List.of(line));

    assertThatThrownBy(() -> intakeService.intake(
        request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED", extraction, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("forbidden_action_key");

    assertThat(extractionResults.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
  }

  @Test
  void duplicateIntakeDoesNotDuplicateValidationArtifacts() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "CHANNEL_MESSAGE", sourceId);

    AiProcessingResultIntakeResponse first = intakeService.intake(
        request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED", extraction(0.9, "Filter"), null));
    AiProcessingResultIntakeResponse second = intakeService.intake(
        request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED", extraction(0.9, "Filter"), null));

    assertThat(second.duplicate()).isTrue();
    assertThat(second.extractionResultId()).isEqualTo(first.extractionResultId());
    UUID resultId = first.extractionResultId();
    assertThat(lines.findByTenantIdAndExtractionResultId(tenantId, resultId)).hasSize(1);
    assertThat(validationRuns.findByTenantIdAndExtractionResultIdOrderByCreatedAtDesc(tenantId, resultId)).hasSize(1);
  }

  @Test
  void manualHandoffReTriggerIsIdempotentAndBounded() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantId, "CHANNEL_MESSAGE", sourceId);
    String sentinel = "SENTINEL-RAW-PAYLOAD-ZZZ";

    AiProcessingResultIntakeResponse response = intakeService.intake(
        request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED", extraction(0.9, sentinel), null));
    UUID resultId = response.extractionResultId();

    // Manual operator re-trigger (what the guarded endpoint calls) is idempotent.
    AdvisoryValidationHandoffResult manual = handoffService.handoff(resultId);
    assertThat(manual.duplicate()).isTrue();
    assertThat(manual.advisoryOnly()).isTrue();
    assertThat(validationRuns.findByTenantIdAndExtractionResultIdOrderByCreatedAtDesc(tenantId, resultId)).hasSize(1);
    // The bounded response never carries the raw advisory payload text.
    assertThat(json.writeObject(manual)).doesNotContain(sentinel);
  }

  @Test
  void foreignTenantCannotTriggerHandoff() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    UUID sourceId = UUID.randomUUID();
    ProcessingJob job = job(tenantA, "CHANNEL_MESSAGE", sourceId);
    AiProcessingResultIntakeResponse response = intakeService.intake(
        request(job.getId(), "CHANNEL_MESSAGE", sourceId, "SUCCEEDED", extraction(0.9, "Filter"), null));
    UUID resultId = response.extractionResultId();

    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> handoffService.handoff(resultId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("extraction_result_not_found");
  }

  // --- helpers -----------------------------------------------------------------------------------

  private ProcessingJob job(UUID tenantId, String targetType, UUID targetId) {
    return jobs.save(new ProcessingJob(tenantId, "MESSAGE_PROCESSING", targetType, targetId, 100, T0));
  }

  private static Map<String, Object> extraction(double overall, String description) {
    Map<String, Object> field = new LinkedHashMap<>();
    field.put("field_name", "customer_hint");
    field.put("raw_value", "Acme");
    field.put("normalized_value", "Acme");
    field.put("value_type", "customer_hint");
    field.put("confidence", 0.9);
    Map<String, Object> extraction = new LinkedHashMap<>();
    extraction.put("detected_intent", "RFQ");
    extraction.put("document_type", "message");
    extraction.put("overall_confidence", overall);
    extraction.put("advisory_only", true);
    extraction.put("fields", List.of(field));
    extraction.put("line_items", List.of(line(1, "SKU-AUTO", description, "2", "EA", overall)));
    return extraction;
  }

  private static Map<String, Object> line(int number, String sku, String description, String quantity, String uom, double confidence) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("line_number", number);
    line.put("raw_sku", sku);
    line.put("raw_description", description);
    line.put("raw_quantity", quantity);
    line.put("raw_uom", uom);
    line.put("confidence", confidence);
    return line;
  }

  private static AiProcessingResultIntakeRequest request(
      UUID jobId, String sourceType, UUID sourceId, String status, Map<String, Object> extraction, String safeFailureReason) {
    return new AiProcessingResultIntakeRequest(
        jobId, TenantContext.requireTenantId().toString(), sourceType, sourceId, status, extraction,
        List.of(), List.of(), List.of(),
        Map.of("provider_name", "rule-based-understanding", "mode", "RULE_BASED"),
        "op-cap-07c.v1", T0, T0.plusMillis(10), 10L, safeFailureReason);
  }
}
