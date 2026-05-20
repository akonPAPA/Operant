package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage8Dtos.CommerceAnalyticsSummaryResponse;
import com.orderpilot.application.services.analytics.CommerceAnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics/commerce")
public class CommerceAnalyticsController {
  private final CommerceAnalyticsService service;

  public CommerceAnalyticsController(CommerceAnalyticsService service) {
    this.service = service;
  }

  @GetMapping("/summary")
  public CommerceAnalyticsSummaryResponse summary() {
    return service.summary();
  }
}
