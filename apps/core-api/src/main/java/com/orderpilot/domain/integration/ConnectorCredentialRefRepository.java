package com.orderpilot.domain.integration;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorCredentialRefRepository extends JpaRepository<ConnectorCredentialRef, UUID> {
  List<ConnectorCredentialRef> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
