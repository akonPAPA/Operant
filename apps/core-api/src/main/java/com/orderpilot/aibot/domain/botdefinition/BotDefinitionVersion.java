package com.orderpilot.aibot.domain.botdefinition;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Versioned declarative bot configuration. */
public final class BotDefinitionVersion {

  private UUID id;
  private final String publicId;
  private final UUID tenantId;
  private final UUID botDefinitionId;
  private final int versionNumber;
  private BotDefinitionVersionState state;
  private String schemaVersion;
  private BotDefinitionConfiguration configuration;
  private String validationJson;
  private String providerProvenanceJson;
  private final Instant createdAt;
  private Instant updatedAt;
  private long rowVersion;

  public BotDefinitionVersion(
      String publicId,
      UUID tenantId,
      UUID botDefinitionId,
      int versionNumber,
      Instant now) {
    this.publicId = Objects.requireNonNull(publicId, "publicId");
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    this.botDefinitionId = Objects.requireNonNull(botDefinitionId, "botDefinitionId");
    if (versionNumber < 1) {
      throw new IllegalArgumentException("invalid_version_number");
    }
    this.versionNumber = versionNumber;
    this.state = BotDefinitionVersionState.DRAFT;
    this.schemaVersion = BotDefinitionConfiguration.SCHEMA_V1;
    this.configuration =
        new BotDefinitionConfiguration(BotDefinitionConfiguration.SCHEMA_V1, "", List.of(), BotHandoffPolicy.defaults());
    this.validationJson = "{}";
    this.providerProvenanceJson = "{}";
    this.createdAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
    this.rowVersion = 0L;
  }

  public void markGenerating(Instant now) {
    if (!state.canStartGeneration()) {
      throw new IllegalStateException("not_ready_for_generation");
    }
    this.state = BotDefinitionVersionState.GENERATING;
    this.updatedAt = now;
  }

  public void markValidating(Instant now) {
    if (state != BotDefinitionVersionState.GENERATING && state != BotDefinitionVersionState.DRAFT) {
      throw new IllegalStateException("not_ready_for_validation");
    }
    this.state = BotDefinitionVersionState.VALIDATING;
    this.updatedAt = now;
  }

  public void applyValidatedConfiguration(
      BotDefinitionConfiguration configuration,
      String validationJson,
      String providerProvenanceJson,
      Instant now) {
    if (state != BotDefinitionVersionState.GENERATING
        && state != BotDefinitionVersionState.VALIDATING) {
      throw new IllegalStateException("illegal_apply_state");
    }
    BotDefinitionValidator.validate(configuration);
    BotSecurityLinter.assertClean(configuration);
    this.configuration = configuration;
    this.schemaVersion = configuration.schemaVersion();
    this.validationJson = validationJson == null || validationJson.isBlank() ? "{}" : validationJson;
    this.providerProvenanceJson =
        providerProvenanceJson == null || providerProvenanceJson.isBlank()
            ? "{}"
            : providerProvenanceJson;
    this.state = BotDefinitionVersionState.VALIDATED;
    this.updatedAt = now;
  }

  public void markInvalid(String validationJson, Instant now) {
    this.validationJson = validationJson == null || validationJson.isBlank() ? "{}" : validationJson;
    this.state = BotDefinitionVersionState.INVALID;
    this.updatedAt = now;
  }

  public void restoreDraft(Instant now) {
    if (state != BotDefinitionVersionState.GENERATING && state != BotDefinitionVersionState.INVALID) {
      throw new IllegalStateException("cannot_restore_draft");
    }
    this.state = BotDefinitionVersionState.DRAFT;
    this.updatedAt = now;
  }

  public Optional<BotIntentDefinition> findIntent(String intentKey) {
    if (intentKey == null || configuration == null) {
      return Optional.empty();
    }
    return configuration.intents().stream()
        .filter(i -> intentKey.equals(i.intentKey()))
        .findFirst();
  }

  /** Persistence-only restore. Does not enforce transition rules. */
  public void rehydrate(
      BotDefinitionVersionState state,
      String schemaVersion,
      BotDefinitionConfiguration configuration,
      String validationJson,
      String providerProvenanceJson,
      Instant updatedAt,
      UUID id,
      long rowVersion) {
    this.state = Objects.requireNonNull(state, "state");
    this.schemaVersion = schemaVersion;
    this.configuration = configuration == null
        ? new BotDefinitionConfiguration(BotDefinitionConfiguration.SCHEMA_V1, "", List.of(), BotHandoffPolicy.defaults())
        : configuration;
    this.validationJson = validationJson == null || validationJson.isBlank() ? "{}" : validationJson;
    this.providerProvenanceJson =
        providerProvenanceJson == null || providerProvenanceJson.isBlank() ? "{}" : providerProvenanceJson;
    this.updatedAt = updatedAt;
    bindPersistence(id, rowVersion);
  }

  public void bindPersistence(UUID id, long rowVersion) {
    this.id = id;
    this.rowVersion = rowVersion;
  }

  public void assertExpectedRowVersion(long expected) {
    if (this.rowVersion != expected) {
      throw new BotDefinitionVersionConflictException("bot_version_row_version_conflict");
    }
  }

  public UUID id() {
    return id;
  }

  public String publicId() {
    return publicId;
  }

  public UUID tenantId() {
    return tenantId;
  }

  public UUID botDefinitionId() {
    return botDefinitionId;
  }

  public int versionNumber() {
    return versionNumber;
  }

  public BotDefinitionVersionState state() {
    return state;
  }

  public String schemaVersion() {
    return schemaVersion;
  }

  public BotDefinitionConfiguration configuration() {
    return configuration;
  }

  public String validationJson() {
    return validationJson;
  }

  public String providerProvenanceJson() {
    return providerProvenanceJson;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public long rowVersion() {
    return rowVersion;
  }
}
