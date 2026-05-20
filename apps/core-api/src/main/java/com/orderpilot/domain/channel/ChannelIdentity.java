package com.orderpilot.domain.channel;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "channel_identity",
    uniqueConstraints = @UniqueConstraint(name = "uq_channel_identity_sender", columnNames = {"tenant_id", "channel_type", "external_sender_id"}))
public class ChannelIdentity {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "channel_type", nullable = false) private String channelType;
  @Column(name = "external_sender_id", nullable = false) private String externalSenderId;
  @Column(name = "external_conversation_id") private String externalConversationId;
  @Column(name = "sender_phone") private String senderPhone;
  @Column(name = "sender_display_name") private String senderDisplayName;
  @Column(name = "customer_account_id") private UUID customerAccountId;
  @Column(name = "customer_contact_id") private UUID customerContactId;
  @Column(name = "identity_status", nullable = false) private String identityStatus;
  @Column(name = "match_confidence") private BigDecimal matchConfidence;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Column(name = "linked_at") private Instant linkedAt;
  @Column(name = "linked_by_user_id") private UUID linkedByUserId;
  @Column(name = "notes") private String notes;

  protected ChannelIdentity() {}

  public ChannelIdentity(UUID tenantId, String channelType, String externalSenderId, String externalConversationId, String senderPhone, String senderDisplayName, Instant now) {
    this.tenantId = tenantId;
    this.channelType = channelType;
    this.externalSenderId = externalSenderId;
    this.externalConversationId = externalConversationId;
    this.senderPhone = senderPhone;
    this.senderDisplayName = senderDisplayName;
    this.identityStatus = "UNLINKED";
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void refreshInboundContext(String externalConversationId, String senderPhone, String senderDisplayName, Instant now) {
    if (notBlank(externalConversationId)) this.externalConversationId = externalConversationId;
    if (notBlank(senderPhone)) this.senderPhone = senderPhone;
    if (notBlank(senderDisplayName)) this.senderDisplayName = senderDisplayName;
    this.updatedAt = now;
  }

  public void suggestMatch(UUID customerAccountId, UUID customerContactId, BigDecimal matchConfidence, String notes, Instant now) {
    this.customerAccountId = customerAccountId;
    this.customerContactId = customerContactId;
    this.matchConfidence = matchConfidence;
    this.identityStatus = "SUGGESTED_MATCH";
    this.notes = notes;
    this.updatedAt = now;
  }

  public void link(UUID customerAccountId, UUID customerContactId, UUID linkedByUserId, String notes, Instant now) {
    this.customerAccountId = customerAccountId;
    this.customerContactId = customerContactId;
    this.linkedByUserId = linkedByUserId;
    this.linkedAt = now;
    this.identityStatus = "LINKED";
    this.notes = notes;
    this.updatedAt = now;
  }

  public void unlink(String notes, Instant now) {
    this.customerAccountId = null;
    this.customerContactId = null;
    this.linkedByUserId = null;
    this.linkedAt = null;
    this.matchConfidence = null;
    this.identityStatus = "UNLINKED";
    this.notes = notes;
    this.updatedAt = now;
  }

  public void block(String notes, Instant now) {
    this.identityStatus = "BLOCKED";
    this.notes = notes;
    this.updatedAt = now;
  }

  public void needsReview(String notes, Instant now) {
    this.identityStatus = "NEEDS_REVIEW";
    this.notes = notes;
    this.updatedAt = now;
  }

  public boolean isLinked() { return "LINKED".equals(identityStatus); }
  public boolean isBlocked() { return "BLOCKED".equals(identityStatus); }
  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public String getChannelType() { return channelType; }
  public String getExternalSenderId() { return externalSenderId; }
  public String getExternalConversationId() { return externalConversationId; }
  public String getSenderPhone() { return senderPhone; }
  public String getSenderDisplayName() { return senderDisplayName; }
  public UUID getCustomerAccountId() { return customerAccountId; }
  public UUID getCustomerContactId() { return customerContactId; }
  public String getIdentityStatus() { return identityStatus; }
  public BigDecimal getMatchConfidence() { return matchConfidence; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public Instant getLinkedAt() { return linkedAt; }
  public UUID getLinkedByUserId() { return linkedByUserId; }
  public String getNotes() { return notes; }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }
}
