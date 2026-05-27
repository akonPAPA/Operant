package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage8Dtos.*;
import com.orderpilot.application.services.analytics.CommerceAnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {
  private final CommerceAnalyticsService service;

  public AnalyticsController(CommerceAnalyticsService service) {
    this.service = service;
  }

  @GetMapping("/overview")
  public AnalyticsOverviewResponse overview() { return service.overview(); }

  @GetMapping("/intake")
  public IntakeAnalyticsResponse intake() { return service.intake(); }

  @GetMapping("/extraction")
  public ExtractionAnalyticsResponse extraction() { return service.extraction(); }

  @GetMapping("/validation")
  public ValidationAnalyticsResponse validation() { return service.validation(); }

  @GetMapping("/review")
  public ReviewAnalyticsResponse review() { return service.review(); }

  @GetMapping("/bot")
  public BotAnalyticsResponse bot() { return service.bot(); }

  @GetMapping("/workflow-health")
  public WorkflowHealthAnalyticsResponse workflowHealth() { return service.workflowHealth(); }
}
