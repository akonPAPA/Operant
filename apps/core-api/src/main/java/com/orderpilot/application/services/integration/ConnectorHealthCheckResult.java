package com.orderpilot.application.services.integration;

import com.orderpilot.domain.integration.IntegrationProviderType;

public record ConnectorHealthCheckResult(IntegrationProviderType providerType, boolean healthy, String statusCode, String message) {}
