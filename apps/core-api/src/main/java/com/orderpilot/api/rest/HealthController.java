package com.orderpilot.api.rest;

import com.orderpilot.api.dto.HealthResponse;
import java.time.Clock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {
  private final Clock clock;

  public HealthController(Clock clock) {
    this.clock = clock;
  }

  @GetMapping
  public HealthResponse health() {
    return new HealthResponse("UP", "orderpilot-core-api", clock.instant());
  }
}