package com.orderpilot.aibot.domain.botdefinition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Aggregate identity for one tenant-owned bot. No provider credentials. */
public final class BotDefinition {

  private UUID id;
  private final String publicId;
  private final UUID tenantId;
  private String name;
  private String description;
  private final UUID createdByActorId;
  private final Instant createdAt;
  private Instant updatedAt;
  private long rowVersion;

  public BotDefinition(
      String publicId,
      UUID tenantId,
      String name,
      String description,
      UUID createdByActorId,
      Instant now) {
    this.publicId = requirePublicId(publicId);
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
    this.name = requireName(name);
    this.description = normalizeDescription(description);
    this.createdByActorId = createdByActorId;
    this.createdAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
    this.rowVersion = 0L;
  }

  public void rename(String name, String description, Instant now) {
    this.name = requireName(name);
    this.description = normalizeDescription(description);
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public void bindPersistence(UUID id, long rowVersion) {
    this.id = id;
    this.rowVersion = rowVersion;
  }

  public void assertExpectedRowVersion(long expected) {
    if (this.rowVersion != expected) {
      throw new BotDefinitionVersionConflictException("bot_definition_row_version_conflict");
    }
  }

  private static String requirePublicId(String publicId) {
    if (publicId == null || publicId.isBlank() || publicId.length() > 40) {
      throw new IllegalArgumentException("invalid_public_id");
    }
    return publicId.trim();
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name_required");
    }
    String trimmed = name.trim();
    if (trimmed.length() > 120) {
      throw new IllegalArgumentException("name_too_long");
    }
    return trimmed;
  }

  private static String normalizeDescription(String description) {
    if (description == null) {
      return "";
    }
    String trimmed = description.trim();
    if (trimmed.length() > 2000) {
      throw new IllegalArgumentException("description_too_long");
    }
    return trimmed;
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

  public String name() {
    return name;
  }

  public String description() {
    return description;
  }

  public UUID createdByActorId() {
    return createdByActorId;
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
