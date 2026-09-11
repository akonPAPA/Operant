package com.orderpilot.aibot.application.port.out;

import java.util.UUID;

public interface AibotAuditPort {
  void record(UUID tenantId, UUID actorId, String action, String entityType, String entityId, String metadataJson);
}
