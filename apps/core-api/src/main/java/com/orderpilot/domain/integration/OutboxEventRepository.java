package com.orderpilot.domain.integration;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
  List<OutboxEvent> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
  List<OutboxEvent> findByTenantIdAndAggregateTypeAndAggregateIdOrderByCreatedAtDesc(UUID tenantId, String aggregateType, UUID aggregateId);
}
