package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage8Dtos.*;
import com.orderpilot.application.services.analytics.BusinessValueAnalyticsService;
import com.orderpilot.application.services.analytics.RoiAssumptionsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Stage8ValueAnalyticsController {
  private final BusinessValueAnalyticsService valueAnalyticsService;
  private final RoiAssumptionsService assumptionsService;

  public Stage8ValueAnalyticsController(BusinessValueAnalyticsService valueAnalyticsService, RoiAssumptionsService assumptionsService) {
    this.valueAnalyticsService = valueAnalyticsService;
    this.assumptionsService = assumptionsService;
  }

  @GetMapping("/api/stage8/value/summary")
  public Stage8ValueSummaryResponse summary() {
    return valueAnalyticsService.summary();
  }

  @GetMapping("/api/stage8/value/roi-assumptions")
  public RoiAssumptionsResponse roiAssumptions() {
    return assumptionsService.current();
  }

  @PutMapping("/api/stage8/value/roi-assumptions")
  public RoiAssumptionsResponse updateRoiAssumptions(@RequestBody RoiAssumptionsRequest request) {
    return assumptionsService.update(request);
  }

  @GetMapping("/api/stage8/value/leakage")
  public Stage8ValueLeakageResponse leakage() {
    return valueAnalyticsService.leakage();
  }

  @GetMapping("/api/stage8/value/productivity")
  public Stage8ValueProductivityResponse productivity() {
    return valueAnalyticsService.productivity();
  }

  @GetMapping("/api/stage8/value/export")
  public Stage8PilotRoiReportResponse export() {
    return valueAnalyticsService.export();
  }
}
