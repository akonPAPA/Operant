package com.orderpilot.api.rest;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import com.orderpilot.infrastructure.config.CoreConfiguration;

@WebMvcTest(HealthController.class)
@Import(CoreConfiguration.class)
class HealthControllerTest {
  @Autowired
  private MockMvc mockMvc;

  @Test
  void returnsHealth() throws Exception {
    mockMvc.perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status", is("UP")))
        .andExpect(jsonPath("$.service", is("orderpilot-core-api")));
  }
}