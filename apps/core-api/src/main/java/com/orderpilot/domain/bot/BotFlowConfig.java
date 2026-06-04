package com.orderpilot.domain.bot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bot_flow_config")
public class BotFlowConfig {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "bot_connection_id", nullable = false) private UUID botConnectionId;
  @Column(name = "flow_name", nullable = false) private String flowName;
  @Column(nullable = false) private boolean enabled;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected BotFlowConfig() {}
  public BotFlowConfig(UUID tenantId, UUID botConnectionId, String flowName, boolean enabled, Instant now) {
    this.tenantId = tenantId; this.botConnectionId = botConnectionId; this.flowName = flowName; this.enabled = enabled; this.createdAt = now; this.updatedAt = now;
  }
  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getBotConnectionId() { return botConnectionId; }
  public String getFlowName() { return flowName; }
  public boolean isEnabled() { return enabled; }
}
