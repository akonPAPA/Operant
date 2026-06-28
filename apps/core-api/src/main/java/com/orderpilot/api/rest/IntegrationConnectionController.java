package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage12Dtos.*;
import com.orderpilot.application.services.connector.ConnectionDiagnostic;
import com.orderpilot.application.services.integration.*;
import com.orderpilot.domain.integration.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/integrations")
public class IntegrationConnectionController {
  private final IntegrationConnectionService connectionService;
  private final ConnectorSyncEventService syncEventService;
  public IntegrationConnectionController(IntegrationConnectionService connectionService, ConnectorSyncEventService syncEventService) { this.connectionService = connectionService; this.syncEventService = syncEventService; }

  @GetMapping("/providers") public List<ProviderResponse> providers() { return Arrays.stream(IntegrationProviderType.values()).map(p -> new ProviderResponse(p.name(), label(p.name()), "ADAPTER_READY_STUB", "READ_ONLY")).toList(); }
  @GetMapping("/connections") public List<IntegrationConnectionResponse> list() { return connectionService.list().stream().map(this::toResponse).toList(); }
  @PostMapping("/connections") public IntegrationConnectionResponse create(@RequestBody IntegrationConnectionRequest request) { return toResponse(connectionService.createDraft(IntegrationProviderType.valueOf(request.providerType()), request.displayName(), request.connectionKind(), null, request.endpointRef())); }
  @GetMapping("/connections/{id}") public IntegrationConnectionResponse get(@PathVariable UUID id) { return toResponse(connectionService.get(id)); }
  @PostMapping("/connections/{id}/secret") public IntegrationConnectionResponse configureSecret(@PathVariable UUID id, @RequestBody SecretConfigurationRequest request) { return toResponse(connectionService.configureSecret(id, request.secretValue())); }
  @PostMapping("/connections/{id}/activate") public IntegrationConnectionResponse activate(@PathVariable UUID id) { return toResponse(connectionService.activate(id)); }
  @PostMapping("/connections/{id}/pause") public IntegrationConnectionResponse pause(@PathVariable UUID id) { return toResponse(connectionService.pause(id)); }
  @PostMapping("/connections/{id}/disable") public IntegrationConnectionResponse disable(@PathVariable UUID id) { return toResponse(connectionService.disable(id)); }
  @PostMapping("/connections/{id}/health-check") public IntegrationHealthResponse health(@PathVariable UUID id) { var r = connectionService.recordHealthCheck(id); return new IntegrationHealthResponse(r.providerType(), r.healthy(), r.statusCode(), r.message(), r.checkedAt(), r.diagnostics().stream().map(IntegrationConnectionController::toDiagnosticResponse).toList()); }
  @GetMapping("/sync-events") public List<ConnectorSyncEventResponse> syncEvents() { return syncEventService.list().stream().map(this::toResponse).toList(); }
  @PostMapping("/connections/{id}/sync/products") public ConnectorSyncEventResponse syncProducts(@PathVariable UUID id) { return toResponse(syncEventService.runImport(id, "PRODUCT_IMPORT")); }
  @PostMapping("/connections/{id}/sync/customers") public ConnectorSyncEventResponse syncCustomers(@PathVariable UUID id) { return toResponse(syncEventService.runImport(id, "CUSTOMER_IMPORT")); }
  @PostMapping("/connections/{id}/sync/inventory") public ConnectorSyncEventResponse syncInventory(@PathVariable UUID id) { return toResponse(syncEventService.runImport(id, "INVENTORY_IMPORT")); }
  @PostMapping("/connections/{id}/sync/prices") public ConnectorSyncEventResponse syncPrices(@PathVariable UUID id) { return toResponse(syncEventService.runImport(id, "PRICE_IMPORT")); }

  private IntegrationConnectionResponse toResponse(IntegrationConnection c) {
    String referenceId = c.getSecretReferenceId() == null ? c.getSecretRef() : c.getSecretReferenceId();
    return new IntegrationConnectionResponse(c.getId(), c.getProviderType().name(), c.getDisplayName(), c.getStatus(), c.getMode(), c.getConnectionKind(), referenceId != null && !referenceId.isBlank(), c.getSecretLastUpdatedAt(), c.getEndpointRef(), c.getLastSyncAt(), c.getLastHealthCheckAt(), c.getLastHealthCheckStatus(), c.getLastDiagnosticSummary(), c.getCreatedAt(), c.getUpdatedAt());
  }

  private ConnectorSyncEventResponse toResponse(ConnectorSyncEvent e) {
    return new ConnectorSyncEventResponse(e.getId(), e.getIntegrationConnectionId(), e.getProviderType().name(), e.getSyncType(), e.getDirection(), e.getStatus(), e.getRecordsRead(), e.getRecordsWritten(), e.getRecordsFailed(), e.getDurationMs(), e.getErrorCategory(), e.getStartedAt(), e.getFinishedAt(), e.getErrorCode());
  }

  private static ConnectionDiagnosticResponse toDiagnosticResponse(ConnectionDiagnostic diagnostic) { return new ConnectionDiagnosticResponse(diagnostic.severity().name(), diagnostic.code().name(), diagnostic.message()); }
  private static String label(String value) { return value.replace('_', ' '); }
}
