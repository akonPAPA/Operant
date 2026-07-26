package com.orderpilot.api.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.ControlLifecycleDtos.ControlLifecycleError;
import com.orderpilot.application.services.control.lifecycle.BackupArtifactPersistenceService;
import com.orderpilot.application.services.control.lifecycle.BackupArtifactPersistenceService.FinalizeReportCommand;
import com.orderpilot.application.services.control.lifecycle.LifecycleBackupOperationService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import java.lang.reflect.Method;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Boundary-mapping proof for the lifecycle control surface exception contract (GAP 1). The primary
 * behavioural proof drives the <em>real</em> Spring exception-resolution chain via MockMvc with the
 * production {@link GlobalExceptionHandler} registered as controller advice:
 *
 * <ul>
 *   <li><b>Scenario A (controller-boundary rejection):</b> a valid request envelope carrying a malformed
 *       provenance banner is rejected by {@code PostgresToolVersionNormalizer} <em>inside the controller,
 *       before any service call</em>, and resolves to a bounded {@code 400 INVALID_BACKUP_ARTIFACT_REPORT}
 *       that never leaks the raw banner. Both collaborating services are proven untouched
 *       ({@code verifyNoInteractions}), so no lifecycle/artifact/audit mutation is even attempted.
 *   <li><b>Scenario B (unexpected service defect):</b> a valid, canonical request reaches
 *       {@code artifactService.completeReport(...)} exactly once; the service then throws
 *       {@link NullPointerException}, which — being a programming defect, not a client report — falls
 *       through to the redacted global {@code 500 INTERNAL_ERROR "Unexpected server error"}. Here we
 *       positively {@code verify} the invoked method rather than claim {@code verifyNoInteractions}.
 * </ul>
 *
 * <p><b>Scope of this proof.</b> This is a MockMvc test: it proves HTTP status / error-code / redaction
 * mapping ONLY. It deliberately does NOT and CANNOT prove that a real service which mutates and then
 * throws would roll back, because the collaborators are mocks.
 *
 * <p><b>What the PostgreSQL suite actually proves (honest boundary):</b>
 * {@code BackupArtifactAuthorityPostgresIntegrationTest} proves rollback for the SELECTED
 * audit-persistence failure scenarios only — where an injected auditor failure aborts the surrounding
 * transaction (e.g. {@code artifactAvailableAuditFailureRollsBackAvailableAndSucceeded},
 * {@code operationSuccessAuditFailureRollsBackAvailableTransition}). Status:
 * {@code SELECTED_AUDIT_FAILURE_ROLLBACK_TEST_IMPLEMENTED}. Those tests are PostgreSQL/Docker-gated, so
 * {@code POSTGRESQL_RUNTIME_NOT_PROVEN} locally when Docker is unavailable.
 *
 * <p>They do NOT prove that every arbitrary unexpected failure (e.g. a {@link NullPointerException} at an
 * arbitrary service location) rolls back at every possible service location:
 * {@code ARBITRARY_UNEXPECTED_SERVICE_FAILURE_ROLLBACK_NOT_GENERALLY_PROVEN}. Scenario B below proves
 * ONLY that such an NPE maps to the redacted 500 contract — not that a mutating service rolled back.
 *
 * <p>The reflection assertion below is a secondary guard, not the behavioural proof.
 */
class InternalControlLifecycleControllerExceptionMappingTest {
  private static final String COMPLETE_URL =
      "/api/v1/internal/control/lifecycle/operations/op_test/complete";

  // A deliberately sensitive raw message: it must never appear in any client-facing response body.
  private static final String LEAKY_MESSAGE =
      "ciphertextSha256_INVALID host=/var/lib/postgresql/data secret=hunter2 "
          + "credential=AKIA123 SELECT * FROM users; at com.orderpilot.domain.control.BackupArtifact";

  private final LifecycleBackupOperationService lifecycleService =
      mock(LifecycleBackupOperationService.class);
  private final BackupArtifactPersistenceService artifactService =
      mock(BackupArtifactPersistenceService.class);
  private final InternalControlLifecycleController controller =
      new InternalControlLifecycleController(lifecycleService, artifactService);
  private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
      .setControllerAdvice(new GlobalExceptionHandler(Clock.systemUTC()))
      .build();

  @BeforeEach
  void resetMocks() {
    reset(lifecycleService, artifactService);
  }

