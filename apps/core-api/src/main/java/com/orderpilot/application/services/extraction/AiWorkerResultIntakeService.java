package com.orderpilot.application.services.extraction;

import com.orderpilot.api.dto.AiResultIntakeDtos.AiProcessingResultIntakeRequest;
import com.orderpilot.api.dto.AiResultIntakeDtos.AiProcessingResultIntakeResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractionResult;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.extraction.ExtractionRun;
import com.orderpilot.domain.extraction.ExtractionRunRepository;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-07D — secure receiving layer for AI-worker (OP-CAP-07C) processing results.
 *
 * <p>Trust boundary: AI output is <em>untrusted advisory data</em>. This service validates the
 * envelope fail-closed, correlates it against trusted Core API state (tenant resolved server-side;
 * processing job + source matched), then persists the result as an advisory {@code ExtractionRun} +
 * {@code ExtractionResult} (provider type {@code AI_WORKER}) and moves only the processing job's own
 * status. It never creates or mutates quotes, orders, inventory, customers, prices, products,
 * connectors or ERP/1C state, and the persisted result is explicitly marked advisory / untrusted
 * until deterministic validation. Real validation/risk/quote-order work is a later layer.
 */
@Service
public class AiWorkerResultIntakeService {
  static final String PROVIDER_TYPE = "AI_WORKER";

  // Bounds — fail closed above these. Snippets/inputs are already bounded by the worker; these are
  // the Core-side outer gates so an oversized or noisy result is rejected rather than persisted.
  static final int MAX_PAYLOAD_CHARS = 200_000;
  static final int MAX_LIST_ENTRIES = 200;
  static final int MAX_ENTRY_CHARS = 2_000;

  static final Set<String> SUPPORTED_SCHEMA_VERSIONS = Set.of("op-cap-07c.v1");
  static final Set<String> SUPPORTED_STATUSES = Set.of("SUCCEEDED", "NEEDS_REVIEW", "FAILED", "REJECTED");

  // OP-CAP-13B: worker statuses whose persisted advisory result is structurally usable and is
  // auto-handed-off into deterministic validation. FAILED/REJECTED are intentionally excluded —
  // they never decompose into business candidates and never run deterministic validation.
  static final Set<String> HANDOFF_TRIGGER_STATUSES = Set.of("SUCCEEDED", "NEEDS_REVIEW");
  static final Set<String> SUPPORTED_SOURCE_TYPES = Set.of(
      "CHANNEL_MESSAGE", "INBOUND_DOCUMENT", "EMAIL_BODY", "PDF_TEXT", "EXCEL_TEXT", "CSV_TEXT",
      "API_UPLOAD_TEXT", "UNKNOWN");

  // Forbidden top-level command/action surfaces. AI advisory output may never carry an executable or
  // business-mutation key; presence of any of these fails the intake closed. Compared lower-cased.
  static final Set<String> FORBIDDEN_ACTION_KEYS = Set.of(
      "action", "command", "approve", "execute", "write", "mutation", "sql", "erpwrite",
      "inventoryupdate", "priceupdate", "customerupdate", "ordercreate", "quoteapprove");

  private final ProcessingJobRepository processingJobRepository;
  private final ExtractionRunRepository runRepository;
  private final ExtractionResultRepository resultRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final AuditEventService auditEventService;
  private final JsonSupport json;
  private final Clock clock;

  public AiWorkerResultIntakeService(
      ProcessingJobRepository processingJobRepository,
      ExtractionRunRepository runRepository,
      ExtractionResultRepository resultRepository,
      ApplicationEventPublisher eventPublisher,
      AuditEventService auditEventService,
      JsonSupport json,
      Clock clock) {
    this.processingJobRepository = processingJobRepository;
    this.runRepository = runRepository;
    this.resultRepository = resultRepository;
    this.eventPublisher = eventPublisher;
    this.auditEventService = auditEventService;
    this.json = json;
    this.clock = clock;
  }

