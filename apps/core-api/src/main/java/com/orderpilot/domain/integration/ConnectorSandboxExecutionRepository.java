package com.orderpilot.domain.integration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorSandboxExecutionRepository extends JpaRepository<ConnectorSandboxExecution, UUID> {
  Optional<ConnectorSandboxExecution> findByIdAndTenantId(UUID id, UUID tenantId);
  Optional<ConnectorSandboxExecution> findByTenantIdAndDryRunKey(UUID tenantId, String dryRunKey);
  List<ConnectorSandboxExecution> findByTenantIdAndConnectorCommandIdOrderByCreatedAtDesc(UUID tenantId, UUID connectorCommandId);
  List<ConnectorSandboxExecution> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, ConnectorSandboxExecutionStatus status);
}