  @Test
  void scenarioA_malformedProvenanceRejectedAtControllerBoundaryBeforeAnyServiceCall() throws Exception {
    // Scenario A: a valid authenticated request envelope carrying a malformed provenance banner ("v16")
    // is rejected by the authoritative boundary normalizer (IllegalArgumentException) INSIDE the
    // controller, before completeReport is ever called, and resolved by the controller-local advice.
    mockMvc.perform(post(COMPLETE_URL)
            .contentType("application/json")
            .content(completeBody("v16")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_BACKUP_ARTIFACT_REPORT"));

    // The service is never reached, so the lifecycle operation, artifact, audit, outbox, connector, and
    // storage/process side effects cannot occur on this failure path. Both collaborators are untouched.
    verifyNoInteractions(artifactService);
    verifyNoInteractions(lifecycleService);
  }

  @Test
  void serviceIllegalArgumentIsBoundedBadRequestWithNoRawMessageOrSecretLeak() throws Exception {
    when(artifactService.completeReport(any(FinalizeReportCommand.class)))
        .thenThrow(new IllegalArgumentException(LEAKY_MESSAGE));

    String body = mockMvc.perform(post(COMPLETE_URL)
            .contentType("application/json")
            .content(completeBody("16.4")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_BACKUP_ARTIFACT_REPORT"))
        .andReturn()
        .getResponse()
        .getContentAsString();

    assertNoSensitiveLeak(body);
    // The artifact-persistence service WAS invoked exactly once and threw; we positively verify the
    // invoked method rather than claim it was untouched. lifecycleService is a distinct bean that the
    // complete() path never calls (lease/request live there), so it remains genuinely untouched.
    verify(artifactService).completeReport(any(FinalizeReportCommand.class));
    verifyNoInteractions(lifecycleService);
  }

  @Test
  void scenarioB_unexpectedNullPointerFromServiceFallsThroughToRedactedInternalError() throws Exception {
    // Scenario B: a valid canonical request reaches the service exactly once; the service then throws an
    // unexpected NPE (a programming defect), which falls through to the redacted global 500 rather than
    // being disguised as a client report.
    when(artifactService.completeReport(any(FinalizeReportCommand.class)))
        .thenThrow(new NullPointerException(
            "Cannot invoke \"BackupArtifact.getState()\" because \"artifact\" is null "
                + "at /var/lib/postgresql SELECT 1 password=hunter2"));

    String body = mockMvc.perform(post(COMPLETE_URL)
            .contentType("application/json")
            .content(completeBody("16.4")))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.message").value("Unexpected server error"))
        .andReturn()
        .getResponse()
        .getContentAsString();

    assertNoSensitiveLeak(body);
    assertThat(body).doesNotContain("NullPointerException");
    // The invoked method is completeReport on the artifact service — positively verified as reached once.
    // This proves HTTP/error mapping ONLY; it does NOT prove the mutating service rolled back (mocks do
    // not mutate). Rollback for SELECTED audit-failure scenarios is proven by
    // BackupArtifactAuthorityPostgresIntegrationTest (PostgreSQL/Docker-gated; POSTGRESQL_RUNTIME_NOT_PROVEN
    // locally without Docker). Arbitrary unexpected service-failure rollback at every location is
    // ARBITRARY_UNEXPECTED_SERVICE_FAILURE_ROLLBACK_NOT_GENERALLY_PROVEN — see the class Javadoc.
    verify(artifactService).completeReport(any(FinalizeReportCommand.class));
  }

  @Test
  void noExceptionHandlerCapturesNullPointerOrBroadRuntimeException() {
    // Secondary guard: the controller must not declare a handler that would swallow NPE / RuntimeException
    // into a client-facing response instead of the redacted global 500.
    for (Method method : InternalControlLifecycleController.class.getDeclaredMethods()) {
      ExceptionHandler handler = method.getAnnotation(ExceptionHandler.class);
      if (handler == null) {
        continue;
      }
      assertThat(handler.value())
          .as("lifecycle controller must not swallow NPE/RuntimeException into a client-facing response")
          .doesNotContain(NullPointerException.class)
          .doesNotContain(RuntimeException.class);
    }
  }

  @Test
  void illegalArgumentDirectHandlerMapsToBoundedBadRequestWithoutLeakingMessage() {
    ResponseEntity<ControlLifecycleError> response =
        controller.handleInvalidRuntimeContract(new IllegalArgumentException("secret-internal-detail"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("INVALID_BACKUP_ARTIFACT_REPORT");
    assertThat(response.getBody().code()).doesNotContain("secret-internal-detail");
  }

  private static void assertNoSensitiveLeak(String body) {
    assertThat(body)
        .doesNotContain("hunter2")
        .doesNotContain("secret=")
        .doesNotContain("credential=")
        .doesNotContain("password=")
        .doesNotContain("AKIA123")
        .doesNotContain("SELECT")
        .doesNotContain("/var/lib/postgresql")
        .doesNotContain("ciphertextSha256_INVALID")
        .doesNotContain("com.orderpilot.domain.control.BackupArtifact")
        .doesNotContain("at com.orderpilot")
        .doesNotContain("IllegalArgumentException");
  }

  private static String completeBody(String versions) {
    return """
        {
          "fencingToken": 3,
          "resultCode": "BACKUP_COMPLETED",
          "artifactHandle": "ba_000000000000000000000001",
          "storageKey": "lifecycle/backup/op_test/attempt-1/token-1/artifact.dump.enc",
          "encryptionAlgorithm": "AES-256-GCM",
          "encryptionEnvelopeVersion": "v1",
          "encryptionKeyIdentifier": "backup-key-2026-07",
          "postgresServerVersion": "%s",
          "pgDumpVersion": "%s",
          "pgRestoreVersion": "%s",
          "schemaVersion": "V68",
          "encryptedByteSize": 128,
          "ciphertextSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "archiveValidated": true,
          "archiveEntryCount": 12
        }
        """.formatted(versions, versions, versions);
  }
}
