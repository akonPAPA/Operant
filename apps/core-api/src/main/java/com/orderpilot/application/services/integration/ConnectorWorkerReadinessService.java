package com.orderpilot.application.services.integration;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.integration.ConnectorCommand;
import com.orderpilot.domain.integration.ConnectorCommandRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConnectorWorkerReadinessService {
  private final ConnectorCommandRepository commandRepository;
  private final AuditEventService auditEventService;
  private final Clock clock;

  public ConnectorWorkerReadinessService(ConnectorCommandRepository commandRepository, AuditEventService auditEventService, Clock clock) {
    this.commandRepository = commandRepository;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  @Transactional
  public List<ConnectorCommand> markExternalExecutionDisabled() {
    var commands = commandRepository.findByTenantIdAndStatusInOrderByCreatedAtAsc(TenantContext.requireTenantId(), List.of("CREATED", "READY_INTERNAL_ONLY", "EXECUTION_DISABLED"));
    for (ConnectorCommand command : commands) {
      command.markSkippedExternalDisabled("External connector execution is disabled in Stage 10F", clock.instant());
      auditEventService.record("CONNECTOR_COMMAND_SKIPPED_EXTERNAL_DISABLED", "CONNECTOR_COMMAND", command.getId().toString(), null, "{\"externalExecution\":\"DISABLED\"}");
    }
    return commandRepository.saveAll(commands);
  }
}
