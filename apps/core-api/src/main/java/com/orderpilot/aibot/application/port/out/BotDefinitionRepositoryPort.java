package com.orderpilot.aibot.application.port.out;

import com.orderpilot.aibot.domain.botdefinition.BotDefinition;
import java.util.Optional;
import java.util.UUID;

public interface BotDefinitionRepositoryPort {
  BotDefinition save(BotDefinition definition);

  Optional<BotDefinition> findByPublicIdAndTenantId(String publicId, UUID tenantId);
}
