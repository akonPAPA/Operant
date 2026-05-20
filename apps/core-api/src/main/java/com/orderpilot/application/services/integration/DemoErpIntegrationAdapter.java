package com.orderpilot.application.services.integration;

import com.orderpilot.domain.integration.IntegrationProviderType;
import org.springframework.stereotype.Component;

@Component public class DemoErpIntegrationAdapter extends Stage12BusinessSystemAdapterSupport { public DemoErpIntegrationAdapter() { super(IntegrationProviderType.OTHER_ERP); } }
