package com.orderpilot.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.orderpilot.api.dto.ControlLifecycleDtos.BackupRequest;
import com.orderpilot.api.dto.ControlLifecycleDtos.CompleteRequest;
import com.orderpilot.api.dto.ControlLifecycleDtos.CompletionResponse;
import com.orderpilot.api.dto.ControlLifecycleDtos.LeaseResponse;
import com.orderpilot.api.dto.ControlLifecycleDtos.OperationView;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * P1-E2A - request/response contract proofs for the backup control slice.
 *
 * <p>The request DTOs declare no authority fields, so - under the app-wide Jackson contract that unknown
 * properties are ignored - a client cannot smuggle operation state, executor identity, a fencing token
 * onto a staff route, or a path/command/database/container: any such value in the body is ignored and
 * never reaches the service. The response DTOs never carry a persistence entity, internal id,
 * idempotency-key hash, principal fingerprint, or any security internal.
 */
class ControlLifecycleContractTest {
  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  void backupRequestDeclaresNoClientAuthorityField() throws Exception {
    // An empty body is valid, and - config-independently - the record has ZERO components: there is
    // structurally no field for a client-supplied state/executor/fencing/path/command value to land in,
    // so none can ever reach the service regardless of whether the mapper ignores or rejects extras.
    assertThat(mapper.readValue("{}", BackupRequest.class)).isNotNull();
    assertThat(BackupRequest.class.getRecordComponents()).isEmpty();
  }

  @Test
  void completeRequestDeclaresOnlyFencingTokenAndResultCode() throws Exception {
    CompleteRequest parsed = mapper.readValue(
        "{\"fencingToken\":3,\"resultCode\":\"BACKUP_COMPLETED\"}", CompleteRequest.class);
    assertThat(parsed.fencingToken()).isEqualTo(3L);
    assertThat(parsed.resultCode()).isEqualTo("BACKUP_COMPLETED");

    // The only declared components are the two bounded executor fields; there is no state, operationId,
    // executor identity, path, or command field a client could populate.
    assertThat(CompleteRequest.class.getRecordComponents())
        .extracting(java.lang.reflect.RecordComponent::getName)
        .containsExactlyInAnyOrder("fencingToken", "resultCode");
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
    // The lease legitimately carries the fencing token (the executor needs it to complete).
    assertThat(lease).contains("fencingToken");
  }
}
