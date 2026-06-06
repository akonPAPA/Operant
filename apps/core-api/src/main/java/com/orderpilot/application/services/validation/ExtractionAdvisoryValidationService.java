package com.orderpilot.application.services.validation;

import com.orderpilot.api.dto.AiValidationDtos.AiValidationIssueView;
import com.orderpilot.api.dto.AiValidationDtos.AiValidationResultView;
import com.orderpilot.api.dto.ValidationEngineDtos.ExtractedRequestValidationResult;
import com.orderpilot.api.dto.ValidationEngineDtos.ValidateExtractedRequestCommand;
import com.orderpilot.api.dto.ValidationEngineDtos.ValidationIssueView;
import com.orderpilot.api.dto.ValidationEngineDtos.ValidationLineInput;
import com.orderpilot.api.dto.ValidationEngineDtos.ValidationLineResult;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractionResult;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.extraction.ExtractionRun;
import com.orderpilot.domain.extraction.ExtractionRunRepository;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import com.orderpilot.domain.validation.AiExtractionValidation;
import com.orderpilot.domain.validation.AiExtractionValidationIssue;
import com.orderpilot.domain.validation.AiExtractionValidationIssueRepository;
import com.orderpilot.domain.validation.AiExtractionValidationRepository;
import com.orderpilot.domain.validation.AiValidationIssueCode;
import com.orderpilot.domain.validation.AiValidationRiskLevel;
import com.orderpilot.domain.validation.AiValidationRoutingDecision;
import com.orderpilot.domain.validation.ValidationSeverity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-07E deterministic validation &amp; risk routing for advisory AI extraction results.
 *
 * <p>Consumes a persisted, untrusted advisory {@code ExtractionResult} (produced by the OP-CAP-07D
 * AI-worker intake), runs deterministic checks, and produces validation issues, a risk level and a
 * routing decision. It reuses the existing OP-CAP-08A {@link ValidationEngineService} for the heavy
 * customer/product/quantity/UOM/inventory/price resolution, then layers AI-advisory gating
 * (provider-failure / rejected / prompt-injection / missing-intent / missing-line-items) on top.
 *
 * <p>Hard boundaries: tenant resolved server-side; AI {@code tenantRef} is never trusted. This service
 * only ever reads master data and writes its own advisory validation/issue rows + the processing job
 * status. It never creates a quote/order, approves anything, mutates product/customer/inventory/price
 * data, or triggers any external/ERP write. Provider-failed / rejected results can never route to a
 * draft-ready state; prompt-injection and low-confidence always route to human review.
 */
@Service
public class ExtractionAdvisoryValidationService {
  private static final Set<String> SUPPORTED_SOURCE_TYPES = Set.of(
      "CHANNEL_MESSAGE", "INBOUND_DOCUMENT", "EMAIL_BODY", "PDF_TEXT", "EXCEL_TEXT", "CSV_TEXT",
      "API_UPLOAD_TEXT");
  // Intents that are expected to carry at least one line item (RFQ/PO-like).
  private static final Set<String> LINE_ITEM_INTENTS = Set.of(
      "RFQ", "REQUEST_QUOTE", "PURCHASE_ORDER", "PO");
  private static final Set<String> KNOWN_INTENTS = Set.of(
      "RFQ", "REQUEST_QUOTE", "PURCHASE_ORDER", "PO", "AVAILABILITY_INQUIRY", "PRICE_INQUIRY",
      "SUBSTITUTE_REQUEST", "ORDER_STATUS_INQUIRY");
  private static final int MAX_MESSAGE = 240;

  private final ExtractionResultRepository extractionResultRepository;
  private final ExtractionRunRepository extractionRunRepository;
  private final ProcessingJobRepository processingJobRepository;
  private final ValidationEngineService validationEngineService;
  private final AiExtractionValidationRepository validationRepository;
  private final AiExtractionValidationIssueRepository issueRepository;
  private final AuditEventService auditEventService;
  private final JsonSupport json;
  private final Clock clock;

  public ExtractionAdvisoryValidationService(
      ExtractionResultRepository extractionResultRepository,
      ExtractionRunRepository extractionRunRepository,
      ProcessingJobRepository processingJobRepository,
      ValidationEngineService validationEngineService,
      AiExtractionValidationRepository validationRepository,
      AiExtractionValidationIssueRepository issueRepository,
      AuditEventService auditEventService,
      JsonSupport json,
      Clock clock) {
    this.extractionResultRepository = extractionResultRepository;
    this.extractionRunRepository = extractionRunRepository;
    this.processingJobRepository = processingJobRepository;
    this.validationEngineService = validationEngineService;
    this.validationRepository = validationRepository;
    this.issueRepository = issueRepository;
    this.auditEventService = auditEventService;
    this.json = json;
    this.clock = clock;
  }

