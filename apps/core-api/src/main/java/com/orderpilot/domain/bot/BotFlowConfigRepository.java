package com.orderpilot.domain.bot;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotFlowConfigRepository extends JpaRepository<BotFlowConfig, UUID> {
  List<BotFlowConfig> findByTenantIdAndBotConnectionId(UUID tenantId, UUID botConnectionId);
}
