package com.orderpilot.domain.intake;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "channel_message")
public class ChannelMessage {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(nullable = false) private String channel;
  @Column(name = "external_message_id") private String externalMessageId;
  @Column(name = "conversation_id") private String conversationId;
  @Column(name = "sender_handle") private String senderHandle;
  @Column(name = "sender_display_name") private String senderDisplayName;
  @Column(name = "customer_account_id") private UUID customerAccountId;
  @Column(name = "customer_contact_id") private UUID customerContactId;
  @Column(name = "channel_identity_id") private UUID channelIdentityId;
  @Column(name = "signature_verification_mode") private String signatureVerificationMode;
  @Column(nullable = false) private String direction;
  @Column(name = "message_type", nullable = false) private String messageType;
  @Column(name = "text_content") private String textContent;
  @JdbcTypeCode(SqlTypes.JSON) @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb") private String rawPayload;
  @Column(nullable = false) private String status;
  @Column(name = "received_at", nullable = false) private Instant receivedAt;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  protected ChannelMessage() {}
  public ChannelMessage(UUID tenantId, String channel, String externalMessageId, String conversationId, String senderHandle, String senderDisplayName, UUID customerAccountId, String direction, String messageType, String textContent, String rawPayload, String status, Instant now) {
    this.tenantId=tenantId; this.channel=channel; this.externalMessageId=externalMessageId; this.conversationId=conversationId; this.senderHandle=senderHandle; this.senderDisplayName=senderDisplayName; this.customerAccountId=customerAccountId; this.direction=direction; this.messageType=messageType; this.textContent=textContent; this.rawPayload=rawPayload == null || rawPayload.isBlank() ? "{}" : rawPayload; this.status=status; this.receivedAt=now; this.createdAt=now; this.updatedAt=now;
  }
  public ChannelMessage(UUID tenantId, String channel, String externalMessageId, String conversationId, String senderHandle, String senderDisplayName, UUID customerAccountId, UUID customerContactId, UUID channelIdentityId, String signatureVerificationMode, String direction, String messageType, String textContent, String rawPayload, String status, Instant now) {
    this(tenantId, channel, externalMessageId, conversationId, senderHandle, senderDisplayName, customerAccountId, direction, messageType, textContent, rawPayload, status, now);
    this.customerContactId = customerContactId;
    this.channelIdentityId = channelIdentityId;
    this.signatureVerificationMode = signatureVerificationMode;
  }
  public void markDuplicate(Instant now){this.status="DUPLICATE"; this.updatedAt=now;} public void markQueued(Instant now){this.status="QUEUED"; this.updatedAt=now;}
  public UUID getId(){return id;} public UUID getTenantId(){return tenantId;} public String getChannel(){return channel;} public String getExternalMessageId(){return externalMessageId;} public String getConversationId(){return conversationId;} public String getSenderHandle(){return senderHandle;} public String getSenderDisplayName(){return senderDisplayName;} public UUID getCustomerAccountId(){return customerAccountId;} public UUID getCustomerContactId(){return customerContactId;} public UUID getChannelIdentityId(){return channelIdentityId;} public String getSignatureVerificationMode(){return signatureVerificationMode;} public String getMessageType(){return messageType;} public String getTextContent(){return textContent;} public String getStatus(){return status;} public Instant getReceivedAt(){return receivedAt;}
}
