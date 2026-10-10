package com.orderpilot.aibot.api.model;

import java.time.Instant;
import java.util.List;

public record BotDefinitionVersionResponse(
    String botPublicId,
    int version,
    String state,
    String name,
    List<IntentView> intents,
    List<String> responsePolicyKeys,
    HandoffPolicyView handoffPolicy,
    List<String> validationFindings,
    ProvenanceSummary provenance,
    Instant updatedAt) {

  public record IntentView(
      String intentKey,
      String description,
      String actionKey,
      String responsePolicyKey,
      boolean handoffOnLowConfidence) {}

  public record HandoffPolicyView(
      boolean onUnknownIntent, boolean onSafetyRisk, boolean onExplicitHumanRequest) {}

  public record ProvenanceSummary(String provider, String model, String schemaVersion) {}
}

