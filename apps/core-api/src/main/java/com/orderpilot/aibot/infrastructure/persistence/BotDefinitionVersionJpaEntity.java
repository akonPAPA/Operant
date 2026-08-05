package com.orderpilot.aibot.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "aibot_bot_definition_version",
    uniqueConstraints = {
      @UniqueConstraint(name = "uq_aibot_bot_definition_version_public_id", columnNames = "public_id"),
      @UniqueConstraint(
          name = "uq_aibot_bot_definition_version_number",
          columnNames = {"bot_definition_id", "version_number"})
    })
public class BotDefinitionVersionJpaEntity {
  @Id @GeneratedValue private UUID id;

  @Column(name = "public_id", nullable = false, updatable = false, length = 40)
  private String publicId;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  @Column(name = "bot_definition_id", nullable = false, updatable = false)
  private UUID botDefinitionId;

  @Column(name = "version_number", nullable = false, updatable = false)
  private int versionNumber;

  @Column(nullable = false, length = 20)
  private String state;

  @Column(name = "schema_version", nullable = false, length = 64)
  private String schemaVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "configuration_json", nullable = false, columnDefinition = "jsonb")
  private String configurationJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "validation_json", nullable = false, columnDefinition = "jsonb")
  private String validationJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "provider_provenance_json", nullable = false, columnDefinition = "jsonb")
  private String providerProvenanceJson;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  protected BotDefinitionVersionJpaEntity() {}

  public BotDefinitionVersionJpaEntity(
      String publicId,
      UUID tenantId,
      UUID botDefinitionId,
      int versionNumber,
      String state,
      String schemaVersion,
      String configurationJson,
      Instant now) {
    this.publicId = publicId;
    this.tenantId = tenantId;
    this.botDefinitionId = botDefinitionId;
    this.versionNumber = versionNumber;
    this.state = state;
    this.schemaVersion = schemaVersion;
    this.configurationJson = configurationJson == null ? "{}" : configurationJson;
    this.validationJson = "{}";
    this.providerProvenanceJson = "{}";
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void overwrite(
      String state,
      String schemaVersion,
      String configurationJson,
      String validationJson,
      String providerProvenanceJson,
      Instant now) {
    this.state = state;
    this.schemaVersion = schemaVersion;
    this.configurationJson = configurationJson == null ? "{}" : configurationJson;
    this.validationJson = validationJson == null ? "{}" : validationJson;
    this.providerProvenanceJson = providerProvenanceJson == null ? "{}" : providerProvenanceJson;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public String getPublicId() { return publicId; }
  public UUID getTenantId() { return tenantId; }
  public UUID getBotDefinitionId() { return botDefinitionId; }
  public int getVersionNumber() { return versionNumber; }
  public String getState() { return state; }
  public String getSchemaVersion() { return schemaVersion; }
  public String getConfigurationJson() { return configurationJson; }
  public String getValidationJson() { return validationJson; }
  public String getProviderProvenanceJson() { return providerProvenanceJson; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public long getRowVersion() { return rowVersion; }
}
