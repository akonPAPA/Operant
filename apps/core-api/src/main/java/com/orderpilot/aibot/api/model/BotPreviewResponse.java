package com.orderpilot.aibot.api.model;

public record BotPreviewResponse(
    String intent,
    double confidence,
    String outcome,
    String responseDraft,
    boolean handoffRequired,
    String traceReference) {}
