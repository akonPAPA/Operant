package com.orderpilot.application.services.integration;

import com.orderpilot.domain.integration.IntegrationProviderType;
import org.springframework.stereotype.Component;

@Component public class CsvIntegrationAdapter extends Stage12BusinessSystemAdapterSupport { public CsvIntegrationAdapter() { super(IntegrationProviderType.CSV); } }
