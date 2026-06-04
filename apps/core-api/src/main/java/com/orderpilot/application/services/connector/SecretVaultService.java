package com.orderpilot.application.services.connector;

public interface SecretVaultService {
  SecretReference storeSecret(String scope, String ownerId, String secretValue);
  boolean isConfigured(String secretReferenceId);
}
