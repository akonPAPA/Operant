package com.orderpilot.application.services.validation;

import com.orderpilot.api.dto.AdvisoryValidationHandoffDtos.AdvisoryValidationHandoffResult;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractedField;
import com.orderpilot.domain.extraction.ExtractedFieldRepository;
import com.orderpilot.domain.extraction.ExtractedLineItem;
import com.orderpilot.domain.extraction.ExtractedLineItemRepository;
import com.orderpilot.domain.extraction.ExtractionResult;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.extraction.SourceEvidence;
import com.orderpilot.domain.extraction.SourceEvidenceRepository;
import com.orderpilot.domain.validation.ExtractionValidationResult;
import com.orderpilot.domain.validation.ValidationIssue;
import com.orderpilot.domain.validation.ValidationRun;
import com.orderpilot.domain.validation.ValidationRunRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-13A — advisory extraction → deterministic validation handoff.
 *
 * <p>Bridges a persisted AI-worker advisory {@link ExtractionResult} (provider {@code AI_WORKER},
 * stored as an untrusted advisory JSON wrapper by {@code AiWorkerResultIntakeService}) into the
 * existing deterministic validation engine ({@link ExtractionValidationService} →
 * {@code ValidationRunService}). The advisory output is decomposed into <em>untrusted</em> normalized
 * {@link ExtractedField}/{@link ExtractedLineItem} rows so the deterministic engine — which owns all
 * SKU/customer/price/stock/margin/substitution decisions — can produce {@link ValidationIssue}s,
 * approval requirements, and a routing recommendation.
 *
 * <p>Trust boundary (unchanged): AI output is advisory only. This service creates only advisory
 * extraction rows and deterministic validation artifacts. It never creates or mutates a quote, order,
 * draft order, inventory, customer, price, discount, margin rule, connector, or ERP/1C state, and it
 * carries no executable/business-action surface. Failures and unsafe output fail closed to a controlled
 * reviewable result rather than a business mutation.
 */
@Service
public class AdvisoryExtractionValidationHandoffService {
  /** Advisory wrapper {@code source} marker written by the AI-worker intake layer. */
  static final String PROVIDER_SOURCE = "AI_WORKER";

  /** Worker/validation statuses that must never be decomposed into business candidates. */
  static final Set<String> TERMINAL_FAILURE_STATUSES = Set.of("FAILED", "REJECTED");

  // Bounds for the untrusted advisory values copied into normalized rows.
  static final int MAX_SKU = 200;
  static final int MAX_TEXT = 512;
  static final int MAX_TOKEN = 60;
  static final int MAX_SNIPPET = 240;
  static final int MAX_LINES = 200;
  static final int MAX_FIELDS = 200;

  // Normalized (underscore/space stripped, lower-cased) forbidden action/mutation keys. Mirrors the
  // intake guard and the worker denylist so a nested business-action key in advisory output fails
  // closed here too (defense in depth at the structured-row decomposition boundary).
  static final Set<String> FORBIDDEN_ACTION_KEYS = Set.of(
      "action", "command", "approve", "approved", "execute", "write", "mutation", "sql", "shell",
      "exec", "toolcall", "toolcalls", "functioncall", "erpwrite", "externalwrite", "changerequest",
      "createorder", "ordercreate", "createquote", "approveorder", "approvequote", "quoteapprove",
      "placeorder", "updateinventory", "inventoryupdate", "updatestock", "updateprice", "priceupdate",
      "changeprice", "discountapproval", "customerupdate");

  private final ExtractionResultRepository extractionResultRepository;
  private final ExtractedFieldRepository fieldRepository;
  private final ExtractedLineItemRepository lineRepository;
  private final SourceEvidenceRepository evidenceRepository;
  private final ValidationRunRepository validationRunRepository;
  private final ExtractionValidationService extractionValidationService;
  private final AuditEventService auditEventService;
  private final JsonSupport json;
  private final Clock clock;

