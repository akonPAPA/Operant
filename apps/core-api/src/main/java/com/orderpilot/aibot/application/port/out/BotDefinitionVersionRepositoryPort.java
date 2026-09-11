package com.orderpilot.aibot.application.port.out;

import com.orderpilot.aibot.domain.botdefinition.BotDefinitionVersion;
import java.util.Optional;
import java.util.UUID;

public interface BotDefinitionVersionRepositoryPort {
  BotDefinitionVersion save(BotDefinitionVersion version);

  Optional<BotDefinitionVersion> findByBotDefinitionIdAndVersionNumberAndTenantId(
      UUID botDefinitionId, int versionNumber, UUID tenantId);

  Optional<BotDefinitionVersion> findByIdAndTenantId(UUID id, UUID tenantId);
}
