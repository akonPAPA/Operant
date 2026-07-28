package com.orderpilot.application.services.control.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.domain.control.BackupArtifact;
import com.orderpilot.domain.control.BackupArtifactState;
import com.orderpilot.domain.control.LifecycleOperation;
import com.orderpilot.domain.control.LifecycleOperationAudit;
import com.orderpilot.domain.control.LifecycleOperationAuditEventType;
import com.orderpilot.domain.control.LifecycleOperationAuditPrincipalType;
import com.orderpilot.domain.control.LifecycleOperationAuditRepository;
import com.orderpilot.domain.control.LifecycleOperationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

/**
 * Locally-runnable SYSTEM orphan identity proof (GAP 3) for
 * {@link LifecycleOperationAuditor#artifactOrphaned}. The internal STAGED -&gt; ORPHANED re-lease
 * transition is authored by the fixed backend system releaser, never by the re-leasing executor; the
 * trigger executor survives only as bounded, injection-proof metadata. This does not depend on the
 * PostgreSQL-gated integration test.
 *
 * <p><b>What the string validator does and does NOT prove (GAP 5, honest framing).</b> The auditor embeds
 * {@code triggerExecutorFingerprint} into metadata JSON only after it passes the bounded
 * {@link #FINGERPRINT_CONTRACT} contract: it must be non-blank, at most {@link #FINGERPRINT_MAX_LENGTH}
 * characters, and drawn from {@code [0-9A-Za-z:_-]} — i.e. no whitespace, no control characters, and no
 * quote/backslash/JSON-breaking characters. Rejected inputs are rejected because they violate that
 * <em>shape</em> contract (a {@code '='}, a space, a quote, a control char, or excess length falls outside
 * the charset/bound), NOT because the validator "detects a secret". A string validator cannot prove a
 * value is not a credential; the semantic guarantee that this field carries a fingerprint derived from an
 * authenticated executor identity — and never a raw credential — comes from the trusted upstream
 * identity/credential resolver, not from this regex. What this test proves is exactly the format contract:
 * bounded, canonical, fingerprint-shaped, JSON-safe, and never promoted to the SYSTEM principal authority.
 */
class LifecycleOperationAuditorOrphanIdentityTest {
  private static final String HANDLE = "ba_000000000000000000000001";
  private static final String FAILURE_CODE = "EXPIRED_LEASE_REPLACED";

  /**
   * The explicit fingerprint / bounded-metadata-value contract enforced by the auditor
   * ({@code LifecycleOperationAuditor.SAFE_METADATA_VALUE}). Kept here as an independent, asserted mirror
   * so a future weakening of the production charset/bound is caught by this proof.
   */
  private static final Pattern FINGERPRINT_CONTRACT = Pattern.compile("[0-9A-Za-z:_-]{1,80}");
  private static final int FINGERPRINT_MAX_LENGTH = 80;

  private static final String CANONICAL_EXECUTOR_FINGERPRINT = "executor-fingerprint-2";

