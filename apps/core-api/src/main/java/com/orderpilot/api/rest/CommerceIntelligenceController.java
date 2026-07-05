package com.orderpilot.api.rest;

import com.orderpilot.api.dto.CommerceIntelligenceDtos.CommerceIntelligenceDemoFlowResponse;
import com.orderpilot.application.services.commerce.CommerceIntelligenceDemoFlowService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/commerce-intelligence")
public class CommerceIntelligenceController {
  private final CommerceIntelligenceDemoFlowService service;

  public CommerceIntelligenceController(CommerceIntelligenceDemoFlowService service) {
    this.service = service;
  }

  @GetMapping("/demo-flow")
  public CommerceIntelligenceDemoFlowResponse demoFlow() {
    return service.readDemoFlow();
  }
}
