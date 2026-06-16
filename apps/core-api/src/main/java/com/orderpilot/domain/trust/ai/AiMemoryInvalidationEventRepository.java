package com.orderpilot.domain.trust.ai;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance. Tenant-scoped, bounded by record id.
 */
public interface AiMemoryInvalidationEventRepository extends JpaRepository<AiMemoryInvalidationEvent, UUID> {
  List<AiMemoryInvalidationEvent> findByTenantIdAndAiMemoryRecordIdOrderByCreatedAtDesc(
      UUID tenantId, UUID aiMemoryRecordId);
}
