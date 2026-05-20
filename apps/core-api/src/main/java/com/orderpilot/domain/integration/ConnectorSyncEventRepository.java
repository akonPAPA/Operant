package com.orderpilot.domain.integration;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorSyncEventRepository extends JpaRepository<ConnectorSyncEvent, UUID> {
  List<ConnectorSyncEvent> findByTenantIdOrderByStartedAtDesc(UUID tenantId);
  Optional<ConnectorSyncEvent> findByIdAndTenantId(UUID id, UUID tenantId);
}
