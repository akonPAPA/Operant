package com.orderpilot.aibot.api.model;

import java.util.List;

public record GenerateBotDefinitionRequest(
    String desiredBehavior, List<String> allowedIntentKeys, String idempotencyKey) {}
