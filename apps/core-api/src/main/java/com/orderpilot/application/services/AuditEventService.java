package com.orderpilot.application.services;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEvent;
import com.orderpilot.domain.audit.AuditEventRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditEventService {
  private final AuditEventRepository auditEventRepository;
  private final Clock clock;

  public AuditEventService(AuditEventRepository auditEventRepository, Clock clock) {
    this.auditEventRepository = auditEventRepository;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public AuditEvent record(String action, String entityType, String entityId, UUID actorId, String metadataJson) {
    UUID tenantId = TenantContext.requireTenantId();
    String metadata = metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson;
    AuditEvent event = new AuditEvent(tenantId, actorId, action, entityType, entityId, metadata, clock.instant());
    return auditEventRepository.save(event);
  }
}
