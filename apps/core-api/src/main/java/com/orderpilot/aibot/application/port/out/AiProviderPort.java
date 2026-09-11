package com.orderpilot.aibot.application.port.out;

import com.orderpilot.aibot.domain.aijob.AiJobPurpose;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public interface AiProviderPort {
  ProviderResult generateStructured(AiProviderRequest request);

  record AiProviderRequest(
      AiJobPurpose purpose,
      String systemPolicyId,
      String schemaVersion,
      String minimizedUserContent,
      List<String> approvedIntentCatalogue,
      List<String> approvedCapabilityCatalogue,
      String locale,
      String correlationReference,
      int maximumOutputTokens) {}

  record ProviderResult(
      String provider,
      String model,
      String normalizedResponseText,
      String providerRequestId,
      Map<String, Object> usageSummary,
      Duration latency,
      String finishState) {}
}
