package com.orderpilot.application.services.channel;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.connector.*;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.channel.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChannelConnectionService {
  private final ChannelConnectionRepository repository;
  private final AuditEventService auditEventService;
  private final SecretVaultService secretVaultService;
  private final Map<ChannelProviderType, ChannelAdapter<?>> adapters;
  private final Clock clock;

  public ChannelConnectionService(ChannelConnectionRepository repository, AuditEventService auditEventService, SecretVaultService secretVaultService, List<ChannelAdapter<?>> adapters, Clock clock) {
    this.repository = repository;
    this.auditEventService = auditEventService;
    this.secretVaultService = secretVaultService;
    this.adapters = adapters.stream().collect(Collectors.toMap(ChannelAdapter::providerType, Function.identity(), (a, b) -> a));
    this.clock = clock;
  }

  @Transactional
  public ChannelConnection createDraft(ChannelProviderType providerType, String displayName, String externalAccountId, String webhookUrl, String secretRef) {
    return createDraft(providerType, displayName, externalAccountId, webhookUrl, secretRef, "DISABLED_FOR_LOCAL_DEV");
  }

  @Transactional
  public ChannelConnection createDraft(ChannelProviderType providerType, String displayName, String externalAccountId, String webhookUrl, String secretRef, String webhookVerificationMode) {
    if (providerType == null) throw new IllegalArgumentException("providerType is required");
    if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("displayName is required");
    ChannelConnection saved = repository.save(new ChannelConnection(TenantContext.requireTenantId(), providerType, displayName.trim(), externalAccountId, webhookUrl, secretRef, clock.instant()));
    saved.configureWebhookVerificationMode(webhookVerificationMode == null || webhookVerificationMode.isBlank() ? "DISABLED_FOR_LOCAL_DEV" : webhookVerificationMode, clock.instant());
    auditEventService.record("CHANNEL_CONNECTION_CREATED", "CHANNEL_CONNECTION", saved.getId().toString(), null, "{\"providerType\":\"" + providerType + "\",\"mode\":\"READ_ONLY\"}");
    return saved;
  }

  @Transactional public ChannelConnection activate(UUID id) { ChannelConnection c = get(id); requireValidConfig(c); c.activate(clock.instant()); auditEventService.record("CHANNEL_CONNECTION_ACTIVATED", "CHANNEL_CONNECTION", c.getId().toString(), null, "{}"); return c; }
  @Transactional public ChannelConnection pause(UUID id) { ChannelConnection c = get(id); c.pause(clock.instant()); auditEventService.record("CHANNEL_CONNECTION_PAUSED", "CHANNEL_CONNECTION", c.getId().toString(), null, "{}"); return c; }
  @Transactional public ChannelConnection disable(UUID id) { ChannelConnection c = get(id); c.disable(clock.instant()); auditEventService.record("CHANNEL_CONNECTION_DISABLED", "CHANNEL_CONNECTION", c.getId().toString(), null, "{}"); return c; }
  @Transactional(readOnly = true) public List<ChannelConnection> list() { return repository.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId()); }
  @Transactional(readOnly = true) public ChannelConnection get(UUID id) { return repository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new IllegalArgumentException("Channel connection not found")); }

  @Transactional
  public ChannelConnection configureSecret(UUID id, String secretValue) {
    ChannelConnection connection = get(id);
    SecretReference reference = secretVaultService.storeSecret("channel", connection.getId().toString(), secretValue);
    connection.configureSecret(reference.secretReferenceId(), reference.lastUpdatedAt());
    auditEventService.record("CHANNEL_SECRET_CONFIGURED", "CHANNEL_CONNECTION", connection.getId().toString(), null, "{\"secretConfigured\":true}");
    return connection;
  }

  @Transactional
  public ConnectionHealthCheckResult recordHealthCheck(UUID id) {
    ChannelConnection connection = get(id);
    ChannelAdapter<?> adapter = adapters.get(connection.getProviderType());
    ChannelHealthCheckResult result = adapter == null ? new ChannelHealthCheckResult(connection.getProviderType(), true, "NO_ADAPTER_STUB", "No adapter registered; treated as adapter-ready stub") : adapter.healthCheck(connection);
    List<ConnectionDiagnostic> diagnostics = diagnosticsFor(connection, result);
    String statusCode = diagnostics.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR) ? "ERROR" : result.statusCode();
    connection.recordHealthCheck(result.healthy() && !"ERROR".equals(statusCode), statusCode, diagnostics.stream().map(d -> d.code().name()).collect(Collectors.joining(",")), clock.instant());
    auditEventService.record("CHANNEL_HEALTH_CHECK_RUN", "CHANNEL_CONNECTION", connection.getId().toString(), null, "{\"statusCode\":\"" + statusCode + "\"}");
    return new ConnectionHealthCheckResult(connection.getProviderType().name(), result.healthy(), statusCode, result.message(), connection.getLastHealthCheckAt(), diagnostics);
  }

  private static void requireValidConfig(ChannelConnection connection) {
    if (connection.getDisplayName() == null || connection.getDisplayName().isBlank()) throw new IllegalArgumentException("Channel connection displayName is required before activation");
  }

  private List<ConnectionDiagnostic> diagnosticsFor(ChannelConnection connection, ChannelHealthCheckResult adapterResult) {
    List<ConnectionDiagnostic> diagnostics = new ArrayList<>();
    if (connection.getWebhookUrl() == null || connection.getWebhookUrl().isBlank()) diagnostics.add(new ConnectionDiagnostic(DiagnosticSeverity.WARNING, DiagnosticCode.WEBHOOK_NOT_CONFIGURED, "Webhook URL is not configured."));
    if ("DISABLED_FOR_LOCAL_DEV".equals(connection.getWebhookVerificationMode())) diagnostics.add(new ConnectionDiagnostic(DiagnosticSeverity.WARNING, DiagnosticCode.WEBHOOK_VERIFICATION_DISABLED, "Webhook verification is explicitly relaxed for local development."));
    if (!secretVaultService.isConfigured(connection.getSecretReferenceId())) diagnostics.add(new ConnectionDiagnostic(DiagnosticSeverity.WARNING, DiagnosticCode.SECRET_MISSING, "No connector secret is configured."));
    if ("READ_ONLY".equals(connection.getMode())) diagnostics.add(new ConnectionDiagnostic(DiagnosticSeverity.INFO, DiagnosticCode.READ_ONLY_MODE, "Connection is read-only; inbound payloads are normalized only."));
    if (!adapterResult.healthy()) diagnostics.add(new ConnectionDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCode.PROVIDER_UNREACHABLE, "Provider adapter reported an unhealthy status."));
    return diagnostics;
  }
}