  @Transactional
  public AiProcessingResultIntakeResponse intake(AiProcessingResultIntakeRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    validateEnvelope(request, tenantId);

    // Correlation against trusted Core API state. The worker's tenantRef is never the authority.
    ProcessingJob job = processingJobRepository.findByIdAndTenantId(request.jobId(), tenantId)
        .orElseThrow(() -> rejected(request, "processing_job_not_found"));
    if (!job.getTargetType().equalsIgnoreCase(request.sourceType())) {
      throw rejected(request, "source_type_mismatch");
    }
    if (!job.getTargetId().equals(request.sourceId())) {
      throw rejected(request, "source_id_mismatch");
    }

    String status = request.status().trim().toUpperCase(Locale.ROOT);
    Instant now = clock.instant();

    // Idempotency: at most one advisory AI-worker run per job. A repeat delivery is a no-op that
    // returns the already-persisted record; an older/conflicting result never overwrites it.
    var existing = runRepository.findFirstByTenantIdAndProcessingJobIdAndProviderType(
        tenantId, job.getId(), PROVIDER_TYPE);
    if (existing.isPresent()) {
      ExtractionRun run = existing.get();
      UUID resultId = resultRepository.findFirstByTenantIdAndExtractionRunId(tenantId, run.getId())
          .map(ExtractionResult::getId).orElse(null);
      auditEventService.record("ai_processing_result.intake_duplicate", "extraction_run",
          run.getId().toString(), null, auditMetadata(request, status));
      return new AiProcessingResultIntakeResponse(
          job.getId(), run.getId(), resultId, job.getStatus(), run.getStatus(), true, true, now);
    }

    // OP-CAP-29: lifecycle guard. By this point there is no surviving advisory run for the job (the
    // idempotency branch above already returns for duplicates). A fresh result is therefore accepted only
    // while the job is still active (PENDING or PROCESSING). If the job already reached a terminal state
    // through another path — e.g. the stale-PROCESSING reaper marked it FAILED — a late/runless result
    // must not resurrect or overwrite it. Fail closed, audited, no run/result created, no business write.
    if (!job.isActiveForResult()) {
      throw rejected(request, "job_not_in_processable_state");
    }

    String providerName = bounded(stringFrom(request.providerMetadata(), "provider_name", "providerName"), 200);
    String providerMode = bounded(stringFrom(request.providerMetadata(), "mode"), 100);

    ExtractionRun run = runRepository.save(new ExtractionRun(
        tenantId, request.sourceType(), request.sourceId(), job.getId(), PROVIDER_TYPE,
        providerName == null ? "ai-worker" : providerName, providerMode,
        null, request.schemaVersion(), now));
    applyRunStatus(run, status, request.safeFailureReason(), now);

    ExtractionResult result = resultRepository.save(buildAdvisoryResult(tenantId, run, request, status, now));
    applyJobStatus(job, status, request.safeFailureReason(), now);

    auditEventService.record("ai_processing_result.intake_succeeded", "extraction_run",
        run.getId().toString(), null, auditMetadata(request, status));

    // OP-CAP-13B: auto-trigger the advisory→deterministic validation handoff for structurally usable
    // results. Published as a transaction-bound event so the handoff runs only AFTER this intake
    // transaction commits (see AdvisoryValidationHandoffTrigger). That keeps the handoff failure
    // isolated — it can never roll back or corrupt the already-persisted advisory result — and lets
    // the deterministic validation run read the committed extraction row. FAILED/REJECTED are skipped
    // (no decomposition, no run); the downstream handoff also independently fails those closed.
    if (HANDOFF_TRIGGER_STATUSES.contains(status)) {
      eventPublisher.publishEvent(
          new AdvisoryValidationHandoffRequested(tenantId, result.getId(), job.getId(), status));
    }

    return new AiProcessingResultIntakeResponse(
        job.getId(), run.getId(), result.getId(), job.getStatus(), run.getStatus(), false, true, now);
  }

