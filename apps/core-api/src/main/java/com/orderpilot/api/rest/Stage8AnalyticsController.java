package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage8Dtos.*;
import com.orderpilot.application.services.analytics.CommerceAnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Stage8AnalyticsController {
  private final CommerceAnalyticsService service;

  public Stage8AnalyticsController(CommerceAnalyticsService service) {
    this.service = service;
  }

  @GetMapping("/api/stage8/analytics/command-center")
  public Stage8CommandCenterAnalyticsResponse commandCenter() {
    return service.stage8CommandCenter();
  }

  @GetMapping("/api/stage8/analytics/channel-volume")
  public Stage8ChannelVolumeResponse channelVolume() {
    return service.stage8ChannelVolume();
  }

  @GetMapping("/api/stage8/analytics/operator-review")
  public Stage8OperatorReviewAnalyticsResponse operatorReview() {
    return service.stage8OperatorReview();
  }

  @GetMapping("/api/stage8/analytics/bot-handoffs")
  public Stage8BotHandoffAnalyticsResponse botHandoffs() {
    return service.stage8BotHandoffs();
  }
}
