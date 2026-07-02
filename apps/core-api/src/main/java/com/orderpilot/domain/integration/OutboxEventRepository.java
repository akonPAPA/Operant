package com.orderpilot.domain.integration;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
  List<OutboxEvent> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
  List<OutboxEvent> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);
  List<OutboxEvent> findByTenantIdAndAggregateTypeAndAggregateIdOrderByCreatedAtDesc(UUID tenantId, String aggregateType, UUID aggregateId);
  // OP-CAP-21: bounded counts + last-published lookup for the Command Center outbox health summary.
  long countByTenantIdAndStatus(UUID tenantId, String status);
  java.util.Optional<OutboxEvent> findFirstByTenantIdAndPublishedAtIsNotNullOrderByPublishedAtDesc(UUID tenantId);
}
