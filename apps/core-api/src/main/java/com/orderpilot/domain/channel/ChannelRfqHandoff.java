package com.orderpilot.domain.channel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * OP-CAP-06B controlled Bot Runtime RFQ Handoff.
 *
 * <p>A reviewable internal RFQ/draft request created when a verified channel/bot message looks like
 * an RFQ. It is intentionally NOT a quote, order, or any trusted business record: the bot/channel
 * path produces this record so an operator can review the request, but it can never approve quotes,
 * create orders, or mutate inventory/price/customer master data.
 *
 * <p>Safety invariants:
 * <ul>
 *   <li>Always tenant-scoped via {@code tenant_id}; reads/writes go through the command service.</li>
 *   <li>One handoff per source event: the {@code (tenant_id, inbound_channel_event_id)} unique
 *       constraint makes duplicate webhook/message delivery idempotent.</li>
 *   <li>{@code request_text} holds normalized/sanitized business text only — never secrets or raw
 *       provider payloads.</li>
 * </ul>
 */
@Entity
@Table(
    name = "channel_rfq_handoff",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_channel_rfq_handoff_tenant_event",
        columnNames = {"tenant_id", "inbound_channel_event_id"}),
    indexes = {
        @Index(name = "idx_channel_rfq_handoff_tenant_status_created", columnList = "tenant_id, status, created_at"),
        @Index(name = "idx_channel_rfq_handoff_tenant_source_event", columnList = "tenant_id, source_external_event_id")
    })
public class ChannelRfqHandoff {
  @Id @GeneratedValue private UUID id;
  @Column(name = "tenant_id", nullable = false) private UUID tenantId;
  @Column(name = "inbound_channel_event_id", nullable = false) private UUID inboundChannelEventId;
  @Column(name = "channel_connection_id", nullable = false) private UUID channelConnectionId;
  @Column(name = "source_channel", nullable = false) private String sourceChannel;
  @Column(name = "source_external_event_id") private String sourceExternalEventId;
  @Column(name = "source_actor_external_id") private String sourceActorExternalId;
  @Column(name = "customer_account_id") private UUID customerAccountId;
  @Column(name = "customer_contact_id") private UUID customerContactId;
  @Column(name = "request_text", length = 4000) private String requestText;
  @Column(name = "detected_intent") private String detectedIntent;
  @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false) private ChannelRfqHandoffStatus status;
  // OP-CAP-06C operator workflow metadata. Populated only by tenant-scoped operator transition
  // commands (start review / dismiss / mark converted); never by the bot/channel intake path.
  @Column(name = "reviewer_user_id") private UUID reviewerUserId;
  @Column(name = "review_started_at") private Instant reviewStartedAt;
  @Column(name = "dismissed_at") private Instant dismissedAt;
  @Column(name = "dismiss_reason", length = 1000) private String dismissReason;
  @Column(name = "converted_at") private Instant convertedAt;
  @Column(name = "conversion_note", length = 1000) private String conversionNote;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected ChannelRfqHandoff() {}

  public ChannelRfqHandoff(
      UUID tenantId,
      UUID inboundChannelEventId,
      UUID channelConnectionId,
      String sourceChannel,
      String sourceExternalEventId,
      String sourceActorExternalId,
      UUID customerAccountId,
      UUID customerContactId,
      String requestText,
      String detectedIntent,
      Instant now) {
    this.tenantId = tenantId;
    this.inboundChannelEventId = inboundChannelEventId;
    this.channelConnectionId = channelConnectionId;
    this.sourceChannel = sourceChannel;
    this.sourceExternalEventId = sourceExternalEventId;
    this.sourceActorExternalId = sourceActorExternalId;
    this.customerAccountId = customerAccountId;
    this.customerContactId = customerContactId;
    this.requestText = requestText;
    this.detectedIntent = detectedIntent;
    this.status = ChannelRfqHandoffStatus.PENDING_REVIEW;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /**
   * OP-CAP-06C: operator takes this handoff into review (PENDING_REVIEW -&gt; IN_REVIEW). The caller
   * (service) is responsible for validating the source status; this method only applies the mutation.
   */
  public void startReview(UUID reviewerUserId, Instant now) {
    this.status = ChannelRfqHandoffStatus.IN_REVIEW;
    this.reviewerUserId = reviewerUserId;
    this.reviewStartedAt = now;
    this.updatedAt = now;
  }

  /**
   * OP-CAP-06C: operator dismisses this handoff as invalid/irrelevant. Terminal state. Records the
   * dismiss reason; the service guarantees the reason is non-blank before calling this.
   */
  public void dismiss(String reason, UUID actorUserId, Instant now) {
    this.status = ChannelRfqHandoffStatus.DISMISSED;
    this.dismissReason = reason;
    this.dismissedAt = now;
    if (this.reviewerUserId == null) {
      this.reviewerUserId = actorUserId;
    }
    this.updatedAt = now;
  }

  /**
   * OP-CAP-06C: operator marks this handoff as converted — i.e. the handoff review workflow is
   * complete and it is ready for a later, separately-gated quote/order workflow. Terminal state.
   * This deliberately does NOT create any quote/order and triggers no external write.
   */
  public void markConverted(String conversionNote, UUID actorUserId, Instant now) {
    this.status = ChannelRfqHandoffStatus.CONVERTED;
    this.conversionNote = conversionNote;
    this.convertedAt = now;
    if (this.reviewerUserId == null) {
      this.reviewerUserId = actorUserId;
    }
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getTenantId() { return tenantId; }
  public UUID getInboundChannelEventId() { return inboundChannelEventId; }
  public UUID getChannelConnectionId() { return channelConnectionId; }
  public String getSourceChannel() { return sourceChannel; }
  public String getSourceExternalEventId() { return sourceExternalEventId; }
  public String getSourceActorExternalId() { return sourceActorExternalId; }
  public UUID getCustomerAccountId() { return customerAccountId; }
  public UUID getCustomerContactId() { return customerContactId; }
  public String getRequestText() { return requestText; }
  public String getDetectedIntent() { return detectedIntent; }
  public ChannelRfqHandoffStatus getStatus() { return status; }
  public UUID getReviewerUserId() { return reviewerUserId; }
  public Instant getReviewStartedAt() { return reviewStartedAt; }
  public Instant getDismissedAt() { return dismissedAt; }
  public String getDismissReason() { return dismissReason; }
  public Instant getConvertedAt() { return convertedAt; }
  public String getConversionNote() { return conversionNote; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
