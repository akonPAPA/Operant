package com.orderpilot.domain.integration;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationConnectionRepository extends JpaRepository<IntegrationConnection, UUID> {
  List<IntegrationConnection> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
  Optional<IntegrationConnection> findByIdAndTenantId(UUID id, UUID tenantId);
}
