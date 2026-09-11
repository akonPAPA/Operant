package com.orderpilot.aibot.infrastructure.persistence;

import com.orderpilot.aibot.application.port.out.BotDefinitionVersionRepositoryPort;
import com.orderpilot.aibot.domain.botdefinition.BotDefinitionVersion;
import com.orderpilot.aibot.domain.botdefinition.BotDefinitionVersionState;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BotDefinitionVersionRepositoryAdapter implements BotDefinitionVersionRepositoryPort {
  private final SpringDataBotDefinitionVersionRepository repository;
  private final BotConfigurationJsonMapper configurationJsonMapper;

  public BotDefinitionVersionRepositoryAdapter(
      SpringDataBotDefinitionVersionRepository repository,
      BotConfigurationJsonMapper configurationJsonMapper) {
    this.repository = repository;
    this.configurationJsonMapper = configurationJsonMapper;
  }

  @Override
  @Transactional
  public BotDefinitionVersion save(BotDefinitionVersion version) {
    BotDefinitionVersionJpaEntity entity;
    if (version.id() == null) {
      entity =
          new BotDefinitionVersionJpaEntity(
              version.publicId(),
              version.tenantId(),
              version.botDefinitionId(),
              version.versionNumber(),
              version.state().name(),
              version.schemaVersion(),
              configurationJsonMapper.toJson(version.configuration()),
              version.createdAt());
    } else {
      entity =
          repository
              .findById(version.id())
              .orElseThrow(() -> new IllegalStateException("bot_version_missing"));
      if (!entity.getTenantId().equals(version.tenantId())) {
        throw new IllegalStateException("tenant_mismatch");
      }
      if (entity.getRowVersion() != version.rowVersion()) {
        throw new IllegalStateException("bot_version_row_version_conflict");
      }
    }
    entity.overwrite(
        version.state().name(),
        version.schemaVersion(),
        configurationJsonMapper.toJson(version.configuration()),
        version.validationJson(),
        version.providerProvenanceJson(),
        version.updatedAt());
    return toDomain(repository.save(entity));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<BotDefinitionVersion> findByBotDefinitionIdAndVersionNumberAndTenantId(
      UUID botDefinitionId, int versionNumber, UUID tenantId) {
    return repository
        .findByBotDefinitionIdAndVersionNumberAndTenantId(botDefinitionId, versionNumber, tenantId)
        .map(this::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<BotDefinitionVersion> findByIdAndTenantId(UUID id, UUID tenantId) {
    return repository.findByIdAndTenantId(id, tenantId).map(this::toDomain);
  }

  private BotDefinitionVersion toDomain(BotDefinitionVersionJpaEntity entity) {
    BotDefinitionVersion domain =
        new BotDefinitionVersion(
            entity.getPublicId(),
            entity.getTenantId(),
            entity.getBotDefinitionId(),
            entity.getVersionNumber(),
            entity.getCreatedAt());
    domain.rehydrate(
        BotDefinitionVersionState.valueOf(entity.getState()),
        entity.getSchemaVersion(),
        configurationJsonMapper.fromJson(entity.getConfigurationJson()),
        entity.getValidationJson(),
        entity.getProviderProvenanceJson(),
        entity.getUpdatedAt(),
        entity.getId(),
        entity.getRowVersion());
    return domain;
  }
}
