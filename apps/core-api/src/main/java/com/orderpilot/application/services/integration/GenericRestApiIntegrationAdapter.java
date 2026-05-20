package com.orderpilot.application.services.integration;

import com.orderpilot.domain.integration.IntegrationProviderType;
import org.springframework.stereotype.Component;

@Component public class GenericRestApiIntegrationAdapter extends Stage12BusinessSystemAdapterSupport { public GenericRestApiIntegrationAdapter() { super(IntegrationProviderType.GENERIC_REST_API); } }
