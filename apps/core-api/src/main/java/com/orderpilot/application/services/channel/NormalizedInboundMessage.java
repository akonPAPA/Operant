package com.orderpilot.application.services.channel;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NormalizedInboundMessage(
    UUID tenantId,
    ChannelType channelType,
    String externalMessageId,
    String externalConversationId,
    String externalSenderId,
    String senderDisplayName,
    String senderPhone,
    String rawText,
    List<String> attachmentRefs,
    Instant receivedAt,
    String rawPayloadJson,
    String idempotencyKey) {}