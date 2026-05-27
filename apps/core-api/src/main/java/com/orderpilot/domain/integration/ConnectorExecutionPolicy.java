package com.orderpilot.domain.integration;

import java.util.List;

public record ConnectorExecutionPolicy(
    ConnectorExecutionMode executionMode,
    List<ConnectorCapability> capabilities,
    boolean productionWritesEnabled,
    boolean networkCallsAllowed,
    int maxAttempts
) {
  public static ConnectorExecutionPolicy stage9DemoPolicy() {
    return new ConnectorExecutionPolicy(
        ConnectorExecutionMode.DEMO_ONLY,
        List.of(
            ConnectorCapability.READ_CUSTOMERS,
            ConnectorCapability.READ_PRODUCTS,
            ConnectorCapability.READ_INVENTORY,
            ConnectorCapability.CREATE_DRAFT_QUOTE,
            ConnectorCapability.CREATE_DRAFT_ORDER,
            ConnectorCapability.FETCH_STATUS),
        false,
        false,
        3);
  }
}
