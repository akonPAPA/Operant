package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage8Dtos.CommerceAnalyticsSummaryResponse;
import com.orderpilot.api.dto.Stage8Dtos.AnalyticsOverviewResponse;
import com.orderpilot.api.dto.Stage8Dtos.BotAnalyticsResponse;
import com.orderpilot.api.dto.Stage8Dtos.ExtractionAnalyticsResponse;
import com.orderpilot.api.dto.Stage8Dtos.IntakeAnalyticsResponse;
import com.orderpilot.api.dto.Stage8Dtos.ReviewAnalyticsResponse;
import com.orderpilot.api.dto.Stage8Dtos.ValidationAnalyticsResponse;
import com.orderpilot.api.dto.Stage8Dtos.WorkflowHealthAnalyticsResponse;
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

  @GetMapping("/overview")
  public AnalyticsOverviewResponse overview() {
    return service.overview();
  }

  @GetMapping("/intake")
  public IntakeAnalyticsResponse intake() {
    return service.intake();
  }

  @GetMapping("/extraction")
  public ExtractionAnalyticsResponse extraction() {
    return service.extraction();
  }

  @GetMapping("/validation")
  public ValidationAnalyticsResponse validation() {
    return service.validation();
  }

  @GetMapping("/review")
  public ReviewAnalyticsResponse review() {
    return service.review();
  }

  @GetMapping("/bot")
  public BotAnalyticsResponse bot() {
    return service.bot();
  }

  @GetMapping("/workflow-health")
  public WorkflowHealthAnalyticsResponse workflowHealth() {
    return service.workflowHealth();
  }
}
