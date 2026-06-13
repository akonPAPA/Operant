package com.orderpilot.application.services.trust;

import com.orderpilot.api.dto.TrustDtos.DocumentTrustRunView;
import com.orderpilot.api.dto.TrustDtos.DocumentTrustSignalView;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.application.services.trust.DocumentFingerprintService.FingerprintResult;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.trust.DocumentTrustCandidate;
import com.orderpilot.domain.trust.DocumentTrustDecision;
import com.orderpilot.domain.trust.DocumentTrustRun;
import com.orderpilot.domain.trust.DocumentTrustRunRepository;
import com.orderpilot.domain.trust.DocumentTrustSignal;
import com.orderpilot.domain.trust.DocumentTrustSignalRepository;
import com.orderpilot.domain.trust.TrustRiskLevel;
import com.orderpilot.domain.trust.TrustSignalCode;
import com.orderpilot.domain.trust.TrustSignalSeverity;
import com.orderpilot.domain.trust.TrustSignalSpec;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-17A Document Trust Signal Foundation.
 *
 * Deterministic, explainable document trust evaluation. Builds a tenant-scoped {@link DocumentTrustRun}
 * with one {@link DocumentTrustSignal} per fired check and a {@link DocumentTrustDecision} routing
 * outcome. Evaluation is idempotent per tenant + source document + content (or caller idempotency
 * key): repeats collapse onto the existing active run. This is a risk-signal foundation for operator
 * review — it never claims a document is fake or fraudulent, never writes business data, and never
 * stores raw document text/payload.
 */
@Service
public class DocumentTrustService {
  /** OCR confidence on a critical field at or below this value fires a trust signal. */
  static final BigDecimal CRITICAL_OCR_THRESHOLD = new BigDecimal("0.60");

  private final DocumentFingerprintService fingerprintService;
  private final DocumentTrustRunRepository runs;
  private final DocumentTrustSignalRepository signals;
  private final DocumentTrustDecisionPolicy policy;
  private final AuditEventRepository auditEvents;
  private final JsonSupport jsonSupport;
  private final Clock clock;

  public DocumentTrustService(
      DocumentFingerprintService fingerprintService,
      DocumentTrustRunRepository runs,
      DocumentTrustSignalRepository signals,
      DocumentTrustDecisionPolicy policy,
      AuditEventRepository auditEvents,
      JsonSupport jsonSupport,
      Clock clock) {
    this.fingerprintService = fingerprintService;
    this.runs = runs;
    this.signals = signals;
    this.policy = policy;
    this.auditEvents = auditEvents;
    this.jsonSupport = jsonSupport;
    this.clock = clock;
  }

  /**
   * Runs all deterministic trust checks for an inbound document and persists the run, its signals,
   * and (for HIGH/CRITICAL) an audit event. Tenant isolation is anchored on the supplied
   * {@code tenantId}. Idempotent: a repeat for the same tenant + source document + content (or
   * idempotency key) returns the existing active run without creating a duplicate.
   */
  @Transactional
  public DocumentTrustRun evaluate(UUID tenantId, UUID sourceDocumentId, UUID validationRunId, DocumentTrustCandidate candidate) {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId is required");
    }
    if (sourceDocumentId == null) {
      throw new IllegalArgumentException("sourceDocumentId is required");
    }
    if (candidate == null) {
      throw new IllegalArgumentException("candidate metadata is required");
    }

    Instant now = clock.instant();
    String contentSha256 = fingerprintService.computeSha256(candidate.contentHashInput());
    String idempotencyKey = normalize(candidate.idempotencyKey());

    Optional<DocumentTrustRun> existing = findExistingActive(tenantId, sourceDocumentId, contentSha256, idempotencyKey);
    if (existing.isPresent()) {
      return existing.get();
    }

    FingerprintResult fingerprint = fingerprintService.fingerprint(
        tenantId, sourceDocumentId, candidate.contentHashInput(), candidate.fileSizeBytes());

    List<TrustSignalSpec> specs = computeSignals(candidate, fingerprint, now);
    DocumentTrustDecision decision = policy.decide(specs);

    DocumentTrustRun run = runs.save(new DocumentTrustRun(
        tenantId,
        sourceDocumentId,
        validationRunId,
        fingerprint.fingerprintId(),
        contentSha256,
        idempotencyKey,
        decision,
        fingerprint.duplicate(),
        specs.size(),
        candidate.fileSizeBytes(),
        candidate.pageCount(),
        now));

    for (TrustSignalSpec spec : specs) {
      signals.save(new DocumentTrustSignal(
          tenantId, run.getId(), spec.code(), spec.severity(),
          spec.fieldKey(), spec.pageNumber(), spec.evidenceRef(), spec.explanation(), now));
    }

