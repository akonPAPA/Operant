package com.orderpilot.application.services.integration;

import com.orderpilot.domain.integration.IntegrationConnection;
import com.orderpilot.domain.integration.IntegrationProviderType;

abstract class Stage12BusinessSystemAdapterSupport implements BusinessSystemAdapter {
  private final IntegrationProviderType providerType;
  Stage12BusinessSystemAdapterSupport(IntegrationProviderType providerType) { this.providerType = providerType; }
  @Override public IntegrationProviderType providerType() { return providerType; }
  @Override public ConnectorHealthCheckResult healthCheck(IntegrationConnection connection) { return new ConnectorHealthCheckResult(providerType, true, "ADAPTER_READY_STUB", "No external network call performed"); }
  @Override public ConnectorSyncResult importProducts(IntegrationConnection connection) { return ConnectorSyncResult.stubbedReadOnly("Products import stub recorded only"); }
  @Override public ConnectorSyncResult importCustomers(IntegrationConnection connection) { return ConnectorSyncResult.stubbedReadOnly("Customers import stub recorded only"); }
  @Override public ConnectorSyncResult importInventory(IntegrationConnection connection) { return ConnectorSyncResult.stubbedReadOnly("Inventory import stub recorded only"); }
  @Override public ConnectorSyncResult importPrices(IntegrationConnection connection) { return ConnectorSyncResult.stubbedReadOnly("Prices import stub recorded only"); }
}
