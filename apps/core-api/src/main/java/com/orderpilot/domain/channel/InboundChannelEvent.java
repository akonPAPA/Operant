package com.orderpilot.domain.channel;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "inbound_channel_event")
public class InboundChannelEvent {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "channel_connection_id", nullable = false) private UUID channelConnectionId;
  @Enumerated(EnumType.STRING) @Column(name = "provider_type", nullable = false) private ChannelProviderType providerType;
  @Column(name = "external_event_id") private String externalEventId;
  @Column(name = "source_actor_type", nullable = false) private String sourceActorType;
  @Column(name = "source_actor_external_id") private String sourceActorExternalId;
  @Column(name = "normalized_text", length = 4000) private String normalizedText;
  @Column(name = "payload_hash", nullable = false) private String payloadHash;
  @Column(name = "raw_payload_storage_ref") private String rawPayloadStorageRef;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "raw_payload_json", columnDefinition = "jsonb") private String rawPayloadJson;
  @Column(nullable = false) private String status;
  @Column(name = "received_at", nullable = false) private Instant receivedAt;
  @Column(name = "processed_at") private Instant processedAt;
  @Column(name = "verification_status") private String verificationStatus;
  @Column(name = "verification_reason") private String verificationReason;
  @Column(name = "error_code") private String errorCode;
  @Column(name = "error_message") private String errorMessage;
  @Column(name = "bot_conversation_id") private UUID botConversationId;
  @Column(name = "bot_message_id") private UUID botMessageId;
  @Column(name = "bot_runtime_status") private String botRuntimeStatus;

  protected InboundChannelEvent() {}

  public InboundChannelEvent(UUID tenantId, UUID channelConnectionId, ChannelProviderType providerType, String externalEventId, String sourceActorType, String sourceActorExternalId, String normalizedText, String payloadHash, String rawPayloadJson, Instant now) {
    this(tenantId, channelConnectionId, providerType, externalEventId, sourceActorType, sourceActorExternalId, normalizedText, payloadHash, rawPayloadJson, null, null, now);
  }

  public InboundChannelEvent(UUID tenantId, UUID channelConnectionId, ChannelProviderType providerType, String externalEventId, String sourceActorType, String sourceActorExternalId, String normalizedText, String payloadHash, String rawPayloadJson, String verificationStatus, String verificationReason, Instant now) {
    this.tenantId = tenantId;
    this.channelConnectionId = channelConnectionId;
    this.providerType = providerType;
    this.externalEventId = externalEventId;
    this.sourceActorType = sourceActorType == null || sourceActorType.isBlank() ? "UNKNOWN" : sourceActorType;
    this.sourceActorExternalId = sourceActorExternalId;
    this.normalizedText = normalizedText;
    this.payloadHash = payloadHash;
    this.rawPayloadJson = rawPayloadJson == null || rawPayloadJson.isBlank() ? "{}" : rawPayloadJson;
    this.verificationStatus = verificationStatus;
    this.verificationReason = verificationReason;
    this.status = "NORMALIZED";
    this.receivedAt = now;
    this.processedAt = now;
  }

  public void markIgnoredDuplicate(Instant now) { this.status = "IGNORED"; this.processedAt = now; }
  public void markFailed(String errorCode, String errorMessage, Instant now) { this.status = "FAILED"; this.errorCode = errorCode; this.errorMessage = errorMessage; this.processedAt = now; }

  /** OP-CAP-06A: record that this verified channel event drove the controlled bot runtime. */
  public void linkBotRuntime(UUID botConversationId, UUID botMessageId, String botRuntimeStatus, Instant now) {
    this.botConversationId = botConversationId;
    this.botMessageId = botMessageId;
    this.botRuntimeStatus = botRuntimeStatus;
    this.status = "ROUTED";
    this.processedAt = now;
  }

  /** OP-CAP-06A: event stored but intentionally not driven into the bot runtime (e.g. non-Telegram provider this slice). */
  public void markBotNotBridged(String botRuntimeStatus, Instant now) {
    this.botRuntimeStatus = botRuntimeStatus;
    this.processedAt = now;
  }

  /** OP-CAP-06A: bot bridge could not process the event; routed for review without uncontrolled failure. */
  public void markBotBridgeFailed(String reason, Instant now) {
    this.botRuntimeStatus = "BRIDGE_FAILED";
    markFailed("BOT_BRIDGE_FAILED", reason, now);
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getChannelConnectionId() { return channelConnectionId; }
  public ChannelProviderType getProviderType() { return providerType; }
  public String getExternalEventId() { return externalEventId; }
  public String getSourceActorType() { return sourceActorType; }
  public String getSourceActorExternalId() { return sourceActorExternalId; }
  public String getNormalizedText() { return normalizedText; }
  public String getPayloadHash() { return payloadHash; }
  public String getRawPayloadStorageRef() { return rawPayloadStorageRef; }
  public String getStatus() { return status; }
  public Instant getReceivedAt() { return receivedAt; }
  public Instant getProcessedAt() { return processedAt; }
  public String getVerificationStatus() { return verificationStatus; }
  public String getVerificationReason() { return verificationReason; }
  public String getErrorCode() { return errorCode; }
  public String getErrorMessage() { return errorMessage; }
  public UUID getBotConversationId() { return botConversationId; }
  public UUID getBotMessageId() { return botMessageId; }
  public String getBotRuntimeStatus() { return botRuntimeStatus; }
}
