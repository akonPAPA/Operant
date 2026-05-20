package com.orderpilot.application.services.integration;

import com.orderpilot.domain.integration.IntegrationConnection;
import com.orderpilot.domain.integration.IntegrationProviderType;

public interface BusinessSystemAdapter {
  IntegrationProviderType providerType();
  ConnectorHealthCheckResult healthCheck(IntegrationConnection connection);
  ConnectorSyncResult importProducts(IntegrationConnection connection);
  ConnectorSyncResult importCustomers(IntegrationConnection connection);
  ConnectorSyncResult importInventory(IntegrationConnection connection);
  ConnectorSyncResult importPrices(IntegrationConnection connection);
}