  private void validateEnvelope(AiProcessingResultIntakeRequest request, UUID tenantId) {
    if (request.jobId() == null) {
      throw rejected(request, "missing_job_id");
    }
    if (request.sourceId() == null) {
      throw rejected(request, "missing_source_id");
    }
    if (isBlank(request.sourceType()) || !SUPPORTED_SOURCE_TYPES.contains(request.sourceType().trim().toUpperCase(Locale.ROOT))) {
      throw rejected(request, "unsupported_source_type");
    }
    if (isBlank(request.status()) || !SUPPORTED_STATUSES.contains(request.status().trim().toUpperCase(Locale.ROOT))) {
      throw rejected(request, "unsupported_status");
    }
    if (isBlank(request.schemaVersion()) || !SUPPORTED_SCHEMA_VERSIONS.contains(request.schemaVersion().trim())) {
      throw rejected(request, "unsupported_schema_version");
    }
    // tenantRef is correlation-only, but if present it must agree with the trusted tenant.
    if (!isBlank(request.tenantRef()) && !request.tenantRef().trim().equals(tenantId.toString())) {
      throw rejected(request, "tenant_correlation_mismatch");
    }
    assertBoundedList(request.warnings(), request);
    assertBoundedList(request.errors(), request);
    assertBoundedList(request.promptInjectionSignals(), request);
    assertNoForbiddenKeys(request.extractionResult(), request);
    assertNoForbiddenKeys(request.providerMetadata(), request);
    assertPayloadBounded(request);
  }

  private void assertBoundedList(List<String> values, AiProcessingResultIntakeRequest request) {
    if (values == null) {
      return;
    }
    if (values.size() > MAX_LIST_ENTRIES) {
      throw rejected(request, "list_too_large");
    }
    for (String value : values) {
      if (value != null && value.length() > MAX_ENTRY_CHARS) {
        throw rejected(request, "list_entry_too_large");
      }
    }
  }

  private void assertNoForbiddenKeys(Map<String, Object> map, AiProcessingResultIntakeRequest request) {
    if (map == null) {
      return;
    }
    for (String key : map.keySet()) {
      if (key != null && FORBIDDEN_ACTION_KEYS.contains(key.trim().toLowerCase(Locale.ROOT))) {
        throw rejected(request, "forbidden_action_key");
      }
    }
  }

  private void assertPayloadBounded(AiProcessingResultIntakeRequest request) {
    if (request.extractionResult() == null) {
      return;
    }
    if (json.writeObject(request.extractionResult()).length() > MAX_PAYLOAD_CHARS) {
      throw rejected(request, "payload_too_large");
    }
  }

  private ExtractionResult buildAdvisoryResult(
      UUID tenantId, ExtractionRun run, AiProcessingResultIntakeRequest request, String status, Instant now) {
    Map<String, Object> extraction = request.extractionResult() == null ? Map.of() : request.extractionResult();
    String detectedIntent = bounded(orDefault(stringFrom(extraction, "detected_intent", "detectedIntent"), "unknown"), 120);
    String documentType = bounded(orDefault(stringFrom(extraction, "document_type", "documentType"), "unknown"), 120);
    BigDecimal overall = confidenceFrom(extraction);
    String validationStatus = validationStatusFor(status);

    Map<String, Object> wrapper = new LinkedHashMap<>();
    wrapper.put("advisoryOnly", true);
    wrapper.put("source", PROVIDER_TYPE);
    wrapper.put("untrustedUntilValidation", true);
    wrapper.put("workerStatus", status);
    wrapper.put("schemaVersion", request.schemaVersion());
    wrapper.put("providerMetadata", request.providerMetadata() == null ? Map.of() : request.providerMetadata());
    wrapper.put("warnings", request.warnings() == null ? List.of() : request.warnings());
    wrapper.put("errors", request.errors() == null ? List.of() : request.errors());
    wrapper.put("promptInjectionSignals", request.promptInjectionSignals() == null ? List.of() : request.promptInjectionSignals());
    wrapper.put("safeFailureReason", bounded(request.safeFailureReason(), MAX_ENTRY_CHARS));
    wrapper.put("durationMs", request.durationMs());
    wrapper.put("startedAt", request.startedAt() == null ? null : request.startedAt().toString());
    wrapper.put("completedAt", request.completedAt() == null ? null : request.completedAt().toString());
    wrapper.put("extraction", extraction);

    return new ExtractionResult(tenantId, run.getId(), request.sourceType(), request.sourceId(),
        detectedIntent, documentType, overall, json.writeObject(wrapper), validationStatus, now);
  }

