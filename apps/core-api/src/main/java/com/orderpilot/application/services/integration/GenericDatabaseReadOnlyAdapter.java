package com.orderpilot.application.services.integration;

import com.orderpilot.domain.integration.IntegrationProviderType;
import org.springframework.stereotype.Component;

@Component public class GenericDatabaseReadOnlyAdapter extends Stage12BusinessSystemAdapterSupport { public GenericDatabaseReadOnlyAdapter() { super(IntegrationProviderType.GENERIC_DATABASE); } }