  private final Logger logger = mock(Logger.class);
  private final LifecycleOperationAuditRepository auditRepository =
      mock(LifecycleOperationAuditRepository.class);
  private final LifecycleOperationRepository operationRepository =
      mock(LifecycleOperationRepository.class);
  private final LifecycleOperationAuditor auditor = new LifecycleOperationAuditor(
      logger,
      auditRepository,
      operationRepository,
      null,
      Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC));

  @Test
  void canonicalExecutorFingerprintIsAcceptedAsBoundedMetadataAndPrincipalStaysSystem() throws Exception {
    // The accepted value satisfies the explicit fingerprint contract (bounded, canonical, fingerprint-
    // shaped, whitespace/control/quote free).
    assertThat(CANONICAL_EXECUTOR_FINGERPRINT).matches(FINGERPRINT_CONTRACT);
    assertThat(CANONICAL_EXECUTOR_FINGERPRINT.length()).isLessThanOrEqualTo(FINGERPRINT_MAX_LENGTH);

    ArgumentCaptor<LifecycleOperationAudit> captor =
        ArgumentCaptor.forClass(LifecycleOperationAudit.class);

    auditor.artifactOrphaned(operation(), artifact(), CANONICAL_EXECUTOR_FINGERPRINT, FAILURE_CODE);

    verify(auditRepository).save(captor.capture());
    LifecycleOperationAudit saved = captor.getValue();
    assertThat(saved.getEventType()).isEqualTo(LifecycleOperationAuditEventType.BACKUP_ARTIFACT_ORPHANED);
    // principal identity is SYSTEM, fingerprint is the fixed backend releaser — never the executor.
    assertThat(saved.getPrincipalType()).isEqualTo(LifecycleOperationAuditPrincipalType.SYSTEM);
    assertThat(saved.getPrincipalFingerprint())
        .isEqualTo(LifecycleOperationAuditor.SYSTEM_RELEASER_FINGERPRINT)
        .isEqualTo("system:lifecycle-releaser");
    // the trigger executor appears ONLY as bounded metadata, not as the principal fingerprint.
    assertThat(saved.getMetadata())
        .contains("\"triggerExecutorFingerprint\":\"" + CANONICAL_EXECUTOR_FINGERPRINT + "\"")
        .contains("\"artifactHandle\":\"" + HANDLE + "\"");
    assertThat(saved.getPrincipalFingerprint()).isNotEqualTo(CANONICAL_EXECUTOR_FINGERPRINT);

    // the emitted metadata is well-formed JSON (the bounded charset guarantees safe direct quoting).
    assertThatCode(() -> new ObjectMapper().readTree(saved.getMetadata())).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "exec\"quote",                 // double quote — outside the charset, would break JSON quoting
      "exec\\backslash",             // backslash — outside the charset, JSON escape hazard
      "exec\ttab",                   // tab — control character, outside the charset
      "exec newline\ninjection",     // newline — control character / log-injection shape
      "password=topsecret",          // '=' is outside [0-9A-Za-z:_-]; rejected on shape, not on meaning
      "AKIA SECRET CREDENTIAL",      // spaces are outside the charset; rejected on shape, not on meaning
      "12345678901234567890123456789012345678901234567890123456789012345678901234567890x" // 81 chars > 80
  })
  void fingerprintViolatingTheBoundedContractProducesNoAuditAndNoMutation(String triggerExecutor) {
    // Each of these is rejected because it violates the explicit fingerprint SHAPE contract — a character
    // outside [0-9A-Za-z:_-] (quote, backslash, control char, space, '=') or length > 80 — NOT because the
    // validator recognises a secret. The independent mirror confirms each input fails the contract.
    assertThat(FINGERPRINT_CONTRACT.matcher(triggerExecutor).matches()).isFalse();

    assertThatThrownBy(
            () -> auditor.artifactOrphaned(operation(), artifact(), triggerExecutor, FAILURE_CODE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("AUDIT_METADATA_VALUE_INVALID");

    // fail-closed: nothing is persisted, so no operation/artifact/audit mutation can occur.
    verify(auditRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "   "})
  void blankFingerprintIsRejectedFailClosedWithNoAudit(String blank) {
    assertThat(FINGERPRINT_CONTRACT.matcher(blank).matches()).isFalse();

    assertThatThrownBy(() -> auditor.artifactOrphaned(operation(), artifact(), blank, FAILURE_CODE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("AUDIT_METADATA_VALUE_INVALID");

    verify(auditRepository, never()).save(org.mockito.ArgumentMatchers.any());
  }

  private static LifecycleOperation operation() {
    LifecycleOperation operation = mock(LifecycleOperation.class);
    when(operation.getPublicId()).thenReturn("op_00000000000000000000000001");
    return operation;
  }

  private static BackupArtifact artifact() {
    BackupArtifact artifact = mock(BackupArtifact.class);
    when(artifact.getPublicHandle()).thenReturn(HANDLE);
    when(artifact.getState()).thenReturn(BackupArtifactState.ORPHANED);
    return artifact;
  }
}
