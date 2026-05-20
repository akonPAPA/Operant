package com.orderpilot.application.services.integration;

import com.orderpilot.domain.integration.IntegrationProviderType;
import org.springframework.stereotype.Component;

@Component public class ExcelIntegrationAdapter extends Stage12BusinessSystemAdapterSupport { public ExcelIntegrationAdapter() { super(IntegrationProviderType.EXCEL); } }
