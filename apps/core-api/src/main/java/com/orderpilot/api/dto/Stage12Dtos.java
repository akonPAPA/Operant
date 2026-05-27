package com.orderpilot.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Stage12Dtos {
  private Stage12Dtos() {}

  public record ProviderResponse(String providerType, String label, String readiness, String defaultMode) {}
  public record ConnectionDiagnosticResponse(String severity, String code, String message) {}
  public record ChannelConnectionRequest(String providerType, String displayName, String externalAccountId, String webhookUrl, String secretRef, String webhookVerificationMode) {}
  public record SecretConfigurationRequest(String secretValue) {}
  public record ChannelConnectionResponse(UUID id, String providerType, String displayName, String status, String mode, String externalAccountId, String webhookUrl, boolean secretConfigured, Instant secretLastUpdatedAt, String secretReferenceId, String webhookVerificationMode, Instant lastHealthCheckAt, String lastHealthCheckStatus, String lastDiagnosticSummary, Instant createdAt, Instant updatedAt) {}
  public record InboundChannelEventResponse(UUID id, UUID channelConnectionId, String providerType, String externalEventId, String sourceActorType, String sourceActorExternalId, String normalizedText, String payloadHash, String status, String verificationStatus, String verificationReason, Instant receivedAt, Instant processedAt, String errorCode, String errorMessage) {}
  public record ChannelHealthResponse(String providerType, boolean healthy, String statusCode, String message, Instant checkedAt, List<ConnectionDiagnosticResponse> diagnostics) {}

  public record IntegrationConnectionRequest(String providerType, String displayName, String connectionKind, String secretRef, String endpointRef) {}
  public record IntegrationConnectionResponse(UUID id, String providerType, String displayName, String status, String mode, String connectionKind, boolean secretConfigured, Instant secretLastUpdatedAt, String secretReferenceId, String endpointRef, Instant lastSyncAt, Instant lastHealthCheckAt, String lastHealthCheckStatus, String lastDiagnosticSummary, Instant createdAt, Instant updatedAt) {}
  public record IntegrationHealthResponse(String providerType, boolean healthy, String statusCode, String message, Instant checkedAt, List<ConnectionDiagnosticResponse> diagnostics) {}
  public record ConnectorSyncEventResponse(UUID id, UUID integrationConnectionId, String providerType, String syncType, String direction, String status, int recordsRead, int recordsWritten, int recordsFailed, Long durationMs, String errorCategory, Instant startedAt, Instant finishedAt, String errorCode, String errorMessage) {}
}
