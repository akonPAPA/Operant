package com.orderpilot.domain.bot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "bot_connection")
public class BotConnection {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "channel_type", nullable = false) private String channelType;
  @Column(name = "bot_external_id") private String botExternalId;
  @Column(name = "telegram_bot_id") private String telegramBotId;
  @Column(nullable = false) private boolean enabled;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "allowed_flows", nullable = false, columnDefinition = "jsonb") private String allowedFlows;
  @Column(name = "default_handoff_queue", nullable = false) private String defaultHandoffQueue;
  @Column(name = "last_seen_at") private Instant lastSeenAt;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb") private String metadataJson;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected BotConnection() {}

  public BotConnection(UUID tenantId, String channelType, String botExternalId, String telegramBotId, boolean enabled, String allowedFlows, String defaultHandoffQueue, String metadataJson, Instant now) {
    this.tenantId = tenantId;
    this.channelType = channelType;
    this.botExternalId = botExternalId;
    this.telegramBotId = telegramBotId;
    this.enabled = enabled;
    this.allowedFlows = allowedFlows == null || allowedFlows.isBlank() ? "[]" : allowedFlows;
    this.defaultHandoffQueue = defaultHandoffQueue == null || defaultHandoffQueue.isBlank() ? "BOT_REVIEW" : defaultHandoffQueue;
    this.metadataJson = metadataJson == null || metadataJson.isBlank() ? "{}" : metadataJson;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void touch(Instant now) { this.lastSeenAt = now; this.updatedAt = now; }
  public void configure(boolean enabled, String allowedFlows, String defaultHandoffQueue, Instant now) { this.enabled = enabled; this.allowedFlows = allowedFlows == null || allowedFlows.isBlank() ? "[]" : allowedFlows; this.defaultHandoffQueue = defaultHandoffQueue == null || defaultHandoffQueue.isBlank() ? "BOT_REVIEW" : defaultHandoffQueue; this.updatedAt = now; }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getChannelType() { return channelType; }
  public String getBotExternalId() { return botExternalId; }
  public String getTelegramBotId() { return telegramBotId; }
  public boolean isEnabled() { return enabled; }
  public String getAllowedFlows() { return allowedFlows; }
  public String getDefaultHandoffQueue() { return defaultHandoffQueue; }
  public Instant getLastSeenAt() { return lastSeenAt; }
  public String getMetadataJson() { return metadataJson; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
