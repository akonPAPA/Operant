package com.orderpilot.application.services.channel;

public record NormalizedChannelEvent(
    String externalEventId,
    String sourceActorType,
    String sourceActorExternalId,
    String normalizedText,
    String rawPayloadJson) {}
