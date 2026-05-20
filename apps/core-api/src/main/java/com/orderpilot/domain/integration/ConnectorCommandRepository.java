package com.orderpilot.domain.integration;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorCommandRepository extends JpaRepository<ConnectorCommand, UUID> {
  Optional<ConnectorCommand> findByIdAndTenantId(UUID id, UUID tenantId);
  Optional<ConnectorCommand> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);
  List<ConnectorCommand> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
  List<ConnectorCommand> findByTenantIdAndStatusInOrderByCreatedAtAsc(UUID tenantId, Collection<String> statuses);
}
