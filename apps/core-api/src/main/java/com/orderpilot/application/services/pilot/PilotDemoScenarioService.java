package com.orderpilot.application.services.pilot;

import com.orderpilot.application.services.pilot.PilotShadowModeService.EvidenceReport;
import com.orderpilot.application.services.pilot.PilotShadowModeService.PilotMetrics;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-11H Pilot Demo Scenario Pack.
 *
 * <p>Read-only, tenant-scoped, deterministic. Composes the existing pilot evidence report
 * ({@link PilotShadowModeService#evidenceReport()}) into a coherent set of investor/design-partner
 * demo scenarios with honest readiness. It introduces no new metric logic, no persistence, no
 * mutations, and never returns raw prediction/correction payloads, secrets, or object-storage internals.
 */
@Service
public class PilotDemoScenarioService {
  public enum DemoScenarioReadiness {
    NOT_AVAILABLE,
    BLOCKED,
    PARTIAL,
    READY_FOR_SCRIPTED_DEMO
  }

  private static final String SEED_GAP = "Needs seeded pilot data: no shadow runs recorded for this tenant yet.";

  private final PilotShadowModeService pilotShadowModeService;

  public PilotDemoScenarioService(PilotShadowModeService pilotShadowModeService) {
    this.pilotShadowModeService = pilotShadowModeService;
  }

  @Transactional(readOnly = true)
  public DemoScenarioPack demoScenarios() {
    EvidenceReport report = pilotShadowModeService.evidenceReport();
    PilotMetrics metrics = report.metrics();
    boolean hasEvidence = metrics.totalShadowRuns() > 0;

    List<DemoScenario> scenarios = List.of(
        telegramRfqSubstitution(metrics, hasEvidence),
        pdfPurchaseOrderException(metrics, hasEvidence),
        discountMarginGuardrail(metrics, hasEvidence),
        inventoryMismatch(metrics),
        badAiOutputRejected());

    List<String> packLimitations = List.of(
        "Demo scenarios are a read-only readiness view; they execute no business actions.",
        "Telegram and PDF flows are local/dev and mock-extraction only; not production-complete.",
        "Inventory reconciliation exists but is not yet recorded as pilot shadow evidence.",
        "Readiness never reaches 100%: READY_FOR_SCRIPTED_DEMO is the honest ceiling for this slice.",
        hasEvidence ? "Evidence is present for this tenant." : SEED_GAP);

    return new DemoScenarioPack(
        report.reportGeneratedAt(), report.tenantId(), hasEvidence, scenarios, packLimitations, report.safetyStatement());
  }

  // --- scenarios ---

  private DemoScenario telegramRfqSubstitution(PilotMetrics metrics, boolean hasEvidence) {
    boolean substitutionEvidence =
        metrics.predictionTypeBreakdown().containsKey("SUBSTITUTION")
            || metrics.exceptionCategoryCounts().containsKey("OUT_OF_STOCK_SUBSTITUTE");
    DemoScenarioReadiness readiness = substitutionEvidence ? DemoScenarioReadiness.READY_FOR_SCRIPTED_DEMO : DemoScenarioReadiness.PARTIAL;

    List<DemoScenarioCapability> capabilities = List.of(
        new DemoScenarioCapability("Controlled bot runtime", true, "Telegram intake runtime exists (local/dev); no production outbound sends."),
        new DemoScenarioCapability("Substitution engine", true, "Substitution suggestions exist via SubstitutionService/ProductSubstitute."),
        new DemoScenarioCapability("Pilot evidence", substitutionEvidence, substitutionEvidence ? "Substitution shadow evidence present." : "No substitution shadow evidence yet."));

    List<String> missing = new ArrayList<>();
    missing.add("Production outbound Telegram messaging is not enabled.");
    if (!substitutionEvidence) {
      missing.add(hasEvidence ? "No SUBSTITUTION prediction or OUT_OF_STOCK_SUBSTITUTE exception recorded yet." : SEED_GAP);
    }

    return new DemoScenario(
        "TELEGRAM_RFQ_SUBSTITUTION",
        "Telegram RFQ with substitution",
        "Convert a messy Telegram RFQ into a validated draft with a compatible substitute suggestion.",
        "Operator / Sales reviewer",
        "TELEGRAM",
        readiness, scoreFor(readiness), capabilities,
        scenarioEvidence(metrics, "SUBSTITUTION-related",
            longOrZero(metrics.exceptionCategoryCounts(), "OUT_OF_STOCK_SUBSTITUTE")),
        missing, commonSafetyBoundaries(),
        "/bot-conversations",
        relatedLinks(),
        List.of(
            "The bot creates a draft/review case only — it never approves a quote or order.",
            "Substitutes are advisory suggestions; a human confirms compatibility."));
  }

  private DemoScenario pdfPurchaseOrderException(PilotMetrics metrics, boolean hasEvidence) {
    boolean extractionEvidence = metrics.predictionTypeBreakdown().containsKey("EXTRACTION") && !metrics.exceptionCategoryCounts().isEmpty();
    DemoScenarioReadiness readiness = extractionEvidence ? DemoScenarioReadiness.READY_FOR_SCRIPTED_DEMO : DemoScenarioReadiness.PARTIAL;

    List<DemoScenarioCapability> capabilities = List.of(
        new DemoScenarioCapability("Document intake", true, "File-upload intake + object-storage pointer exist."),
        new DemoScenarioCapability("Extraction", true, "Deterministic mock extraction only; no production OCR/LLM."),
        new DemoScenarioCapability("Deterministic validation", true, "Validation engine produces issues/exceptions."),
        new DemoScenarioCapability("Pilot evidence", extractionEvidence, extractionEvidence ? "Extraction + exception evidence present." : "No extraction/exception shadow evidence yet."));

    List<String> missing = new ArrayList<>();
    missing.add("Production OCR/LLM extraction is not enabled (mock extraction only).");
    if (!extractionEvidence) {
      missing.add(hasEvidence ? "No EXTRACTION prediction with recorded exception categories yet." : SEED_GAP);
    }

    return new DemoScenario(
        "PDF_PO_EXCEPTION",
        "PDF purchase order with validation/exception handling",
        "Ingest a PDF purchase order, extract lines, and route validation exceptions for human review.",
        "Operator / Validation reviewer",
        "FILE_UPLOAD",
        readiness, scoreFor(readiness), capabilities,
        scenarioEvidence(metrics, "exception categories", metrics.exceptionCategoryCounts().values().stream().mapToLong(Long::longValue).sum()),
        missing, commonSafetyBoundaries(),
        "/validation-review",
        relatedLinks(),
        List.of(
            "AI extraction is advisory; deterministic validation is the controlled gate.",
            "Exceptions are routed to human review, not auto-approved."));
  }

  private DemoScenario discountMarginGuardrail(PilotMetrics metrics, boolean hasEvidence) {
    boolean marginEvidence = metrics.exceptionCategoryCounts().containsKey("MARGIN_VIOLATION")
        || metrics.exceptionCategoryCounts().containsKey("DISCOUNT_VIOLATION");
    DemoScenarioReadiness readiness = marginEvidence ? DemoScenarioReadiness.READY_FOR_SCRIPTED_DEMO : DemoScenarioReadiness.PARTIAL;

    List<DemoScenarioCapability> capabilities = List.of(
        new DemoScenarioCapability("Margin/discount validation", true, "MarginValidationService/DiscountValidationService exist."),
        new DemoScenarioCapability("Approval requirement", true, "ApprovalRequirementService gates risky discounts/margins."),
        new DemoScenarioCapability("Pilot evidence", marginEvidence, marginEvidence ? "Margin/discount exception evidence present." : "No margin/discount exception evidence yet."));

    List<String> missing = new ArrayList<>();
    if (!marginEvidence) {
      missing.add(hasEvidence ? "No MARGIN_VIOLATION/DISCOUNT_VIOLATION exception recorded yet." : SEED_GAP);
    }

    return new DemoScenario(
        "DISCOUNT_MARGIN_GUARDRAIL",
        "Discount/margin guardrail requiring approval",
        "Flag a discount or margin breach and require explicit manager approval before it can proceed.",
        "Manager / Approver",
        "INTERNAL",
        readiness, scoreFor(readiness), capabilities,
        scenarioEvidence(metrics, "MARGIN_VIOLATION", longOrZero(metrics.exceptionCategoryCounts(), "MARGIN_VIOLATION")),
        missing, commonSafetyBoundaries(),
        "/pilot-readiness/evidence-report",
        relatedLinks(),
        List.of(
            "Risky margin/discount changes never auto-approve; a manager must approve.",
            "The guardrail decision is deterministic and audit-oriented."));
  }

  private DemoScenario inventoryMismatch(PilotMetrics metrics) {
    // Reconciliation backend exists but is not yet wired into pilot shadow evidence -> honestly PARTIAL.
    DemoScenarioReadiness readiness = DemoScenarioReadiness.PARTIAL;

    List<DemoScenarioCapability> capabilities = List.of(
        new DemoScenarioCapability("Inventory reconciliation", true, "InventoryReconciliationService + ReconciliationCase exist."),
        new DemoScenarioCapability("Discrepancy case", true, "Reconciliation cases capture expected vs actual mismatch."),
        new DemoScenarioCapability("Pilot evidence link", false, "Reconciliation runs are not yet recorded as pilot shadow evidence."));

    return new DemoScenario(
        "INVENTORY_MISMATCH",
        "Inventory mismatch / reconciliation discrepancy",
        "Detect an expected-vs-actual stock mismatch and open a reconciliation discrepancy case.",
        "Operations / Reconciliation reviewer",
        "INTERNAL",
        readiness, scoreFor(readiness), capabilities,
        scenarioEvidence(metrics, "review-required runs", metrics.reviewRequiredCount()),
        List.of(
            "Reconciliation outcomes are not yet composed into the pilot evidence report.",
            "Show this scenario live via the /reconciliation workspace."),
        commonSafetyBoundaries(),
        "/reconciliation",
        relatedLinks(),
        List.of(
            "Reconciliation surfaces discrepancies for review; it performs no ERP write-back.",
            "Severity and case creation are deterministic and audited."));
  }

  private DemoScenario badAiOutputRejected() {
    // Deterministic safety guards exist (schema validation, prompt-injection guard, output sanitizer,
    // DTO safety tests). This is a code-level safety demo and needs no seeded data.
    DemoScenarioReadiness readiness = DemoScenarioReadiness.READY_FOR_SCRIPTED_DEMO;

    List<DemoScenarioCapability> capabilities = List.of(
        new DemoScenarioCapability("Schema validation", true, "ExtractionSchemaValidator rejects malformed AI output."),
        new DemoScenarioCapability("Prompt-injection guard", true, "PromptInjectionGuardService treats document/message text as hostile."),
        new DemoScenarioCapability("Output sanitizer", true, "AiOutputSanitizer strips unsafe AI output."),
        new DemoScenarioCapability("Safe DTO contract", true, "Pilot DTOs are reflection-tested to exclude raw payloads/secrets."));

    return new DemoScenario(
        "BAD_AI_OUTPUT_REJECTED",
        "Bad AI output / unsafe input rejection",
        "Demonstrate that malformed AI output and hostile/injected input are rejected before they affect business data.",
        "Security reviewer / Operator",
        "INTERNAL",
        readiness, scoreFor(readiness), capabilities,
        List.of(new DemoScenarioEvidence("Deterministic safety guards", "schema validation + prompt-injection guard + output sanitizer + DTO safety tests")),
        List.of("This is a code-level safety demonstration; it does not invoke a real AI provider."),
        commonSafetyBoundaries(),
        "/pilot-readiness",
        relatedLinks(),
        List.of(
            "AI output is advisory and must pass deterministic validation before use.",
            "Customer/document text is treated as hostile input by default."));
  }

  // --- helpers ---

  private static List<DemoScenarioEvidence> scenarioEvidence(PilotMetrics metrics, String focusLabel, long focusCount) {
    return List.of(
        new DemoScenarioEvidence("Total shadow runs", Long.toString(metrics.totalShadowRuns())),
        new DemoScenarioEvidence("Human correction rate", metrics.humanCorrectionRate().toPlainString()),
        new DemoScenarioEvidence(focusLabel + " signals", Long.toString(focusCount)));
  }

  private static long longOrZero(Map<String, Long> counts, String key) {
    Long value = counts.get(key);
    return value == null ? 0L : value;
  }

  private static List<DemoScenarioSafetyBoundary> commonSafetyBoundaries() {
    return List.of(
        new DemoScenarioSafetyBoundary("AI output is advisory only; it never writes business or master data."),
        new DemoScenarioSafetyBoundary("No ERP/1C/connector writes occur in this scenario."),
        new DemoScenarioSafetyBoundary("Risky actions require deterministic validation and human approval."),
        new DemoScenarioSafetyBoundary("All data is tenant-scoped and audit-oriented."));
  }

  private static List<String> relatedLinks() {
    return List.of("/pilot-readiness", "/pilot-readiness/evidence-report");
  }

  private static int scoreFor(DemoScenarioReadiness readiness) {
    return switch (readiness) {
      case NOT_AVAILABLE -> 0;
      case BLOCKED -> 15;
      case PARTIAL -> 50;
      case READY_FOR_SCRIPTED_DEMO -> 80;
    };
  }

  // --- service records (kept beside the pilot service records pattern) ---

  public record DemoScenarioCapability(String name, boolean available, String note) {}

  public record DemoScenarioEvidence(String label, String value) {}

  public record DemoScenarioSafetyBoundary(String statement) {}

  public record DemoScenario(
      String code,
      String title,
      String businessObjective,
      String primaryActorRole,
      String channelSourceType,
      DemoScenarioReadiness readiness,
      int readinessScore,
      List<DemoScenarioCapability> requiredCapabilities,
      List<DemoScenarioEvidence> evidenceSignals,
      List<String> missingCapabilities,
      List<DemoScenarioSafetyBoundary> safetyBoundaries,
      String suggestedDemoRoute,
      List<String> relatedReportLinks,
      List<String> operatorTalkingPoints) {}

  public record DemoScenarioPack(
      Instant reportGeneratedAt,
      UUID tenantId,
      boolean tenantHasPilotEvidence,
      List<DemoScenario> scenarios,
      List<String> packLimitations,
      String safetyStatement) {}
}
