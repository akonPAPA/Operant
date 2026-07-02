package com.orderpilot.application.services.integration;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.api.TenantScopedListLimits;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.integration.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConnectorSyncEventService {
  private final IntegrationConnectionRepository connectionRepository;
  private final ConnectorSyncEventRepository syncEventRepository;
  private final AuditEventService auditEventService;
  private final Map<IntegrationProviderType, BusinessSystemAdapter> adapters;
  private final Clock clock;

  public ConnectorSyncEventService(IntegrationConnectionRepository connectionRepository, ConnectorSyncEventRepository syncEventRepository, AuditEventService auditEventService, List<BusinessSystemAdapter> adapters, Clock clock) {
    this.connectionRepository = connectionRepository;
    this.syncEventRepository = syncEventRepository;
    this.auditEventService = auditEventService;
    this.adapters = adapters.stream().collect(Collectors.toMap(BusinessSystemAdapter::providerType, Function.identity(), (a, b) -> a));
    this.clock = clock;
  }

  @Transactional
  public ConnectorSyncEvent start(UUID connectionId, String syncType, String direction) {
    IntegrationConnection connection = getActiveConnection(connectionId);
    ConnectorSyncEvent event = syncEventRepository.save(new ConnectorSyncEvent(TenantContext.requireTenantId(), connectionId, connection.getProviderType(), syncType, direction, clock.instant()));
    auditEventService.record("CONNECTOR_SYNC_STARTED", "CONNECTOR_SYNC_EVENT", event.getId().toString(), null, "{\"syncType\":\"" + syncType + "\",\"externalWrites\":\"DISABLED\"}");
    return event;
  }

  @Transactional
  public ConnectorSyncEvent runImport(UUID connectionId, String syncType) {
    IntegrationConnection connection = getActiveConnection(connectionId);
    ConnectorSyncEvent event = syncEventRepository.save(new ConnectorSyncEvent(TenantContext.requireTenantId(), connectionId, connection.getProviderType(), syncType, "INBOUND", clock.instant()));
    auditEventService.record("CONNECTOR_SYNC_STARTED", "CONNECTOR_SYNC_EVENT", event.getId().toString(), null, "{\"syncType\":\"" + syncType + "\",\"externalWrites\":\"DISABLED\"}");
    try {
      BusinessSystemAdapter adapter = adapters.get(connection.getProviderType());
      ConnectorSyncResult result = switch (syncType) {
        case "PRODUCT_IMPORT" -> adapter == null ? ConnectorSyncResult.stubbedReadOnly("No adapter registered") : adapter.importProducts(connection);
        case "CUSTOMER_IMPORT" -> adapter == null ? ConnectorSyncResult.stubbedReadOnly("No adapter registered") : adapter.importCustomers(connection);
        case "INVENTORY_IMPORT" -> adapter == null ? ConnectorSyncResult.stubbedReadOnly("No adapter registered") : adapter.importInventory(connection);
        case "PRICE_IMPORT" -> adapter == null ? ConnectorSyncResult.stubbedReadOnly("No adapter registered") : adapter.importPrices(connection);
        default -> throw new IllegalArgumentException("Unsupported sync type");
      };
      event.complete(result.recordsRead(), result.recordsWritten(), result.recordsFailed(), clock.instant());
      connection.markSynced(clock.instant());
      auditEventService.record("CONNECTOR_SYNC_COMPLETED", "CONNECTOR_SYNC_EVENT", event.getId().toString(), null, "{\"status\":\"" + event.getStatus() + "\"}");
    } catch (RuntimeException ex) {
      event.fail("SYNC_FAILED", ex.getMessage(), clock.instant());
      auditEventService.record("CONNECTOR_SYNC_FAILED", "CONNECTOR_SYNC_EVENT", event.getId().toString(), null, "{\"errorCode\":\"SYNC_FAILED\"}");
    }
    return event;
  }

  @Transactional public ConnectorSyncEvent complete(UUID id, int read, int written, int failed) { ConnectorSyncEvent event = get(id); event.complete(read, written, failed, clock.instant()); auditEventService.record("CONNECTOR_SYNC_COMPLETED", "CONNECTOR_SYNC_EVENT", event.getId().toString(), null, "{}"); return event; }
  @Transactional public ConnectorSyncEvent fail(UUID id, String code, String message) { ConnectorSyncEvent event = get(id); event.fail(code, message, clock.instant()); auditEventService.record("CONNECTOR_SYNC_FAILED", "CONNECTOR_SYNC_EVENT", event.getId().toString(), null, "{\"errorCode\":\"" + code + "\"}"); return event; }
  @Transactional(readOnly = true) public List<ConnectorSyncEvent> list() { return list(TenantScopedListLimits.GENERAL_LIST_DEFAULT); }
  @Transactional(readOnly = true) public List<ConnectorSyncEvent> list(int limit) {
    int clamped = TenantScopedListLimits.clamp(limit, TenantScopedListLimits.GENERAL_LIST_DEFAULT, TenantScopedListLimits.GENERAL_LIST_MAX);
    return syncEventRepository.findByTenantIdOrderByStartedAtDesc(TenantContext.requireTenantId(), PageRequest.of(0, clamped));
  }
  @Transactional(readOnly = true) public ConnectorSyncEvent get(UUID id) { return syncEventRepository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(() -> new IllegalArgumentException("Connector sync event not found")); }

  private IntegrationConnection getActiveConnection(UUID connectionId) {
    IntegrationConnection connection = connectionRepository.findByIdAndTenantId(connectionId, TenantContext.requireTenantId()).orElseThrow(() -> new IllegalArgumentException("Integration connection not found"));
    if (!"ACTIVE".equals(connection.getStatus())) throw new IllegalArgumentException("Integration connection must be ACTIVE to run sync");
    if (!"READ_ONLY".equals(connection.getMode())) throw new IllegalArgumentException("Only READ_ONLY connector syncs are allowed in Stage 13");
    return connection;
  }
}