  @Transactional
  public AiValidationResultView validate(UUID extractionResultId) {
    UUID tenantId = TenantContext.requireTenantId();
    // Fail closed: the result must exist for THIS tenant. AI tenantRef is never the authority.
    ExtractionResult extraction = extractionResultRepository.findByIdAndTenantId(extractionResultId, tenantId)
        .orElseThrow(() -> new IllegalArgumentException("Advisory extraction result not found for tenant"));

    Map<String, Object> wrapper = json.parseObject(extraction.getResultJson());
    boolean advisory = Boolean.TRUE.equals(wrapper.get("advisoryOnly"));
    String source = asString(wrapper.get("source"));
    if (!advisory || !"AI_WORKER".equals(source)) {
      throw new IllegalArgumentException("Extraction result is not an advisory AI-worker result");
    }

    ExtractionRun run = extraction.getExtractionRunId() == null ? null
        : extractionRunRepository.findByIdAndTenantId(extraction.getExtractionRunId(), tenantId).orElse(null);
    UUID processingJobId = run == null ? null : run.getProcessingJobId();
    String sourceType = extraction.getSourceType();
    UUID sourceId = extraction.getSourceId();

    String workerStatus = upper(asString(wrapper.get("workerStatus")));
    List<String> signals = asStringList(wrapper.get("promptInjectionSignals"));

    List<DerivedIssue> issues = new ArrayList<>();
    int unknownProductCount = 0;
    boolean unknownCustomer = false;
    boolean missingLineItems = false;

    if ("FAILED".equals(workerStatus)) {
      // Provider failure cannot enter business validation as valid.
      issues.add(new DerivedIssue(AiValidationIssueCode.PROVIDER_FAILURE, ValidationSeverity.CRITICAL, null, null,
          "AI worker reported a provider failure; advisory result cannot be validated"));
    } else if ("REJECTED".equals(workerStatus)) {
      issues.add(new DerivedIssue(AiValidationIssueCode.EXTRACTION_REJECTED, ValidationSeverity.CRITICAL, null, null,
          "AI worker rejected the input; advisory result cannot be validated"));
    } else if (!"SUCCEEDED".equals(workerStatus) && !"NEEDS_REVIEW".equals(workerStatus)) {
      issues.add(new DerivedIssue(AiValidationIssueCode.PROVIDER_FAILURE, ValidationSeverity.CRITICAL, null, null,
          "AI worker status is missing or unrecognized; advisory result cannot be validated"));
    } else {
      // Advisory payload is structurally usable: run the AI-advisory + deterministic checks.
      Map<String, Object> payload = asMap(wrapper.get("extraction"));
      String intent = upper(asString(payload.get("detected_intent")));
      List<Object> lineItems = asList(payload.get("line_items"));

      if (!SUPPORTED_SOURCE_TYPES.contains(upper(sourceType))) {
        issues.add(new DerivedIssue(AiValidationIssueCode.UNSUPPORTED_SOURCE_TYPE, ValidationSeverity.WARNING, null, null,
            "Source type is not supported for deterministic validation"));
      }
      if (intent == null || intent.isBlank() || !KNOWN_INTENTS.contains(intent)) {
        issues.add(new DerivedIssue(AiValidationIssueCode.MISSING_INTENT, ValidationSeverity.WARNING, null, null,
            "Intent is missing or unsupported"));
      }
      if (lineItems.isEmpty() && intent != null && LINE_ITEM_INTENTS.contains(intent)) {
        missingLineItems = true;
        issues.add(new DerivedIssue(AiValidationIssueCode.MISSING_LINE_ITEMS, ValidationSeverity.CRITICAL, null, null,
            "No line items extracted for an RFQ/PO request"));
      }
      if (!signals.isEmpty()) {
        issues.add(new DerivedIssue(AiValidationIssueCode.PROMPT_INJECTION_SIGNAL, ValidationSeverity.WARNING, null, null,
            "Prompt injection signals present (count=" + signals.size() + "); human review required"));
      }

      // Reuse the deterministic engine for customer/product/quantity/UOM/inventory/price checks.
      ExtractedRequestValidationResult engine = validationEngineService.validate(
          toCommand(extraction, payload, intent, signals));
      unknownCustomer = engine.matchedCustomer() == null;
      DerivedAccumulator engineDerived = mapEngineIssues(engine);
      issues.addAll(engineDerived.issues());
      unknownProductCount = engineDerived.unknownProductCount();
    }

    Outcome outcome = classify(issues, workerStatus, signals.size(), unknownProductCount, missingLineItems);

    AiValidationResultView view = persist(tenantId, extraction, run, processingJobId, sourceType, sourceId,
        issues, outcome, signals.size(), unknownProductCount, unknownCustomer);
    updateProcessingJob(processingJobId, tenantId, outcome.routing());
    audit(view);
    return view;
  }