  public AdvisoryExtractionValidationHandoffService(
      ExtractionResultRepository extractionResultRepository,
      ExtractedFieldRepository fieldRepository,
      ExtractedLineItemRepository lineRepository,
      SourceEvidenceRepository evidenceRepository,
      ValidationRunRepository validationRunRepository,
      ExtractionValidationService extractionValidationService,
      AuditEventService auditEventService,
      JsonSupport json,
      Clock clock) {
    this.extractionResultRepository = extractionResultRepository;
    this.fieldRepository = fieldRepository;
    this.lineRepository = lineRepository;
    this.evidenceRepository = evidenceRepository;
    this.validationRunRepository = validationRunRepository;
    this.extractionValidationService = extractionValidationService;
    this.auditEventService = auditEventService;
    this.json = json;
    this.clock = clock;
  }

  /**
   * Hand a persisted advisory AI-worker extraction result into deterministic validation. Tenant is
   * resolved server-side; a result belonging to another tenant is simply not found and fails closed.
   */
  @Transactional
  public AdvisoryValidationHandoffResult handoff(UUID extractionResultId) {
    UUID tenantId = TenantContext.requireTenantId();
    ExtractionResult extraction = extractionResultRepository.findByIdAndTenantId(extractionResultId, tenantId)
        .orElseThrow(() -> rejected(extractionResultId, "extraction_result_not_found"));
    Instant now = clock.instant();
    Map<String, Object> wrapper = json.parseObject(extraction.getResultJson());

    // Only AI-worker advisory results are handed off here. Core-internal extractions already own their
    // normalized rows and go through validation directly; refusing them avoids double-decomposition.
    if (!PROVIDER_SOURCE.equalsIgnoreCase(String.valueOf(wrapper.get("source")))) {
      throw rejected(extractionResultId, "not_ai_worker_advisory_result");
    }

    String validationStatus = upper(extraction.getValidationStatus());
    String workerStatus = upper(String.valueOf(wrapper.getOrDefault("workerStatus", "")));

    // Fail-closed status gate: a failed/rejected worker result never decomposes into business
    // candidates and never runs validation. It returns a controlled, reviewable failed handoff state.
    if (TERMINAL_FAILURE_STATUSES.contains(validationStatus) || TERMINAL_FAILURE_STATUSES.contains(workerStatus)) {
      String reason = bounded(String.valueOf(wrapper.getOrDefault("safeFailureReason", "")), MAX_TEXT);
      if (isBlank(reason)) {
        reason = validationStatus.toLowerCase(Locale.ROOT);
      }
      audit("advisory_validation_handoff.failed_closed", extraction, null, validationStatus, reason);
      return failed(extraction, AdvisoryValidationHandoffDtosStatus.FAILED_EXTRACTION, reason);
    }

    Map<String, Object> payload = asMap(wrapper.get("extraction"));

    // Defense-in-depth: re-scan the nested advisory payload for any executable/business-action key.
    if (containsForbiddenActionKey(payload, 0)) {
      audit("advisory_validation_handoff.unsafe_rejected", extraction, null, validationStatus, "forbidden_action_key");
      return failed(extraction, AdvisoryValidationHandoffDtosStatus.UNSAFE_OUTPUT_REJECTED, "forbidden_action_key");
    }

    // Idempotency: a prior handoff that already validated this result returns the latest run with no
    // duplicate rows or runs. Retry after a partial decomposition reuses the existing rows.
    List<ValidationRun> existingRuns =
        validationRunRepository.findByTenantIdAndExtractionResultIdOrderByCreatedAtDesc(tenantId, extractionResultId);
    boolean alreadyDecomposed =
        !lineRepository.findByTenantIdAndExtractionResultId(tenantId, extractionResultId).isEmpty();
    if (!existingRuns.isEmpty()) {
      ExtractionValidationResult existing = extractionValidationService.latestByExtractionResultId(extractionResultId);
      audit("advisory_validation_handoff.duplicate", extraction, existing.validationRunId(), validationStatus, null);
      return accepted(extraction, existing, decomposedLineCount(tenantId, extractionResultId), true);
    }

    int lineCount = alreadyDecomposed
        ? decomposedLineCount(tenantId, extractionResultId)
        : decompose(tenantId, extraction, payload, now);

    // Delegate to the existing deterministic validation engine. It owns every validation/risk decision
    // and creates only validation artifacts (issues/approvals) — never business records.
    ExtractionValidationResult validation = extractionValidationService.validateCompletedExtraction(extractionResultId);
    audit("advisory_validation_handoff.accepted", extraction, validation.validationRunId(), validationStatus, null);
    return accepted(extraction, validation, lineCount, false);
  }

