package com.orderpilot.domain.integration;

import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorSyncEventRepository extends JpaRepository<ConnectorSyncEvent, UUID> {
  List<ConnectorSyncEvent> findByTenantIdOrderByStartedAtDesc(UUID tenantId);
  List<ConnectorSyncEvent> findByTenantIdOrderByStartedAtDesc(UUID tenantId, Pageable pageable);
  Optional<ConnectorSyncEvent> findByIdAndTenantId(UUID id, UUID tenantId);
}
