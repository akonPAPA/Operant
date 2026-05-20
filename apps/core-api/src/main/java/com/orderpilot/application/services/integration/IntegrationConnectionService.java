package com.orderpilot.application.services.integration;

import com.orderpilot.application.services.AuditEventService;
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
  private final Map<IntegrationProviderType, BusinessSystemAdapter> adapters;
  private final Clock clock;

  public IntegrationConnectionService(IntegrationConnectionRepository repository, AuditEventService auditEventService, List<BusinessSystemAdapter> adapters, Clock clock) {
    this.repository = repository;
    this.auditEventService = auditEventService;
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
  public ConnectorHealthCheckResult recordHealthCheck(UUID id) {
    IntegrationConnection connection = get(id);
    BusinessSystemAdapter adapter = adapters.get(connection.getProviderType());
    ConnectorHealthCheckResult result = adapter == null ? new ConnectorHealthCheckResult(connection.getProviderType(), true, "NO_ADAPTER_STUB", "No adapter registered; treated as adapter-ready stub") : adapter.healthCheck(connection);
    connection.recordHealthCheck(result.healthy(), clock.instant());
    return result;
  }

  private static void requireValidConfig(IntegrationConnection connection) {
    if (connection.getDisplayName() == null || connection.getDisplayName().isBlank()) throw new IllegalArgumentException("Integration connection displayName is required before activation");
    if (connection.getConnectionKind() == null || connection.getConnectionKind().isBlank()) throw new IllegalArgumentException("Integration connection kind is required before activation");
  }
}
