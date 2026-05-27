package com.orderpilot.application.services.integration;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.connector.*;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.integration.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntegrationConnectionService {
  private final IntegrationConnectionRepository repository;
  private final AuditEventService auditEventService;
  private final SecretVaultService secretVaultService;
  private final Map<IntegrationProviderType, BusinessSystemAdapter> adapters;
  private final Clock clock;

  public IntegrationConnectionService(IntegrationConnectionRepository repository, AuditEventService auditEventService, SecretVaultService secretVaultService, List<BusinessSystemAdapter> adapters, Clock clock) {
    this.repository = repository;
    this.auditEventService = auditEventService;
    this.secretVaultService = secretVaultService;
    this.adapters = adapters.stream().collect(Collectors.toMap(BusinessSystemAdapter::providerType, Function.identity(), (a, b) -> a));
    this.clock = clock;
  }

  @Transactional
  public IntegrationConnection createDraft(IntegrationProviderType providerType, String displayName, String connectionKind, String secretRef, String endpointRef) {
    if (providerType == null) throw new IllegalArgumentException("providerType is required");
    if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("displayName is required");
    IntegrationConnection saved = repository.save(new IntegrationConnection(TenantContext.requireTenantId(), providerType, displayName.trim(), connectionKind, secretRef, endpointRef, clock.instant()));
    auditEventService.record("INTEGRATION_CONNECTION_CREATED", "INTEGRATION_CONNECTION", saved.getId().toString(), null, "{\"providerType\":\"" + providerType + "\",\"mode\":\"READ_ONLY\"}");
    return saved;
  }

  @Transactional public IntegrationConnection activate(UUID id) { IntegrationConnection c = get(id); requireValidConfig(c); c.activate(clock.instant()); auditEventService.record("INTEGRATION_CONNECTION_ACTIVATED", "INTEGRATION_CONNECTION", c.getId().toString(), null, "{}"); return c; }
  @Transactional public IntegrationConnection pause(UUID id) { IntegrationConnection c = get(id); c.pause(clock.instant()); auditEventService.record("INTEGRATION_CONNECTION_PAUSED", "INTEGRATION_CONNECTION", c.getId().toString(), null, "{}"); return c; }
  @Transactional public IntegrationConnection disable(UUID id) { IntegrationConnection c = get(id); c.disable(clock.instant()); auditEventService.record("INTEGRATION_CONNECTION_DISABLED", "INTEGRATION_CONNECTION", c.getId().toString(), null, "{}"); return c; }
  @Transactional(readOnly = true) public List<IntegrationConnection> list() { return repository.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId()); }
  @Transactional(readOnly = true) public IntegrationConnection get(UUID id) { return repository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new IllegalArgumentException("Integration connection not found")); }

  @Transactional
  public IntegrationConnection configureSecret(UUID id, String secretValue) {
    IntegrationConnection connection = get(id);
    SecretReference reference = secretVaultService.storeSecret("integration", connection.getId().toString(), secretValue);
    connection.configureSecret(reference.secretReferenceId(), reference.lastUpdatedAt());
    auditEventService.record("INTEGRATION_SECRET_CONFIGURED", "INTEGRATION_CONNECTION", connection.getId().toString(), null, "{\"secretConfigured\":true}");
    return connection;
  }

  @Transactional
  public ConnectionHealthCheckResult recordHealthCheck(UUID id) {
    IntegrationConnection connection = get(id);
    BusinessSystemAdapter adapter = adapters.get(connection.getProviderType());
    ConnectorHealthCheckResult result = adapter == null ? new ConnectorHealthCheckResult(connection.getProviderType(), true, "NO_ADAPTER_STUB", "No adapter registered; treated as adapter-ready stub") : adapter.healthCheck(connection);
    List<ConnectionDiagnostic> diagnostics = diagnosticsFor(connection, result);
    String statusCode = diagnostics.stream().anyMatch(d -> d.severity() == DiagnosticSeverity.ERROR) ? "ERROR" : result.statusCode();
    connection.recordHealthCheck(result.healthy() && !"ERROR".equals(statusCode), statusCode, diagnostics.stream().map(d -> d.code().name()).collect(Collectors.joining(",")), clock.instant());
    auditEventService.record("INTEGRATION_HEALTH_CHECK_RUN", "INTEGRATION_CONNECTION", connection.getId().toString(), null, "{\"statusCode\":\"" + statusCode + "\"}");
    return new ConnectionHealthCheckResult(connection.getProviderType().name(), result.healthy(), statusCode, result.message(), connection.getLastHealthCheckAt(), diagnostics);
  }

  private static void requireValidConfig(IntegrationConnection connection) {
    if (connection.getDisplayName() == null || connection.getDisplayName().isBlank()) throw new IllegalArgumentException("Integration connection displayName is required before activation");
    if (connection.getConnectionKind() == null || connection.getConnectionKind().isBlank()) throw new IllegalArgumentException("Integration connection kind is required before activation");
  }

  private List<ConnectionDiagnostic> diagnosticsFor(IntegrationConnection connection, ConnectorHealthCheckResult adapterResult) {
    List<ConnectionDiagnostic> diagnostics = new ArrayList<>();
    if (!secretVaultService.isConfigured(connection.getSecretReferenceId())) diagnostics.add(new ConnectionDiagnostic(DiagnosticSeverity.WARNING, DiagnosticCode.SECRET_MISSING, "No connector secret is configured."));
    if ("READ_ONLY".equals(connection.getMode())) diagnostics.add(new ConnectionDiagnostic(DiagnosticSeverity.INFO, DiagnosticCode.READ_ONLY_MODE, "Connector is read-only; syncs only read provider data."));
    if ("WRITE_ENABLED".equals(connection.getMode()) || "DRAFT_WRITE".equals(connection.getMode())) diagnostics.add(new ConnectionDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCode.WRITE_MODE_NOT_ALLOWED, "External writes are not allowed in Stage 13."));
    if (!adapterResult.healthy()) diagnostics.add(new ConnectionDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCode.PROVIDER_UNREACHABLE, "Provider adapter reported an unhealthy status."));
    return diagnostics;
  }
}
