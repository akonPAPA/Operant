package com.orderpilot.aibot.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataBotDefinitionVersionRepository
    extends JpaRepository<BotDefinitionVersionJpaEntity, UUID> {
  Optional<BotDefinitionVersionJpaEntity> findByBotDefinitionIdAndVersionNumberAndTenantId(
      UUID botDefinitionId, int versionNumber, UUID tenantId);

  Optional<BotDefinitionVersionJpaEntity> findByIdAndTenantId(UUID id, UUID tenantId);
}