  private int decompose(UUID tenantId, ExtractionResult extraction, Map<String, Object> payload, Instant now) {
    List<Map<String, Object>> fields = asMapList(payload.get("fields"));
    int fieldCount = 0;
    for (Map<String, Object> field : fields) {
      if (fieldCount >= MAX_FIELDS) {
        break;
      }
      String name = bounded(string(field, "field_name", "fieldName"), 120);
      if (isBlank(name)) {
        continue;
      }
      fieldRepository.save(new ExtractedField(
          tenantId, extraction.getId(), name,
          bounded(string(field, "raw_value", "rawValue"), MAX_TEXT),
          bounded(string(field, "normalized_value", "normalizedValue"), MAX_TEXT),
          orDefault(bounded(string(field, "value_type", "valueType"), MAX_TOKEN), "STRING"),
          confidence(field), null, now));
      fieldCount++;
    }

    List<Map<String, Object>> items = asMapList(payload.get("line_items"));
    if (items.isEmpty()) {
      items = asMapList(payload.get("lineItems"));
    }
    int lineNumber = 0;
    int saved = 0;
    for (Map<String, Object> line : items) {
      if (saved >= MAX_LINES) {
        break;
      }
      lineNumber++;
      int number = intOr(line.get("line_number"), line.get("lineNumber"), lineNumber);
      String rawSku = bounded(string(line, "raw_sku", "rawSku"), MAX_SKU);
      String rawDescription = bounded(string(line, "raw_description", "rawDescription"), MAX_TEXT);
      String rawQuantity = bounded(string(line, "raw_quantity", "rawQuantity"), MAX_TOKEN);
      String rawUom = bounded(string(line, "raw_uom", "rawUom"), MAX_TOKEN);
      // normalizedUom is intentionally null — the deterministic UOM service normalizes it; the worker
      // value is never trusted as the normalized form.
      UUID evidenceId = saveEvidence(tenantId, extraction, firstNonBlank(rawDescription, rawSku, rawQuantity), now);
      lineRepository.save(new ExtractedLineItem(
          tenantId, extraction.getId(), number, rawSku, rawDescription, rawQuantity,
          parseQuantity(rawQuantity), rawUom, null, confidence(line), evidenceId, now));
      saved++;
    }
    return saved;
  }

  // Preserve bounded source/evidence provenance per line. No raw secrets/credentials/commands are
  // stored; the snippet is the bounded advisory line text only.
  private UUID saveEvidence(UUID tenantId, ExtractionResult extraction, String snippet, Instant now) {
    if (isBlank(snippet)) {
      return null;
    }
    String evidenceType = "CHANNEL_MESSAGE".equalsIgnoreCase(extraction.getSourceType())
        ? "MESSAGE_TEXT_SPAN" : "DOCUMENT_TEXT_SPAN";
    SourceEvidence evidence = evidenceRepository.save(new SourceEvidence(
        tenantId, extraction.getExtractionRunId(), extraction.getSourceType(), extraction.getSourceId(),
        evidenceType, null, null, bounded(snippet, MAX_SNIPPET), now));
    return evidence.getId();
  }

  private int decomposedLineCount(UUID tenantId, UUID extractionResultId) {
    return lineRepository.findByTenantIdAndExtractionResultId(tenantId, extractionResultId).size();
  }

  private AdvisoryValidationHandoffResult accepted(
      ExtractionResult extraction, ExtractionValidationResult validation, int lineCount, boolean duplicate) {
    List<ValidationIssue> issues = validation.validationIssues();
    int blocking = (int) issues.stream()
        .filter(i -> "CRITICAL".equals(i.getSeverity()) || "ERROR".equals(i.getSeverity()))
        .count();
    return new AdvisoryValidationHandoffResult(
        extraction.getId(), extraction.getSourceType(), extraction.getSourceId(), extraction.getDetectedIntent(),
        AdvisoryValidationHandoffDtosStatus.ACCEPTED, validation.validationRunId(), validation.overallStatus(),
        validation.routingRecommendation() == null ? null : validation.routingRecommendation().name(),
        lineCount, issues.size(), blocking, validation.approvalRequirements().size(), duplicate, true, null);
  }

