package com.orderpilot.aibot.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataBotDefinitionRepository extends JpaRepository<BotDefinitionJpaEntity, UUID> {
  Optional<BotDefinitionJpaEntity> findByPublicIdAndTenantId(String publicId, UUID tenantId);
}
