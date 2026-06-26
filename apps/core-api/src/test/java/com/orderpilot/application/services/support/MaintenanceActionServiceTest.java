package com.orderpilot.application.services.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.SupportInternalDtos.MaintenanceActionRecordResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import com.orderpilot.domain.support.MaintenanceActionRecord;
import com.orderpilot.domain.support.MaintenanceActionRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-51 — a maintenance/update action can be recorded only with a reason and a known action type,
 * emits an audit event, and is record-only: it persists exactly one record row and triggers no deployment,
 * no migration, and no external call (proven here by the absence of any other side-effecting collaborator
 * and an unchanged processing-job table).
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MaintenanceActionServiceTest {
  private static final Instant T0 = Instant.parse("2026-06-26T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

  @Autowired private MaintenanceActionRecordRepository recordRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private ProcessingJobRepository processingJobRepository;

  private MaintenanceActionService service;
  private UUID tenantId;

  @BeforeEach
  void setUp() {
    AuditEventService auditEventService = new AuditEventService(auditEventRepository, CLOCK);
    service = new MaintenanceActionService(recordRepository, auditEventService, new ObjectMapper(), CLOCK);
    tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void recordWithReasonPersistsRecordedRowEmitsAuditAndHasNoExternalSideEffect() {
    MaintenanceActionRecordResponse response = service.record(
        tenantId, UUID.randomUUID(), "UPDATE_AUDIT", "Applied connector config review", null);

    assertThat(response.status()).isEqualTo(MaintenanceActionRecord.Status.RECORDED.name());
    assertThat(response.actionType()).isEqualTo("UPDATE_AUDIT");
    assertThat(response.targetScope()).isEqualTo("TENANT:" + tenantId);
    assertThat(recordRepository.count()).isEqualTo(1);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("MAINTENANCE_RECORD_CREATED");
    // Record-only: nothing in any business/runtime table was created.
    assertThat(processingJobRepository.count()).isZero();
  }

  @Test
  void missingReasonIsRejected() {
    assertThatThrownBy(() -> service.record(tenantId, UUID.randomUUID(), "UPDATE_AUDIT", "  ", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(recordRepository.count()).isZero();
  }

  @Test
  void unknownActionTypeIsRejected() {
    assertThatThrownBy(() -> service.record(tenantId, UUID.randomUUID(), "DROP_TABLE", "reason", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(recordRepository.count()).isZero();
  }
}
