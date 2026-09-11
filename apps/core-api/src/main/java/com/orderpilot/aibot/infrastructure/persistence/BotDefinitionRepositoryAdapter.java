package com.orderpilot.aibot.infrastructure.persistence;

import com.orderpilot.aibot.application.port.out.BotDefinitionRepositoryPort;
import com.orderpilot.aibot.domain.botdefinition.BotDefinition;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BotDefinitionRepositoryAdapter implements BotDefinitionRepositoryPort {
  private final SpringDataBotDefinitionRepository repository;

  public BotDefinitionRepositoryAdapter(SpringDataBotDefinitionRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional
  public BotDefinition save(BotDefinition definition) {
    BotDefinitionJpaEntity entity;
    if (definition.id() == null) {
      entity =
          new BotDefinitionJpaEntity(
              definition.publicId(),
              definition.tenantId(),
              definition.name(),
              definition.description(),
              definition.createdByActorId(),
              definition.createdAt());
    } else {
      entity =
          repository
              .findById(definition.id())
              .orElseThrow(() -> new IllegalStateException("bot_definition_missing"));
      if (!entity.getTenantId().equals(definition.tenantId())) {
        throw new IllegalStateException("tenant_mismatch");
      }
      if (entity.getRowVersion() != definition.rowVersion()) {
        throw new IllegalStateException("bot_definition_row_version_conflict");
      }
      entity.rename(definition.name(), definition.description(), definition.updatedAt());
    }
    BotDefinitionJpaEntity saved = repository.save(entity);
    return toDomain(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<BotDefinition> findByPublicIdAndTenantId(String publicId, UUID tenantId) {
    return repository.findByPublicIdAndTenantId(publicId, tenantId).map(this::toDomain);
  }

  private BotDefinition toDomain(BotDefinitionJpaEntity entity) {
    BotDefinition domain =
        new BotDefinition(
            entity.getPublicId(),
            entity.getTenantId(),
            entity.getName(),
            entity.getDescription(),
            entity.getCreatedBy(),
            entity.getCreatedAt());
    domain.bindPersistence(entity.getId(), entity.getRowVersion());
    return domain;
  }
}