  private void applyRunStatus(ExtractionRun run, String status, String safeReason, Instant now) {
    switch (status) {
      case "SUCCEEDED" -> run.markSucceeded(now);
      case "NEEDS_REVIEW" -> run.markNeedsReview(now);
      // The extraction run state machine has no REJECTED; both FAILED and REJECTED worker outcomes
      // are terminal failures for the run. The advisory result's validationStatus keeps the precise
      // distinction (FAILED vs REJECTED) for downstream review.
      default -> run.markFailed(bounded(safeReason, 500), now);
    }
  }

  private void applyJobStatus(ProcessingJob job, String status, String safeReason, Instant now) {
    switch (status) {
      case "SUCCEEDED" -> job.markSucceeded(now);
      case "NEEDS_REVIEW" -> job.markNeedsReview(now);
      case "REJECTED" -> job.markRejected(bounded(safeReason, 500), now);
      default -> job.markFailed(bounded(safeReason, 500), now);
    }
  }

  private static String validationStatusFor(String status) {
    return switch (status) {
      case "SUCCEEDED" -> "READY_FOR_VALIDATION";
      case "NEEDS_REVIEW" -> "NEEDS_REVIEW";
      case "REJECTED" -> "REJECTED";
      default -> "FAILED";
    };
  }

  private BigDecimal confidenceFrom(Map<String, Object> extraction) {
    Object value = extraction.get("overall_confidence");
    if (value == null) {
      value = extraction.get("overallConfidence");
    }
    if (value instanceof Number number) {
      double d = Math.max(0.0, Math.min(1.0, number.doubleValue()));
      return BigDecimal.valueOf(d);
    }
    return BigDecimal.ZERO;
  }

  // Bounded, non-sensitive audit metadata only. Never includes raw document/message text, the
  // extraction payload, secrets, or service tokens.
  private String auditMetadata(AiProcessingResultIntakeRequest request, String status) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("jobId", String.valueOf(request.jobId()));
    metadata.put("sourceType", String.valueOf(request.sourceType()));
    metadata.put("sourceId", String.valueOf(request.sourceId()));
    metadata.put("resultStatus", status);
    metadata.put("schemaVersion", String.valueOf(request.schemaVersion()));
    metadata.put("providerName", orDefault(stringFrom(request.providerMetadata(), "provider_name", "providerName"), ""));
    metadata.put("warningCount", request.warnings() == null ? 0 : request.warnings().size());
    metadata.put("errorCount", request.errors() == null ? 0 : request.errors().size());
    metadata.put("promptInjectionSignalCount",
        request.promptInjectionSignals() == null ? 0 : request.promptInjectionSignals().size());
    metadata.put("durationMs", request.durationMs() == null ? 0L : request.durationMs());
    metadata.put("advisoryOnly", true);
    return json.writeObject(metadata);
  }

  private IllegalArgumentException rejected(AiProcessingResultIntakeRequest request, String reason) {
    // Audit the fail-closed rejection with bounded metadata (REQUIRES_NEW commits independently of
    // the rolled-back intake transaction), then surface a safe 400 with the reason token only.
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("jobId", String.valueOf(request.jobId()));
    metadata.put("sourceType", String.valueOf(request.sourceType()));
    metadata.put("sourceId", String.valueOf(request.sourceId()));
    metadata.put("reason", reason);
    metadata.put("advisoryOnly", true);
    auditEventService.record("ai_processing_result.intake_rejected", "processing_job",
        String.valueOf(request.jobId()), null, json.writeObject(metadata));
    return new IllegalArgumentException("AI result intake rejected: " + reason);
  }

  private static String stringFrom(Map<String, Object> map, String... keys) {
    if (map == null) {
      return null;
    }
    for (String key : keys) {
      Object value = map.get(key);
      if (value != null) {
        return value.toString();
      }
    }
    return null;
  }

  private static String orDefault(String value, String fallback) {
    return isBlank(value) ? fallback : value;
  }

  private static String bounded(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() > max ? value.substring(0, max) : value;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
