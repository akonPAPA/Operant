package com.orderpilot.application.services.integration;

import com.orderpilot.domain.integration.IntegrationProviderType;
import org.springframework.stereotype.Component;

@Component public class OneCIntegrationAdapter extends Stage12BusinessSystemAdapterSupport { public OneCIntegrationAdapter() { super(IntegrationProviderType.ONE_C); } }
