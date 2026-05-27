package com.orderpilot.application.services.integration;

import com.orderpilot.domain.integration.IntegrationConnection;
import com.orderpilot.domain.integration.IntegrationProviderType;
import org.springframework.stereotype.Component;

@Component
public class DemoErpIntegrationAdapter extends Stage12BusinessSystemAdapterSupport {
  public DemoErpIntegrationAdapter() { super(IntegrationProviderType.OTHER_ERP); }

  @Override public ConnectorSyncResult importProducts(IntegrationConnection connection) { return new ConnectorSyncResult(3, 0, 0, "READ_ONLY_PILOT", "Demo ERP read-only pilot fetched product summaries only"); }
  @Override public ConnectorSyncResult importCustomers(IntegrationConnection connection) { return new ConnectorSyncResult(2, 0, 0, "READ_ONLY_PILOT", "Demo ERP read-only pilot fetched customer summaries only"); }
  @Override public ConnectorSyncResult importInventory(IntegrationConnection connection) { return new ConnectorSyncResult(4, 0, 0, "READ_ONLY_PILOT", "Demo ERP read-only pilot fetched inventory summaries only"); }
  @Override public ConnectorSyncResult importPrices(IntegrationConnection connection) { return new ConnectorSyncResult(3, 0, 0, "READ_ONLY_PILOT", "Demo ERP read-only pilot fetched price summaries only"); }
}