  private AdvisoryValidationHandoffResult failed(ExtractionResult extraction, String status, String reason) {
    return new AdvisoryValidationHandoffResult(
        extraction.getId(), extraction.getSourceType(), extraction.getSourceId(), extraction.getDetectedIntent(),
        status, null, null, null, 0, 0, 0, 0, false, true, reason);
  }

  private void audit(String action, ExtractionResult extraction, UUID validationRunId, String workerValidationStatus, String reason) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("extractionResultId", extraction.getId().toString());
    metadata.put("sourceType", extraction.getSourceType());
    metadata.put("sourceId", String.valueOf(extraction.getSourceId()));
    metadata.put("validationRunId", validationRunId == null ? null : validationRunId.toString());
    metadata.put("workerValidationStatus", workerValidationStatus);
    metadata.put("reason", reason);
    metadata.put("advisoryOnly", true);
    auditEventService.record(action, "extraction_result", extraction.getId().toString(), null, json.writeObject(metadata));
  }

  private IllegalArgumentException rejected(UUID extractionResultId, String reason) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("extractionResultId", String.valueOf(extractionResultId));
    metadata.put("reason", reason);
    metadata.put("advisoryOnly", true);
    auditEventService.record("advisory_validation_handoff.rejected", "extraction_result",
        String.valueOf(extractionResultId), null, json.writeObject(metadata));
    return new IllegalArgumentException("Advisory validation handoff rejected: " + reason);
  }

  @SuppressWarnings("unchecked")
  private boolean containsForbiddenActionKey(Object value, int depth) {
    if (depth > 8) {
      return false;
    }
    if (value instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (entry.getKey() instanceof String key && FORBIDDEN_ACTION_KEYS.contains(normalizeKey(key))) {
          return true;
        }
        if (containsForbiddenActionKey(entry.getValue(), depth + 1)) {
          return true;
        }
      }
      return false;
    }
    if (value instanceof List<?> list) {
      for (Object item : list) {
        if (containsForbiddenActionKey(item, depth + 1)) {
          return true;
        }
      }
    }
    return false;
  }

  private static String normalizeKey(String key) {
    return key.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> asMapList(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    return list.stream()
        .filter(item -> item instanceof Map<?, ?>)
        .map(item -> (Map<String, Object>) item)
        .toList();
  }

  private static String string(Map<String, Object> map, String... keys) {
    for (String key : keys) {
      Object value = map.get(key);
      if (value != null) {
        return value.toString();
      }
    }
    return null;
  }

  private static BigDecimal confidence(Map<String, Object> map) {
    Object value = map.get("confidence");
    if (value instanceof Number number) {
      return BigDecimal.valueOf(Math.max(0.0, Math.min(1.0, number.doubleValue())));
    }
    return BigDecimal.ZERO;
  }

  private static int intOr(Object snake, Object camel, int fallback) {
    Object value = snake != null ? snake : camel;
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text) {
      try {
        return Integer.parseInt(text.trim());
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }

  private static BigDecimal parseQuantity(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return new BigDecimal(raw.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (!isBlank(value)) {
        return value;
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

  private static String upper(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  // Local alias for the DTO status tokens to keep call sites short and consistent.
  private static final class AdvisoryValidationHandoffDtosStatus {
    static final String ACCEPTED = com.orderpilot.api.dto.AdvisoryValidationHandoffDtos.STATUS_ACCEPTED;
    static final String FAILED_EXTRACTION = com.orderpilot.api.dto.AdvisoryValidationHandoffDtos.STATUS_FAILED_EXTRACTION;
    static final String UNSAFE_OUTPUT_REJECTED = com.orderpilot.api.dto.AdvisoryValidationHandoffDtos.STATUS_UNSAFE_OUTPUT_REJECTED;
    private AdvisoryValidationHandoffDtosStatus() {}
  }
}
