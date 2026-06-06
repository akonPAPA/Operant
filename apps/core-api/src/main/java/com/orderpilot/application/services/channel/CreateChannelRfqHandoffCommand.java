package com.orderpilot.application.services.channel;

import java.util.UUID;

/**
 * OP-CAP-06B command to create a reviewable {@code ChannelRfqHandoff} from a verified channel/bot
 * event. The tenant is never carried here: it is resolved from {@code TenantContext} and the owning
 * inbound channel event inside the command service, so a caller can never assert another tenant.
 *
 * @param inboundChannelEventId source channel event reference (idempotency key with tenant)
 * @param channelConnectionId   owning managed connection
 * @param sourceChannel          provider/channel name (e.g. {@code TELEGRAM})
 * @param sourceExternalEventId  provider-side message/event id, if known
 * @param sourceActorExternalId  customer/contact hint (provider sender id), if known
 * @param customerAccountId      resolved customer account hint, if identity resolution found one
 * @param customerContactId      resolved customer contact hint, if identity resolution found one
 * @param requestText            normalized/sanitized business request text (no secrets/raw payload)
 * @param detectedIntent         detected intent (RFQ when available)
 */
public record CreateChannelRfqHandoffCommand(
    UUID inboundChannelEventId,
    UUID channelConnectionId,
    String sourceChannel,
    String sourceExternalEventId,
    String sourceActorExternalId,
    UUID customerAccountId,
    UUID customerContactId,
    String requestText,
    String detectedIntent) {}
