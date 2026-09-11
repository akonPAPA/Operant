package com.orderpilot.aibot.infrastructure.outbox;

import com.orderpilot.aibot.application.port.out.AibotAuditPort;
import com.orderpilot.application.services.AuditEventService;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AibotAuditAdapter implements AibotAuditPort {
  private final AuditEventService auditEventService;

  public AibotAuditAdapter(AuditEventService auditEventService) {
    this.auditEventService = auditEventService;
  }

  @Override
  public void record(
      UUID tenantId, UUID actorId, String action, String entityType, String entityId, String metadataJson) {
    auditEventService.recordForTenant(tenantId, action, entityType, entityId, actorId, metadataJson);
  }
}
