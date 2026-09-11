package com.orderpilot.aibot.api.model;

import java.time.Instant;

public record BotDraftResponse(
    String botPublicId,
    int version,
    String state,
    String name,
    String description,
    Instant createdAt) {}
