package com.orderpilot.api.rest;

import com.orderpilot.api.dto.DemoRfqHandoffDtos.DemoRfqHandoffResponse;
import com.orderpilot.application.services.channel.LocalDemoRfqIntakeService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.security.DemoRfqHandoffRuntimeGate;
import com.orderpilot.security.RequestActorResolver;
import com.orderpilot.security.policy.TenantPolicyException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Narrow authenticated local/demo entrypoint. The route itself is the complete business intent;
 * there is intentionally no request DTO or request body.
 */
@RestController
public class DemoRfqHandoffController {
  private final LocalDemoRfqIntakeService intakeService;
  private final RequestActorResolver actorResolver;
  private final DemoRfqHandoffRuntimeGate runtimeGate;

  public DemoRfqHandoffController(
      LocalDemoRfqIntakeService intakeService,
      RequestActorResolver actorResolver,
      DemoRfqHandoffRuntimeGate runtimeGate) {
    this.intakeService = intakeService;
    this.actorResolver = actorResolver;
    this.runtimeGate = runtimeGate;
  }

  @PostMapping("/api/v1/demo/rfq-handoff")
  public DemoRfqHandoffResponse create(HttpServletRequest http) {
    runtimeGate.requireEnabled();
    UUID actorId =
        actorResolver.resolveVerifiedLocalDemoOperator(
            http, TenantContext.requireTenantId());
    if (RequestActorResolver.SYSTEM_ACTOR.equals(actorId)) {
      throw new TenantPolicyException("Tenant operator actor is required");
    }
    return intakeService.createOrGet(actorId);
  }
}
