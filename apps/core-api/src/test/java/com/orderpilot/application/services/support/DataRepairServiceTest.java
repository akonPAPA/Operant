package com.orderpilot.application.services.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.SupportInternalDtos.DataRepairDryRunRequest;
import com.orderpilot.api.dto.SupportInternalDtos.DataRepairDryRunResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import com.orderpilot.domain.support.DataRepairRequest;
import com.orderpilot.domain.support.DataRepairRequestRepository;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-51 — a data-repair request is dry-run only: it requires a reason and a bounded target type,
 * persists with {@code DRY_RUN_COMPLETED} + {@code EXECUTION_DISABLED}, emits an audit event, mutates no
 * business row, and the request contract has no arbitrary SQL/script/raw-target field.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DataRepairServiceTest {
  private static final Instant T0 = Instant.parse("2026-06-26T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

  @Autowired private DataRepairRequestRepository requestRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private ProcessingJobRepository processingJobRepository;

  private DataRepairService service;
  private UUID tenantId;

  @BeforeEach
  void setUp() {
    AuditEventService auditEventService = new AuditEventService(auditEventRepository, CLOCK);
    service = new DataRepairService(requestRepository, auditEventService, new ObjectMapper(), CLOCK);
    tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void dryRunPersistsDisabledExecutionEmitsAuditAndMutatesNoBusinessRow() {
    DataRepairDryRunResponse response = service.requestDryRun(
        tenantId, UUID.randomUUID(), "ORDER_JOURNEY", "Reconcile a stuck journey milestone");

    assertThat(response.status()).isEqualTo(DataRepairRequest.Status.DRY_RUN_COMPLETED.name());
    assertThat(response.executionStatus()).isEqualTo(DataRepairRequest.ExecutionStatus.EXECUTION_DISABLED.name());
    assertThat(response.targetType()).isEqualTo("ORDER_JOURNEY");
    assertThat(response.summary()).containsIgnoringCase("dry-run");
    assertThat(requestRepository.count()).isEqualTo(1);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("DATA_REPAIR_DRYRUN_REQUESTED");
    // No business row mutated/created by a dry-run.
    assertThat(processingJobRepository.count()).isZero();
  }

  @Test
  void missingReasonIsRejected() {
    assertThatThrownBy(() -> service.requestDryRun(tenantId, UUID.randomUUID(), "QUOTE", "  "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(requestRepository.count()).isZero();
  }

  @Test
  void unknownTargetTypeIsRejected() {
    assertThatThrownBy(() -> service.requestDryRun(tenantId, UUID.randomUUID(), "ARBITRARY_TABLE", "reason"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(requestRepository.count()).isZero();
  }

  @Test
  void dryRunRequestContractHasNoArbitrarySqlOrScriptField() {
    String[] forbidden = {"sql", "script", "query", "statement", "rawsql", "command", "table", "column"};
    for (RecordComponent component : DataRepairDryRunRequest.class.getRecordComponents()) {
      String name = component.getName().toLowerCase(Locale.ROOT);
      for (String banned : forbidden) {
        assertThat(name).as("data-repair request field %s must not accept raw SQL/script", name)
            .doesNotContain(banned);
      }
    }
  }
}
