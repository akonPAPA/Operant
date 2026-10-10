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

@Entity
@Table(
    name = "aibot_bot_definition",
    uniqueConstraints = {
      @UniqueConstraint(name = "uq_aibot_bot_definition_public_id", columnNames = "public_id"),
      @UniqueConstraint(name = "uq_aibot_bot_definition_tenant_name", columnNames = {"tenant_id", "name"})
    })
public class BotDefinitionJpaEntity {
  @Id @GeneratedValue private UUID id;

  @Column(name = "public_id", nullable = false, updatable = false, length = 40)
  private String publicId;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  @Column(nullable = false, length = 120)
  private String name;

  @Column(nullable = false, length = 2000)
  private String description;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  protected BotDefinitionJpaEntity() {}

  public BotDefinitionJpaEntity(
      String publicId,
      UUID tenantId,
      String name,
      String description,
      UUID createdBy,
      Instant now) {
    this.publicId = publicId;
    this.tenantId = tenantId;
    this.name = name;
    this.description = description == null ? "" : description;
    this.createdBy = createdBy;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void rename(String name, String description, Instant now) {
    this.name = name;
    this.description = description == null ? "" : description;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public String getPublicId() { return publicId; }
  public UUID getTenantId() { return tenantId; }
  public String getName() { return name; }
  public String getDescription() { return description; }
  public UUID getCreatedBy() { return createdBy; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public long getRowVersion() { return rowVersion; }
}
