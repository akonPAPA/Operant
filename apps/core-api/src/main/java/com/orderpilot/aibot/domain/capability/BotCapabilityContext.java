package com.orderpilot.aibot.domain.capability;

import java.util.Map;
import java.util.UUID;

public record BotCapabilityContext(
    UUID tenantId,
    String botPublicId,
    int version,
    String intentKey,
    double confidence,
    String locale,
    String normalizedMessage,
    Map<String, String> entities,
    boolean previewMode) {}
