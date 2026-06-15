package com.orderpilot.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({IdempotencyService.class, ObjectMapper.class, CoreConfiguration.class})
class IdempotencyServiceTest {
  @Autowired private IdempotencyService service;
  @Autowired private IdempotencyRecordRepository records;

  @Test
  void sameKeyAndSamePayloadReplaysStoredResponseWithoutRepeatingSideEffect() {
    UUID tenant = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    AtomicInteger executions = new AtomicInteger();

    TestResponse first = service.execute(
        tenant, actor, "idem-1", "QUOTE_APPROVE", "DRAFT_QUOTE", "quote-1",
        Map.of("decision", "APPROVE"), TestResponse.class,
        () -> new TestResponse("ok-" + executions.incrementAndGet()));
    TestResponse replay = service.execute(
        tenant, actor, "idem-1", "QUOTE_APPROVE", "DRAFT_QUOTE", "quote-1",
        Map.of("decision", "APPROVE"), TestResponse.class,
        () -> new TestResponse("ok-" + executions.incrementAndGet()));

    assertThat(first.value()).isEqualTo("ok-1");
    assertThat(replay.value()).isEqualTo("ok-1");
    assertThat(executions).hasValue(1);
    assertThat(records.findAll()).hasSize(1);
  }

  @Test
  void sameKeyWithDifferentPayloadIsConflict() {
    UUID tenant = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    service.execute(
        tenant, actor, "idem-2", "QUOTE_REJECT", "DRAFT_QUOTE", "quote-1",
        Map.of("reason", "first"), TestResponse.class,
        () -> new TestResponse("rejected"));

    assertThatThrownBy(() -> service.execute(
        tenant, actor, "idem-2", "QUOTE_REJECT", "DRAFT_QUOTE", "quote-1",
        Map.of("reason", "second"), TestResponse.class,
        () -> new TestResponse("should-not-run")))
        .isInstanceOf(IdempotencyConflictException.class)
        .hasMessageContaining("different request");
  }

  @Test
  void sameKeyWithDifferentActorIsConflictAndDoesNotExposeResponse() {
    UUID tenant = UUID.randomUUID();
    UUID actor = UUID.randomUUID();
    service.execute(
        tenant, actor, "idem-3", "QUOTE_APPROVE", "DRAFT_QUOTE", "quote-1",
        Map.of("decision", "APPROVE"), TestResponse.class,
        () -> new TestResponse("private-result"));

    assertThatThrownBy(() -> service.execute(
        tenant, UUID.randomUUID(), "idem-3", "QUOTE_APPROVE", "DRAFT_QUOTE", "quote-1",
        Map.of("decision", "APPROVE"), TestResponse.class,
        () -> new TestResponse("should-not-run")))
        .isInstanceOf(IdempotencyConflictException.class)
        .hasMessageContaining("actor context");
  }

  @Test
  void sameKeyInDifferentTenantIsIndependent() {
    UUID actor = UUID.randomUUID();

    TestResponse tenantA = service.execute(
        UUID.randomUUID(), actor, "shared-key", "QUOTE_APPROVE", "DRAFT_QUOTE", "quote-1",
        Map.of("decision", "APPROVE"), TestResponse.class,
        () -> new TestResponse("tenant-a"));
    TestResponse tenantB = service.execute(
        UUID.randomUUID(), actor, "shared-key", "QUOTE_APPROVE", "DRAFT_QUOTE", "quote-1",
        Map.of("decision", "APPROVE"), TestResponse.class,
        () -> new TestResponse("tenant-b"));

    assertThat(tenantA.value()).isEqualTo("tenant-a");
    assertThat(tenantB.value()).isEqualTo("tenant-b");
    assertThat(records.findAll()).hasSize(2);
  }

  @Test
  void requestHashIgnoresIdempotencyMetadataButIncludesBusinessPayloadAndTarget() {
    UUID tenant = UUID.randomUUID();
    UUID actor = UUID.randomUUID();

    String first = service.requestHash(tenant, actor, "QUOTE_REVIEW_LINE_CORRECT", "DRAFT_QUOTE", "quote-1:line-1",
        Map.of("quantity", 2, "idempotencyKey", "a", "correlationId", "ignored"));
    String metadataChanged = service.requestHash(tenant, actor, "QUOTE_REVIEW_LINE_CORRECT", "DRAFT_QUOTE", "quote-1:line-1",
        Map.of("quantity", 2, "idempotencyKey", "b", "correlationId", "also-ignored"));
    String businessChanged = service.requestHash(tenant, actor, "QUOTE_REVIEW_LINE_CORRECT", "DRAFT_QUOTE", "quote-1:line-1",
        Map.of("quantity", 3, "idempotencyKey", "a"));
    String targetChanged = service.requestHash(tenant, actor, "QUOTE_REVIEW_LINE_CORRECT", "DRAFT_QUOTE", "quote-1:line-2",
        Map.of("quantity", 2, "idempotencyKey", "a"));

    assertThat(metadataChanged).isEqualTo(first);
    assertThat(businessChanged).isNotEqualTo(first);
    assertThat(targetChanged).isNotEqualTo(first);
  }

  record TestResponse(String value) {}
}