  @Transactional(readOnly = true)
  public AiValidationResultView latest(UUID extractionResultId) {
    UUID tenantId = TenantContext.requireTenantId();
    AiExtractionValidation v = validationRepository.findByTenantIdAndExtractionResultId(tenantId, extractionResultId)
        .orElseThrow(() -> new IllegalArgumentException("No advisory validation found for this extraction result"));
    List<AiExtractionValidationIssue> rows =
        issueRepository.findByTenantIdAndAiExtractionValidationIdOrderByCreatedAtAsc(tenantId, v.getId());
    return toView(v, rows);
  }

  // --- classification ---

  private Outcome classify(List<DerivedIssue> issues, String workerStatus, int signalCount,
      int unknownProductCount, boolean missingLineItems) {
    boolean blocked = issues.stream().anyMatch(i ->
        i.severity() == ValidationSeverity.CRITICAL || i.severity() == ValidationSeverity.ERROR);
    boolean anyWarning = issues.stream().anyMatch(i -> i.severity() == ValidationSeverity.WARNING);
    boolean unsupportedSource = issues.stream().anyMatch(i -> i.code() == AiValidationIssueCode.UNSUPPORTED_SOURCE_TYPE);

    AiValidationRiskLevel risk;
    if (blocked) {
      risk = AiValidationRiskLevel.BLOCKED;
    } else if (signalCount > 0 || unsupportedSource || unknownProductCount >= 2) {
      risk = AiValidationRiskLevel.HIGH;
    } else if (anyWarning) {
      risk = AiValidationRiskLevel.MEDIUM;
    } else {
      risk = AiValidationRiskLevel.LOW;
    }

    AiValidationRoutingDecision routing;
    if ("FAILED".equals(workerStatus)) {
      routing = AiValidationRoutingDecision.FAILED_VALIDATION;
    } else if (risk == AiValidationRiskLevel.BLOCKED) {
      routing = AiValidationRoutingDecision.BLOCKED_INVALID_EXTRACTION;
    } else if (risk == AiValidationRiskLevel.LOW) {
      routing = AiValidationRoutingDecision.READY_FOR_DRAFT_REVIEW;
    } else {
      routing = AiValidationRoutingDecision.NEEDS_HUMAN_REVIEW;
    }
    return new Outcome(risk, routing);
  }

  // --- engine adaptation ---

  private ValidateExtractedRequestCommand toCommand(
      ExtractionResult extraction, Map<String, Object> payload, String intent, List<String> signals) {
    List<String> customerHints = asStringList(payload.get("customer_hints"));
    String customerHint = customerHints.isEmpty() ? null : customerHints.get(0);
    List<Object> rawLines = asList(payload.get("line_items"));
    List<ValidationLineInput> lines = new ArrayList<>();
    int idx = 0;
    for (Object raw : rawLines) {
      Map<String, Object> line = asMap(raw);
      Integer lineNumber = asInteger(line.get("line_number"));
      lines.add(new ValidationLineInput(
          lineNumber == null ? idx : lineNumber,
          asString(line.get("raw_description")),
          firstNonBlank(asString(line.get("raw_sku")), asString(line.get("raw_alias"))),
          asBigDecimal(line.get("raw_quantity")),
          asString(line.get("raw_uom")),
          asDouble(line.get("confidence")),
          null, null, null, null, null, null, null));
      idx++;
    }
    return new ValidateExtractedRequestCommand(
        extraction.getSourceType(),
        extraction.getSourceId() == null ? null : extraction.getSourceId().toString(),
        intent,
        asDouble(payload.get("overall_confidence")),
        signals,
        customerHint,
        null, null, null, null, null,
        lines);
  }

