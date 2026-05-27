package com.orderpilot.api.rest;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderpilot.api.dto.Stage2Dtos.ProductMatchResponse;
import com.orderpilot.application.services.ProductCatalogService;
import com.orderpilot.common.errors.GlobalExceptionHandler;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
@Import({CoreConfiguration.class, GlobalExceptionHandler.class, NoopApiPermissionTestConfig.class})
class ProductCatalogControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockBean private ProductCatalogService service;

  @Test
  void exposesTenantScopedProductMatchEndpoint() throws Exception {
    UUID productId = UUID.randomUUID();
    when(service.match("AB-1209", null)).thenReturn(new ProductMatchResponse("ALIAS_EXACT", productId, "AB1209", "BRK-CAMRY-2018-AFT-A", "Toyota Camry 2018 Aftermarket Brake Pads A", new BigDecimal("0.95"), List.of(productId), false));

    mockMvc.perform(get("/api/v1/products/match").param("code", "AB-1209"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matchType").value("ALIAS_EXACT"))
        .andExpect(jsonPath("$.productId").value(productId.toString()))
        .andExpect(jsonPath("$.normalizedCode").value("AB1209"));
  }
}
