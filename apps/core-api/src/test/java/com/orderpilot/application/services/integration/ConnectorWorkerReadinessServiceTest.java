package com.orderpilot.application.services.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.integration.ConnectorCommandRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ConnectorWorkerReadinessService.class, ConnectorIdempotencyService.class, ChangeRequestService.class, AuditEventService.class, CoreConfiguration.class})
class ConnectorWorkerReadinessServiceTest {
  @Autowired private ConnectorWorkerReadinessService workerReadinessService;
  @Autowired private ConnectorIdempotencyService idempotencyService;
  @Autowired private ChangeRequestService changeRequestService;
  @Autowired private ConnectorCommandRepository commandRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void disabledWorkerMarksCommandsSkippedWithoutCallingExternalSystems() {
    TenantContext.setTenantId(UUID.randomUUID());
    var request = changeRequestService.createChangeRequest("DEMO_ERP", "DRAFT_QUOTE", "EXPORT", "DRAFT_QUOTE", UUID.randomUUID(), "{}", "worker-ready-cr", null);
    changeRequestService.validateChangeRequest(request.getId());
    changeRequestService.approveChangeRequest(request.getId(), null);
    var command = idempotencyService.createCommandFromApprovedChangeRequest(request.getId(), null, "DEMO_ERP", "CREATE_DRAFT_QUOTE", "{}");

    var skipped = workerReadinessService.markExternalExecutionDisabled();

    assertThat(skipped).hasSize(1);
    assertThat(skipped.get(0).getId()).isEqualTo(command.getId());
    assertThat(skipped.get(0).getStatus()).isEqualTo("SKIPPED_EXTERNAL_DISABLED");
    assertThat(skipped.get(0).getLastError()).contains("disabled");
    assertThat(commandRepository.findById(command.getId()).orElseThrow().getStatus()).isEqualTo("SKIPPED_EXTERNAL_DISABLED");
    assertThat(auditEventRepository.findAll()).extracting("action").contains("CONNECTOR_COMMAND_SKIPPED_EXTERNAL_DISABLED");
  }
}
