package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orderpilot.api.dto.ControlLifecycleDtos.BackupRequest;
import com.orderpilot.api.dto.ControlLifecycleDtos.CompleteRequest;
import com.orderpilot.api.dto.ControlLifecycleDtos.CompletionResponse;
import com.orderpilot.api.dto.ControlLifecycleDtos.LeaseResponse;
import com.orderpilot.api.dto.ControlLifecycleDtos.OperationView;
import com.orderpilot.api.dto.ControlLifecycleDtos.StageRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Request/response boundary proofs for the bounded lifecycle control slice. */
class ControlLifecycleContractTest {
  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  void backupRequestDeclaresOnlySignedOpaqueIdempotencyIntent() throws Exception {
    BackupRequest parsed = mapper.readValue(
        "{\"idempotencyKey\":\"lifecycle-backup-attempt-001\"}", BackupRequest.class);
    assertThat(parsed.idempotencyKey()).isEqualTo("lifecycle-backup-attempt-001");
    assertThat(BackupRequest.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .containsExactly("idempotencyKey");
  }

  @Test
  void completeRequestDeclaresBoundedArtifactReportIntent() throws Exception {
    CompleteRequest parsed = mapper.readValue("""
        {
          "fencingToken":3,
          "resultCode":"BACKUP_COMPLETED",
          "artifactHandle":"ba_000000000000000000000001",
          "storageKey":"lifecycle/backup/op_abc/attempt-1/token-1/artifact.dump.enc",
          "encryptionAlgorithm":"AES-256-GCM",
          "encryptionEnvelopeVersion":"v1",
          "encryptionKeyIdentifier":"backup-key-2026-07",
          "postgresServerVersion":"PostgreSQL 16",
          "pgDumpVersion":"pg_dump 16",
          "pgRestoreVersion":"pg_restore 16",
          "schemaVersion":"V68",
          "encryptedByteSize":128,
          "ciphertextSha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "archiveValidated":true,
          "archiveEntryCount":12
        }
        """, CompleteRequest.class);
    assertThat(parsed.fencingToken()).isEqualTo(3L);
    assertThat(parsed.resultCode()).isEqualTo("BACKUP_COMPLETED");
    assertThat(parsed.artifactHandle()).isEqualTo("ba_000000000000000000000001");
    assertThat(CompleteRequest.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .containsExactlyInAnyOrder(
            "fencingToken",
            "resultCode",
            "artifactHandle",
            "storageKey",
            "encryptionAlgorithm",
            "encryptionEnvelopeVersion",
            "encryptionKeyIdentifier",
            "postgresServerVersion",
            "pgDumpVersion",
            "pgRestoreVersion",
            "schemaVersion",
            "encryptedByteSize",
            "ciphertextSha256",
            "archiveValidated",
            "archiveEntryCount");
  }

  @Test
  void stageRequestDeclaresOnlyFencingToken() throws Exception {
    StageRequest parsed = mapper.readValue("{\"fencingToken\":7}", StageRequest.class);
    assertThat(parsed.fencingToken()).isEqualTo(7L);
    assertThat(StageRequest.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .containsExactly("fencingToken");
  }

  @Test
  void requestDtosDeclareNoClientAuthorityFields() {
    assertThat(BackupRequest.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .doesNotContain(
            "operationType", "state", "principalId", "principalType", "permission",
            "requestedBy", "leasedBy", "path", "command", "database", "container", "image",
            "environment", "fencingToken", "resultCode");
    assertThat(CompleteRequest.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .doesNotContain(
            "operationType", "state", "principalId", "principalType", "permission",
            "requestedBy", "leasedBy", "path", "command", "database", "container", "image",
            "environment", "owner", "tenantId", "actorId", "status", "approvalStatus");
  }

  @Test
  void operationViewExposesOnlyBoundedOperatorSafeFields() throws Exception {
    OperationView view =
        new OperationView("op_abc", "BACKUP", "QUEUED", null, 0, Instant.EPOCH, Instant.EPOCH);
    String json = mapper.writeValueAsString(view);

    assertThat(json)
        .contains("operationId", "operationType", "state", "attempt", "createdAt", "updatedAt")
        .doesNotContain(
            "idempotencyKeyHash",
            "fingerprint",
            "requestedBy",
            "leasedBy",
            "fencingToken")
        .doesNotContain("\"id\"");
  }

  @Test
  void executorFacingResponsesCarryNoSecurityInternals() throws Exception {
    String lease = mapper.writeValueAsString(
        new LeaseResponse("op_abc", "BACKUP", 2L, Instant.EPOCH));
    String completion = mapper.writeValueAsString(
        new CompletionResponse("op_abc", "SUCCEEDED", "BACKUP_COMPLETED"));

    for (String json : new String[] {lease, completion}) {
      assertThat(json)
          .doesNotContain("idempotencyKeyHash", "fingerprint", "requestedBy", "leasedBy")
          .doesNotContain("\"id\"");
    }
    assertThat(lease).contains("fencingToken");
  }
}