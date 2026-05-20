package com.orderpilot.application.services.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.integration.ChangeRequest;
import com.orderpilot.domain.integration.ChangeRequestRepository;
import com.orderpilot.domain.integration.ConnectorCommandRepository;
import com.orderpilot.domain.integration.OutboxEventRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
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
@Import({ConnectorIdempotencyService.class, ChangeRequestService.class, AuditEventService.class, CoreConfiguration.class})
class ConnectorIdempotencyServiceTest {
  @Autowired private ConnectorIdempotencyService service;
  @Autowired private ChangeRequestService changeRequestService;
  @Autowired private ConnectorCommandRepository commandRepository;
  @Autowired private ChangeRequestRepository changeRequestRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void createsExecutionDisabledConnectorCommandFromApprovedChangeRequest() {
    TenantContext.setTenantId(UUID.randomUUID());
    ChangeRequest request = approvedChangeRequest();

    var command = service.createCommandFromApprovedChangeRequest(request.getId(), null, "DEMO_ERP", "CREATE_DRAFT_QUOTE", "{\"safe\":true}");

    assertThat(command.getChangeRequestId()).isEqualTo(request.getId());
    assertThat(command.getStatus()).isEqualTo("EXECUTION_DISABLED");
    assertThat(command.getAttemptCount()).isZero();
    assertThat(command.getMaxAttempts()).isZero();
    assertThat(command.isRetryable()).isFalse();
    assertThat(changeRequestRepository.count()).isEqualTo(1);
    assertThat(outboxEventRepository.count()).isGreaterThan(0);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("CONNECTOR_COMMAND_CREATED_EXECUTION_DISABLED");
  }

  @Test
  void duplicateIdempotencyKeyReturnsExistingCommand() {
    TenantContext.setTenantId(UUID.randomUUID());
    ChangeRequest request = approvedChangeRequest();

    var first = service.createCommandFromApprovedChangeRequest(request.getId(), null, "ONE_C", "CREATE_DRAFT_ORDER", "{}");
    var second = service.createCommandFromApprovedChangeRequest(request.getId(), null, "ONE_C", "CREATE_DRAFT_ORDER", "{\"changed\":true}");

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(commandRepository.count()).isEqualTo(1);
  }

  @Test
  void commandRequiresApprovedChangeRequest() {
    TenantContext.setTenantId(UUID.randomUUID());
    ChangeRequest request = changeRequestService.createChangeRequest("DEMO_ERP", "DRAFT_QUOTE", "EXPORT", "DRAFT_QUOTE", UUID.randomUUID(), "{}", "cr-unapproved", null);

    assertThatThrownBy(() -> service.createCommandFromApprovedChangeRequest(request.getId(), null, "DEMO_ERP", "CREATE_DRAFT_QUOTE", "{}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("approved ChangeRequest");
    assertThat(commandRepository.count()).isZero();
  }

  @Test
  void deadLetterContractUpdatesRetryFieldsWithoutExternalExecution() {
    TenantContext.setTenantId(UUID.randomUUID());
    var command = service.createCommandFromApprovedChangeRequest(approvedChangeRequest().getId(), null, "WAREHOUSE", "UPDATE_INVENTORY", "{}");
    Instant nextAttemptAt = Instant.parse("2026-05-20T01:00:00Z");

    var deadLettered = service.deadLetter(command.getId(), "validation failed before connector execution", true, nextAttemptAt);

    assertThat(deadLettered.getStatus()).isEqualTo("DEAD_LETTERED");
    assertThat(deadLettered.getAttemptCount()).isEqualTo(1);
    assertThat(deadLettered.isRetryable()).isTrue();
    assertThat(deadLettered.getNextAttemptAt()).isEqualTo(nextAttemptAt);
    assertThat(deadLettered.getLastError()).contains("validation failed");
  }

  @Test
  void idempotencyKeyIsStableForSameOperation() {
    UUID tenantId = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();

    String first = service.deriveIdempotencyKey(tenantId, "CRM", "SYNC_CUSTOMER", sourceId);
    String second = service.deriveIdempotencyKey(tenantId, "CRM", "SYNC_CUSTOMER", sourceId);

    assertThat(first).isEqualTo(second);
    assertThat(first).startsWith("connector:");
  }

  private ChangeRequest approvedChangeRequest() {
    ChangeRequest request = changeRequestService.createChangeRequest("DEMO_ERP", "DRAFT_QUOTE", "EXPORT", "DRAFT_QUOTE", UUID.randomUUID(), "{}", UUID.randomUUID().toString(), null);
    changeRequestService.validateChangeRequest(request.getId());
    return changeRequestService.approveChangeRequest(request.getId(), UUID.randomUUID());
  }
}
