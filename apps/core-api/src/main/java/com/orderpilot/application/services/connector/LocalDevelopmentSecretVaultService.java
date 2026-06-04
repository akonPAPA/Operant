package com.orderpilot.application.services.connector;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class LocalDevelopmentSecretVaultService implements SecretVaultService {
  private final Map<String, String> localSecrets = new ConcurrentHashMap<>();
  private final Clock clock;

  public LocalDevelopmentSecretVaultService(Clock clock) {
    this.clock = clock;
  }

  @Override
  public SecretReference storeSecret(String scope, String ownerId, String secretValue) {
    if (secretValue == null || secretValue.isBlank()) {
      throw new IllegalArgumentException("secret value is required");
    }
    if (scope == null || scope.isBlank()) {
      throw new IllegalArgumentException("secret scope is required");
    }
    if (ownerId == null || ownerId.isBlank()) {
      throw new IllegalArgumentException("secret owner is required");
    }
    String referenceId = "local-dev://" + scope + "/" + ownerId + "/" + UUID.randomUUID();
    localSecrets.put(referenceId, secretValue);
    return new SecretReference(referenceId, true, clock.instant());
  }

  @Override
  public boolean isConfigured(String secretReferenceId) {
    return secretReferenceId != null && localSecrets.containsKey(secretReferenceId);
  }
}
