package com.orderpilot.aibot.domain.capability;

public record BotCapabilityResult(
    String outcome, String responseDraft, boolean handoffRequired) {}