  private DerivedAccumulator mapEngineIssues(ExtractedRequestValidationResult engine) {
    List<DerivedIssue> derived = new ArrayList<>();
    Set<String> unknownProductLines = new LinkedHashSet<>();
    for (ValidationIssueView view : engine.issues()) {
      switch (view.type()) {
        case CUSTOMER_NOT_FOUND, CUSTOMER_AMBIGUOUS -> derived.add(map(AiValidationIssueCode.UNKNOWN_CUSTOMER, view));
        case PRODUCT_NOT_FOUND, PRODUCT_AMBIGUOUS -> {
          derived.add(map(AiValidationIssueCode.UNKNOWN_PRODUCT, view));
          unknownProductLines.add(String.valueOf(view.lineIndex()));
        }
        case INVALID_QUANTITY -> derived.add(map(AiValidationIssueCode.INVALID_QUANTITY, view));
        case INVALID_UOM -> derived.add(map(AiValidationIssueCode.INVALID_UOM, view));
        case LOW_EXTRACTION_CONFIDENCE -> derived.add(map(AiValidationIssueCode.LOW_CONFIDENCE_FIELD, view));
        case UNSUPPORTED_INTENT -> { /* already covered by the AI-level MISSING_INTENT check */ }
        case PRICE_NOT_FOUND -> derived.add(map(AiValidationIssueCode.PRICE_UNKNOWN, view));
        default -> { /* engine inventory/margin/substitute/compat signals are not part of the 07E core taxonomy */ }
      }
    }
    // Inventory-unknown for matched products (engine emits no issue when there is simply no snapshot).
    for (ValidationLineResult line : engine.lineResults()) {
      if (line.matchedProduct() != null && "UNKNOWN".equals(line.inventoryStatus())) {
        derived.add(new DerivedIssue(AiValidationIssueCode.INVENTORY_UNKNOWN, ValidationSeverity.WARNING,
            line.lineIndex(), null, "No inventory data available for the matched product"));
      }
    }
    return new DerivedAccumulator(dedupe(derived), unknownProductLines.size());
  }

  private DerivedIssue map(AiValidationIssueCode code, ValidationIssueView view) {
    return new DerivedIssue(code, view.severity(), view.lineIndex(), null, bounded(view.message()));
  }

  private List<DerivedIssue> dedupe(List<DerivedIssue> derived) {
    Map<String, DerivedIssue> byKey = new LinkedHashMap<>();
    for (DerivedIssue d : derived) {
      String key = d.code().name() + "#" + d.lineIndex();
      DerivedIssue existing = byKey.get(key);
      if (existing == null || d.severity().ordinal() > existing.severity().ordinal()) {
        byKey.put(key, d);
      }
    }
    return new ArrayList<>(byKey.values());
  }

  // --- persistence ---

  private AiValidationResultView persist(UUID tenantId, ExtractionResult extraction, ExtractionRun run,
      UUID processingJobId, String sourceType, UUID sourceId, List<DerivedIssue> issues, Outcome outcome,
      int signalCount, int unknownProductCount, boolean unknownCustomer) {
    Instant now = clock.instant();
    // Idempotent: replace any prior validation for this extraction result in one transaction. The
    // header fields are populated via apply() BEFORE the first persist so the non-null columns hold.
    AiExtractionValidation validation = validationRepository
        .findByTenantIdAndExtractionResultId(tenantId, extraction.getId())
        .orElse(null);
    if (validation == null) {
      validation = new AiExtractionValidation(tenantId, extraction.getId(), run == null ? null : run.getId(),
          processingJobId, sourceType, sourceId, now);
    } else {
      issueRepository.deleteByTenantIdAndAiExtractionValidationId(tenantId, validation.getId());
    }

    String highestSeverity = issues.stream()
        .max((a, b) -> Integer.compare(a.severity().ordinal(), b.severity().ordinal()))
        .map(i -> i.severity().name())
        .orElse(null);
    validation.apply(outcome.risk().name(), outcome.routing().name(), statusFor(outcome.routing()),
        issues.size(), highestSeverity, signalCount, unknownProductCount, unknownCustomer, now);
    validation = validationRepository.save(validation);

    List<AiExtractionValidationIssue> rows = new ArrayList<>();
    for (DerivedIssue d : issues) {
      rows.add(issueRepository.save(new AiExtractionValidationIssue(
          tenantId, validation.getId(), extraction.getId(), sourceType, sourceId,
          d.code().name(), d.severity().name(), d.fieldName(), d.lineIndex(), bounded(d.message()), null, now)));
    }
    return toView(validation, rows);
  }

