package com.orderpilot.application.services.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.trust.DocumentFingerprint;
import com.orderpilot.domain.trust.DocumentFingerprintRepository;
import com.orderpilot.domain.trust.DocumentTrustCandidate;
import com.orderpilot.domain.trust.DocumentTrustRun;
import com.orderpilot.domain.trust.DocumentTrustRunRepository;
import com.orderpilot.domain.trust.DocumentTrustSignal;
import com.orderpilot.domain.trust.DocumentTrustSignalRepository;
import com.orderpilot.domain.trust.TrustRiskLevel;
import com.orderpilot.domain.trust.TrustSignalCode;
import com.orderpilot.domain.trust.TrustSignalSeverity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-17A Document Trust Signal Foundation.
 * Deterministic, tenant-scoped document trust evaluation: signals, duplicate detection, risk routing,
 * idempotent run creation, bounded evidence, numeric safety, and the no-raw-text persistence invariant.
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentTrustFoundationStage17ATest {
  private static final Instant FUTURE = Instant.parse("2099-01-01T00:00:00Z");
  private static final Instant PAST = Instant.parse("2000-01-01T00:00:00Z");

  @Autowired private DocumentTrustService trustService;
  @Autowired private DocumentTrustRunRepository runs;
  @Autowired private DocumentTrustSignalRepository signals;
  @Autowired private DocumentFingerprintRepository fingerprints;
  @Autowired private AuditEventRepository auditEvents;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  // Full builder; convenience helper below fills the common fields with safe defaults.
  private DocumentTrustCandidate cand(
      String idempotencyKey, Instant documentDate, Instant issueDate, Instant dueDate, String hashInput,
      Long fileSizeBytes, Integer pageCount, String bankHolder, String expectedHolder,
      BigDecimal ocrConfidence, BigDecimal declaredTotal, BigDecimal computedTotal) {
    return new DocumentTrustCandidate(idempotencyKey, documentDate, issueDate, dueDate, hashInput,
        fileSizeBytes, pageCount, bankHolder, expectedHolder, ocrConfidence, declaredTotal, computedTotal);
  }

  private DocumentTrustCandidate candidate(
      Instant documentDate, Instant issueDate, Instant dueDate, String hashInput,
      String bankHolder, String expectedHolder, BigDecimal ocrConfidence,
      BigDecimal declaredTotal, BigDecimal computedTotal) {
    return cand(null, documentDate, issueDate, dueDate, hashInput, 1024L, 1,
        bankHolder, expectedHolder, ocrConfidence, declaredTotal, computedTotal);
  }

  private List<TrustSignalCode> codes(UUID tenantId, UUID trustRunId) {
    return signals.findByTenantIdAndTrustRunIdOrderByCreatedAtAsc(tenantId, trustRunId)
        .stream().map(DocumentTrustSignal::getSignalCode).toList();
  }

  @Test
  void futureDocumentDateCreatesFutureSignal() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DocumentTrustRun run = trustService.evaluate(tenantId, UUID.randomUUID(), null,
        candidate(FUTURE, null, null, "hash-future", null, null, null, null, null));

    assertThat(codes(tenantId, run.getId())).contains(TrustSignalCode.DOCUMENT_DATE_IN_FUTURE);
    assertThat(run.getRiskLevel()).isEqualTo(TrustRiskLevel.MEDIUM);
  }

  @Test
  void dueDateBeforeIssueDateCreatesSignal() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DocumentTrustRun run = trustService.evaluate(tenantId, UUID.randomUUID(), null,
        candidate(null, FUTURE, PAST, "hash-dates", null, null, null, null, null));

    assertThat(codes(tenantId, run.getId())).contains(TrustSignalCode.DUE_DATE_BEFORE_ISSUE_DATE);
  }

  @Test
  void duplicateContentHashDetectedWithinSameTenant() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    trustService.evaluate(tenantId, UUID.randomUUID(), null,
        candidate(null, null, null, "same-content", null, null, null, null, null));
    DocumentTrustRun second = trustService.evaluate(tenantId, UUID.randomUUID(), null,
        candidate(null, null, null, "same-content", null, null, null, null, null));

    assertThat(second.isDuplicateDetected()).isTrue();
    assertThat(codes(tenantId, second.getId())).contains(TrustSignalCode.DUPLICATE_DOCUMENT_HASH);
    assertThat(second.getRiskLevel()).isEqualTo(TrustRiskLevel.HIGH);
  }

  @Test
  void duplicateContentHashDoesNotMatchOrLeakAcrossTenants() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    trustService.evaluate(tenantA, UUID.randomUUID(), null,
        candidate(null, null, null, "cross-tenant-content", null, null, null, null, null));

    TenantContext.setTenantId(tenantB);
    DocumentTrustRun bRun = trustService.evaluate(tenantB, UUID.randomUUID(), null,
        candidate(null, null, null, "cross-tenant-content", null, null, null, null, null));

    assertThat(bRun.isDuplicateDetected()).isFalse();
    assertThat(codes(tenantB, bRun.getId())).doesNotContain(TrustSignalCode.DUPLICATE_DOCUMENT_HASH);
    String sharedHash = sha("cross-tenant-content");
    // Each tenant only ever sees its own single fingerprint for the shared content hash.
    assertThat(fingerprints.findByTenantIdAndContentSha256(tenantA, sharedHash)).hasSize(1);
    assertThat(fingerprints.findByTenantIdAndContentSha256(tenantB, sharedHash)).hasSize(1);
  }

  @Test
  void bankAccountHolderMismatchCreatesHighSignal() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DocumentTrustRun run = trustService.evaluate(tenantId, UUID.randomUUID(), null,
        candidate(null, null, null, "hash-bank", "Beta LLC", "Acme Trading Co", null, null, null));

    DocumentTrustSignal signal = signals.findByTenantIdAndTrustRunIdOrderByCreatedAtAsc(tenantId, run.getId())
        .stream().filter(s -> s.getSignalCode() == TrustSignalCode.BANK_ACCOUNT_HOLDER_MISMATCH)
        .findFirst().orElseThrow();
    assertThat(signal.getSeverity()).isEqualTo(TrustSignalSeverity.HIGH);
    assertThat(run.getRiskLevel()).isEqualTo(TrustRiskLevel.HIGH);
  }

  @Test
  void mediumRiskContinuesWithWarningState() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DocumentTrustRun run = trustService.evaluate(tenantId, UUID.randomUUID(), null,
        candidate(FUTURE, null, null, "hash-warn", null, null, null, null, null));

    assertThat(run.getRiskLevel()).isIn(TrustRiskLevel.LOW, TrustRiskLevel.MEDIUM);
    assertThat(run.getDecisionState()).isEqualTo("CONTINUE_WITH_WARNING");
    assertThat(run.isRequiresHumanReview()).isFalse();
    assertThat(run.isBlocksAutomation()).isFalse();
  }

  @Test
  void noMaterialSignalsIsLowRiskWithZeroScore() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DocumentTrustRun run = trustService.evaluate(tenantId, UUID.randomUUID(), null,
        candidate(PAST, PAST, FUTURE, "hash-clean", "Acme", "Acme", new BigDecimal("0.99"),
            new BigDecimal("100.00"), new BigDecimal("100.00")));

    assertThat(run.getRiskLevel()).isEqualTo(TrustRiskLevel.LOW);
    assertThat(run.getSignalCount()).isZero();
    assertThat(run.getRiskScore()).isZero();
    assertThat(run.getDecisionState()).isEqualTo("CONTINUE_WITH_WARNING");
  }

  @Test
  void highRiskRequiresHumanReviewFlag() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DocumentTrustRun run = trustService.evaluate(tenantId, UUID.randomUUID(), null,
        candidate(null, null, null, "hash-high", "Beta", "Acme", null, null, null));

    assertThat(run.getRiskLevel()).isEqualTo(TrustRiskLevel.HIGH);
    assertThat(run.isRequiresHumanReview()).isTrue();
    assertThat(run.isBlocksAutomation()).isFalse();
    assertThat(run.getDecisionState()).isEqualTo("REQUIRES_REVIEW");
  }

  @Test
  void criticalRiskBlocksAutomationFlag() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // Duplicate document hash + bank account holder mismatch is a forced-critical combination.
    trustService.evaluate(tenantId, UUID.randomUUID(), null,
        candidate(null, null, null, "critical-content", null, null, null, null, null));
    DocumentTrustRun run = trustService.evaluate(tenantId, UUID.randomUUID(), null,
        candidate(null, null, null, "critical-content", "Beta", "Acme", null, null, null));

    assertThat(run.getRiskLevel()).isEqualTo(TrustRiskLevel.CRITICAL);
    assertThat(run.isBlocksAutomation()).isTrue();
    assertThat(run.isRequiresHumanReview()).isTrue();
    assertThat(run.getDecisionState()).isEqualTo("BLOCK_AUTOMATION");
  }

  @Test
  void evaluationIsIdempotentForSameSourceDocumentAndContent() {
    UUID tenantId = UUID.randomUUID();
    UUID sourceDocumentId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);

    DocumentTrustRun first = trustService.evaluate(tenantId, sourceDocumentId, null,
        candidate(FUTURE, null, null, "idem-content", null, null, null, null, null));
    DocumentTrustRun second = trustService.evaluate(tenantId, sourceDocumentId, null,
        candidate(FUTURE, null, null, "idem-content", null, null, null, null, null));

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(runs.findByTenantIdAndSourceDocumentIdOrderByCreatedAtDesc(tenantId, sourceDocumentId)).hasSize(1);
    // The second call must not create a second fingerprint or duplicate the run's signals.
    assertThat(fingerprints.findByTenantIdAndContentSha256(tenantId, sha("idem-content"))).hasSize(1);
    assertThat(signals.findByTenantIdAndTrustRunIdOrderByCreatedAtAsc(tenantId, first.getId())).hasSize(1);
  }

  @Test
  void idempotencyKeyCollapsesRepeatEvaluations() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    String key = "ext-idem-key-001";

    DocumentTrustRun first = trustService.evaluate(tenantId, UUID.randomUUID(), null,
        cand(key, FUTURE, null, null, "key-content", 1024L, 1, null, null, null, null, null));
    DocumentTrustRun second = trustService.evaluate(tenantId, UUID.randomUUID(), null,
        cand(key, FUTURE, null, null, "key-content", 1024L, 1, null, null, null, null, null));

    assertThat(second.getId()).isEqualTo(first.getId());
  }

  @Test
  void riskScoreIsClampedToUpperBound() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    // Seed identical content for a different source document so the duplicate signal fires.
    trustService.evaluate(tenantId, UUID.randomUUID(), null,
        candidate(null, null, null, "clamp-content", null, null, null, null, null));
    // Four HIGH signals (duplicate + bank mismatch + low OCR + total mismatch) would exceed 100.
    DocumentTrustRun run = trustService.evaluate(tenantId, UUID.randomUUID(), null,
        candidate(null, null, null, "clamp-content", "Beta", "Acme", new BigDecimal("0.10"),
            new BigDecimal("100.00"), new BigDecimal("90.00")));

    assertThat(run.getSignalCount()).isGreaterThanOrEqualTo(4);
    assertThat(run.getRiskScore()).isBetween(0, 100);
    assertThat(run.getRiskScore()).isEqualTo(100);
  }

  @Test
  void signalsCarryBoundedEvidenceMetadata() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DocumentTrustRun run = trustService.evaluate(tenantId, UUID.randomUUID(), null,
        candidate(null, null, null, "evidence-hash", "Beta", "Acme", null, null, null));

    DocumentTrustSignal signal = signals.findByTenantIdAndTrustRunIdOrderByCreatedAtAsc(tenantId, run.getId())
        .stream().filter(s -> s.getSignalCode() == TrustSignalCode.BANK_ACCOUNT_HOLDER_MISMATCH)
        .findFirst().orElseThrow();
    assertThat(signal.getFieldKey()).isEqualTo("bankAccountHolder");
    assertThat(signal.getEvidenceRef()).isNotBlank().hasSizeLessThanOrEqualTo(120);
    assertThat(signal.getExplanation()).isNotBlank().hasSizeLessThanOrEqualTo(280);
  }

  @Test
  void largeFileSizeAndPageCountMetadataPersist() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    long largeSize = 5_000_000_000L; // beyond Integer.MAX_VALUE
    int largePageCount = 1500;
    DocumentTrustRun run = trustService.evaluate(tenantId, UUID.randomUUID(), null,
        cand(null, FUTURE, null, null, "big-doc", largeSize, largePageCount, null, null, null, null, null));

    assertThat(run.getFileSizeBytes()).isEqualTo(largeSize);
    assertThat(run.getPageCount()).isEqualTo(largePageCount);
    assertThat(runs.findByIdAndTenantId(run.getId(), tenantId).orElseThrow().getFileSizeBytes()).isEqualTo(largeSize);
  }

  @Test
  void highOrCriticalDecisionEmitsAuditAndMediumDoesNot() {
    UUID highTenant = UUID.randomUUID();
    TenantContext.setTenantId(highTenant);
    DocumentTrustRun highRun = trustService.evaluate(highTenant, UUID.randomUUID(), null,
        candidate(null, null, null, "audit-high", "Beta", "Acme", null, null, null));
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(highTenant))
        .extracting("action")
        .contains("DOCUMENT_TRUST_DECISION_RECORDED");
    assertThat(highRun.getRiskLevel()).isEqualTo(TrustRiskLevel.HIGH);

    UUID mediumTenant = UUID.randomUUID();
    TenantContext.setTenantId(mediumTenant);
    trustService.evaluate(mediumTenant, UUID.randomUUID(), null,
        candidate(FUTURE, null, null, "audit-medium", null, null, null, null, null));
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(mediumTenant)).isEmpty();
  }

  @Test
  void noRawTextIsPersistedInTrustTables() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    String secretContent = "SECRET-RAW-DOCUMENT-BODY-12345";
    String bankHolder = "CONFIDENTIAL-HOLDER-NAME";
    // Future date + bank mismatch => HIGH, so an audit event is also produced and checked.
    DocumentTrustRun run = trustService.evaluate(tenantId, UUID.randomUUID(), null,
        candidate(FUTURE, null, null, secretContent, bankHolder, "Acme", null, null, null));

    // Fingerprint stores only the hash, never the raw canonical input.
    DocumentFingerprint fp = fingerprints.findByIdAndTenantId(run.getFingerprintId(), tenantId).orElseThrow();
    assertThat(fp.getContentSha256()).isNotEqualTo(secretContent).doesNotContain(secretContent);

    // Signal evidence is generic/bounded only — no raw content or identity values.
    for (DocumentTrustSignal signal : signals.findByTenantIdAndTrustRunIdOrderByCreatedAtAsc(tenantId, run.getId())) {
      assertThat(signal.getExplanation()).doesNotContain(secretContent).doesNotContain(bankHolder);
      assertThat(signal.getEvidenceRef()).doesNotContain(secretContent).doesNotContain(bankHolder);
      assertThat(signal.getFieldKey()).doesNotContain(secretContent).doesNotContain(bankHolder);
    }

    // Audit metadata carries bounded tokens only, no raw content.
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId))
        .isNotEmpty()
        .extracting("metadata")
        .noneMatch(m -> ((String) m).contains(secretContent) || ((String) m).contains(bankHolder));
  }

  @Test
  void readRunIsTenantScoped() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    DocumentTrustRun run = trustService.evaluate(tenantA, UUID.randomUUID(), null,
        candidate(FUTURE, null, null, "hash-scoped", null, null, null, null, null));

    // Same tenant can read.
    assertThat(trustService.getRunView(run.getId()).id()).isEqualTo(run.getId());

    // A different tenant cannot read another tenant's run.
    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> trustService.getRunView(run.getId()))
        .isInstanceOf(NotFoundException.class);
  }

  private static String sha(String input) {
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16));
        hex.append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
