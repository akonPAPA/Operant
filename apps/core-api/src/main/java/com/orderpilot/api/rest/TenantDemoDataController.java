package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage2Dtos.DemoTenantRequest;
import com.orderpilot.api.dto.Stage2Dtos.DemoTenantResponse;
import com.orderpilot.application.services.TenantDemoDataService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo")
public class TenantDemoDataController {
  private final TenantDemoDataService service;

  public TenantDemoDataController(TenantDemoDataService service) {
    this.service = service;
  }

  @PostMapping("/tenant")
  public DemoTenantResponse createOrReuseTenant(@RequestBody DemoTenantRequest request) {
    return service.createOrReuse(request);
  }
}