    // HIGH/CRITICAL trust decisions are audit-significant.
    if (run.getRiskLevel().atLeast(TrustRiskLevel.HIGH)) {
      recordAudit(tenantId, run);
    }
    return run;
  }

  private Optional<DocumentTrustRun> findExistingActive(UUID tenantId, UUID sourceDocumentId, String contentSha256, String idempotencyKey) {
    if (idempotencyKey != null) {
      return runs.findFirstByTenantIdAndIdempotencyKeyAndActiveTrue(tenantId, idempotencyKey);
    }
    return runs.findFirstByTenantIdAndSourceDocumentIdAndContentSha256AndIdempotencyKeyIsNullAndActiveTrue(
        tenantId, sourceDocumentId, contentSha256);
  }

  private String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  @Transactional(readOnly = true)
  public DocumentTrustRunView getRunView(UUID id) {
    UUID tenantId = TenantContext.requireTenantId();
    DocumentTrustRun run = runs.findByIdAndTenantId(id, tenantId)
        .orElseThrow(() -> new NotFoundException("Document trust run not found"));
    List<DocumentTrustSignalView> signalViews = signals
        .findByTenantIdAndTrustRunIdOrderByCreatedAtAsc(tenantId, run.getId())
        .stream()
        .map(s -> new DocumentTrustSignalView(
            s.getId(),
            s.getSignalCode().name(),
            s.getSeverity().name(),
            s.getFieldKey(),
            s.getPageNumber(),
            s.getEvidenceRef(),
            s.getExplanation(),
            s.getCreatedAt()))
        .toList();
    return new DocumentTrustRunView(
        run.getId(),
        run.getSourceDocumentId(),
        run.getValidationRunId(),
        run.getRiskLevel().name(),
        run.getRiskScore(),
        run.getDecisionState(),
        run.isRequiresHumanReview(),
        run.isBlocksAutomation(),
        run.isDuplicateDetected(),
        run.getSignalCount(),
        run.getFileSizeBytes(),
        run.getPageCount(),
        run.getCreatedAt(),
        signalViews);
  }

  private List<TrustSignalSpec> computeSignals(DocumentTrustCandidate candidate, FingerprintResult fingerprint, Instant now) {
    List<TrustSignalSpec> specs = new ArrayList<>();

    if (candidate.documentDate() != null && candidate.documentDate().isAfter(now)) {
      specs.add(new TrustSignalSpec(
          TrustSignalCode.DOCUMENT_DATE_IN_FUTURE,
          TrustSignalSeverity.WARNING,
          "documentDate", null, "metadata:documentDate",
          "Document date is later than the evaluation time."));
    }

    if (candidate.issueDate() != null && candidate.dueDate() != null
        && candidate.dueDate().isBefore(candidate.issueDate())) {
      specs.add(new TrustSignalSpec(
          TrustSignalCode.DUE_DATE_BEFORE_ISSUE_DATE,
          TrustSignalSeverity.WARNING,
          "dueDate", null, "metadata:dueDate",
          "Payment due date precedes the document issue date."));
    }

    if (fingerprint.duplicate()) {
      specs.add(new TrustSignalSpec(
          TrustSignalCode.DUPLICATE_DOCUMENT_HASH,
          TrustSignalSeverity.HIGH,
          "contentHash", null, "fingerprint:" + fingerprint.fingerprintId(),
          "Identical document content hash already seen for this tenant."));
    }

    if (isMismatch(candidate.bankAccountHolderName(), candidate.expectedAccountHolderName())) {
      specs.add(new TrustSignalSpec(
          TrustSignalCode.BANK_ACCOUNT_HOLDER_MISMATCH,
          TrustSignalSeverity.HIGH,
          "bankAccountHolder", null, "metadata:bankAccountHolder",
          "Bank account holder differs from the expected counterparty."));
    }

    if (candidate.criticalFieldOcrConfidence() != null
        && candidate.criticalFieldOcrConfidence().compareTo(CRITICAL_OCR_THRESHOLD) <= 0) {
      specs.add(new TrustSignalSpec(
          TrustSignalCode.OCR_CONFIDENCE_LOW_CRITICAL_FIELD,
          TrustSignalSeverity.HIGH,
          "ocrCriticalField", null, "metadata:ocrConfidence",
          "OCR confidence on a critical field is below the acceptance threshold."));
    }

    if (candidate.declaredTotal() != null && candidate.computedLineItemsTotal() != null
        && candidate.declaredTotal().compareTo(candidate.computedLineItemsTotal()) != 0) {
      specs.add(new TrustSignalSpec(
          TrustSignalCode.DOCUMENT_TOTAL_MATH_MISMATCH,
          TrustSignalSeverity.HIGH,
          "documentTotal", null, "metadata:documentTotal",
          "Declared document total does not reconcile with the computed line-item total."));
    }

    return specs;
  }

  // Identity values are compared in memory only; they are never persisted in trust tables.
  private boolean isMismatch(String actual, String expected) {
    if (actual == null || actual.isBlank() || expected == null || expected.isBlank()) {
      return false;
    }
    return !actual.trim().equalsIgnoreCase(expected.trim());
  }

  private void recordAudit(UUID tenantId, DocumentTrustRun run) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("riskLevel", run.getRiskLevel().name());
    metadata.put("riskScore", run.getRiskScore());
    metadata.put("decisionState", run.getDecisionState());
    metadata.put("requiresHumanReview", run.isRequiresHumanReview());
    metadata.put("blocksAutomation", run.isBlocksAutomation());
    metadata.put("duplicateDetected", run.isDuplicateDetected());
    metadata.put("signalCount", run.getSignalCount());
    AuditEvent event = new AuditEvent(
        tenantId,
        null,
        "DOCUMENT_TRUST_DECISION_RECORDED",
        "DocumentTrustRun",
        run.getId().toString(),
        jsonSupport.writeObject(metadata),
        clock.instant());
    auditEvents.save(event);
  }
}
