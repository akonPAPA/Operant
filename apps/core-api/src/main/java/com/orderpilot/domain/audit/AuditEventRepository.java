package com.orderpilot.domain.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
  List<AuditEvent> findByTenantIdOrderByOccurredAtDesc(UUID tenantId);
  List<AuditEvent> findByTenantIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(UUID tenantId, String entityType, String entityId);
  // OP-CAP-21: bounded recent-audit window for the Command Center audit timeline preview (no full scan).
  List<AuditEvent> findTop20ByTenantIdOrderByOccurredAtDesc(UUID tenantId);
}