  private void updateProcessingJob(UUID processingJobId, UUID tenantId, AiValidationRoutingDecision routing) {
    if (processingJobId == null) {
      return;
    }
    ProcessingJob job = processingJobRepository.findByIdAndTenantId(processingJobId, tenantId).orElse(null);
    if (job == null) {
      return;
    }
    Instant now = clock.instant();
    switch (routing) {
      case READY_FOR_DRAFT_REVIEW -> job.markSucceeded(now);
      case NEEDS_HUMAN_REVIEW -> job.markNeedsReview(now);
      case BLOCKED_INVALID_EXTRACTION -> job.markRejected("ai_validation_blocked", now);
      case FAILED_VALIDATION -> job.markFailed("ai_validation_failed", now);
    }
  }

  private void audit(AiValidationResultView view) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("extractionResultId", String.valueOf(view.extractionResultId()));
    metadata.put("extractionRunId", String.valueOf(view.extractionRunId()));
    metadata.put("processingJobId", String.valueOf(view.processingJobId()));
    metadata.put("sourceType", String.valueOf(view.sourceType()));
    metadata.put("sourceId", String.valueOf(view.sourceId()));
    metadata.put("riskLevel", view.riskLevel());
    metadata.put("routingDecision", view.routingDecision());
    metadata.put("issueCount", view.issueCount());
    metadata.put("highestSeverity", String.valueOf(view.highestSeverity()));
    metadata.put("promptInjectionSignalCount", view.promptInjectionSignalCount());
    metadata.put("unknownProductCount", view.unknownProductCount());
    metadata.put("unknownCustomer", view.unknownCustomer());
    metadata.put("advisoryOnly", true);
    auditEventService.record("ai_extraction_validation.completed", "ai_extraction_validation",
        view.validationId().toString(), null, json.writeObject(metadata));
  }

  private static String statusFor(AiValidationRoutingDecision routing) {
    return switch (routing) {
      case READY_FOR_DRAFT_REVIEW -> "VALIDATED";
      case NEEDS_HUMAN_REVIEW -> "NEEDS_REVIEW";
      case BLOCKED_INVALID_EXTRACTION -> "BLOCKED";
      case FAILED_VALIDATION -> "FAILED";
    };
  }

  private AiValidationResultView toView(AiExtractionValidation v, List<AiExtractionValidationIssue> rows) {
    List<AiValidationIssueView> issues = rows.stream()
        .map(r -> new AiValidationIssueView(r.getIssueCode(), r.getSeverity(), r.getLineIndex(),
            r.getFieldName(), r.getMessage(), r.getEvidenceRef()))
        .toList();
    return new AiValidationResultView(
        v.getId(), v.getExtractionResultId(), v.getExtractionRunId(), v.getProcessingJobId(),
        v.getSourceType(), v.getSourceId(), v.getRiskLevel(), v.getRoutingDecision(), v.getStatus(),
        v.getIssueCount(), v.getHighestSeverity(), v.getPromptInjectionSignalCount(),
        v.getUnknownProductCount(), v.isUnknownCustomer(), true, issues, v.getCreatedAt());
  }

  // --- safe parsing helpers ---

  private static String bounded(String value) {
    if (value == null) {
      return "";
    }
    return value.length() > MAX_MESSAGE ? value.substring(0, MAX_MESSAGE) : value;
  }

  private static String upper(String value) {
    return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) {
      return a;
    }
    return b == null || b.isBlank() ? null : b;
  }

  private static String asString(Object value) {
    return value == null ? null : value.toString();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  private static List<Object> asList(Object value) {
    return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
  }

  private static List<String> asStringList(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (Object o : list) {
      if (o != null) {
        out.add(o.toString());
      }
    }
    return out;
  }

  private static Integer asInteger(Object value) {
    if (value instanceof Number n) {
      return n.intValue();
    }
    try {
      return value == null ? null : Integer.valueOf(value.toString().trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static Double asDouble(Object value) {
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    try {
      return value == null ? null : Double.valueOf(value.toString().trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static BigDecimal asBigDecimal(Object value) {
    if (value instanceof Number n) {
      return new BigDecimal(n.toString());
    }
    try {
      return value == null || value.toString().isBlank() ? null : new BigDecimal(value.toString().trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  // --- internal holders ---

  private record DerivedIssue(AiValidationIssueCode code, ValidationSeverity severity, Integer lineIndex,
      String fieldName, String message) {}

  private record DerivedAccumulator(List<DerivedIssue> issues, int unknownProductCount) {}

  private record Outcome(AiValidationRiskLevel risk, AiValidationRoutingDecision routing) {}
}
