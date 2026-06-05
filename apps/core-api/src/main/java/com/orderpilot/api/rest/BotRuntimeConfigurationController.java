package com.orderpilot.api.rest;

import com.orderpilot.api.dto.BotRuntimeConfigurationDtos.BotRuntimeConfigurationListItem;
import com.orderpilot.api.dto.BotRuntimeConfigurationDtos.BotRuntimeConfigurationResponse;
import com.orderpilot.api.dto.BotRuntimeConfigurationDtos.BotRuntimeConfigurationUpdateRequest;
import com.orderpilot.application.services.channel.BotRuntimeConfigurationService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OP-CAP-06B Controlled Bot Runtime Configuration API.
 *
 * <p>Routed under {@code /api/v1/bot-runtime/**} so it inherits the existing permission mapping:
 * GET requires {@code BOT_READ} and any mutation requires {@code BOT_ACTION}. Tenant is always
 * resolved server-side from {@code TenantContext} (the {@code X-Tenant-Id} header); the request
 * body is never trusted for tenant authority and never carries secrets.
 */
@RestController
@RequestMapping("/api/v1/bot-runtime/configurations")
public class BotRuntimeConfigurationController {
  private final BotRuntimeConfigurationService configurationService;

  public BotRuntimeConfigurationController(BotRuntimeConfigurationService configurationService) {
    this.configurationService = configurationService;
  }

  /** List connections eligible for bot runtime configuration and their config status. */
  @GetMapping
  public List<BotRuntimeConfigurationListItem> list() {
    return configurationService.listEligible();
  }

  /** Read (or lazily create a safe default for) one connection's configuration. */
  @GetMapping("/{connectionId}")
  public BotRuntimeConfigurationResponse get(@PathVariable UUID connectionId) {
    return configurationService.getForConnection(connectionId);
  }

  /** Update the safe, controlled configuration fields for one connection. */
  @PutMapping("/{connectionId}")
  public BotRuntimeConfigurationResponse update(@PathVariable UUID connectionId,
      @RequestBody BotRuntimeConfigurationUpdateRequest request) {
    return configurationService.updateForConnection(connectionId, request);
  }

  /** Reset one connection's configuration back to the safe defaults. */
  @PostMapping("/{connectionId}/reset-defaults")
  public BotRuntimeConfigurationResponse resetDefaults(@PathVariable UUID connectionId) {
    return configurationService.resetDefaults(connectionId);
  }
}
