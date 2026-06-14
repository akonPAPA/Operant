package com.orderpilot.api.rest;

import com.orderpilot.api.dto.CommandCenterDtos.CommandCenterSummaryDto;
import com.orderpilot.application.services.analytics.CommandCenterReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-21 — read-only Transaction Command Center summary.
 *
 * <p>Tenant-scoped and permission-protected (ANALYTICS_READ via {@code ApiPermissionInterceptor};
 * the whole {@code /api/v1/command-center} prefix is GET-only). No mutation, no external/AI/connector
 * calls. Errors flow through the existing {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/command-center")
public class CommandCenterController {
  private final CommandCenterReadService service;

  public CommandCenterController(CommandCenterReadService service) {
    this.service = service;
  }

  @GetMapping("/summary")
  public CommandCenterSummaryDto summary() {
    return service.summary();
  }
}
