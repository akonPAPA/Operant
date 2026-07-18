package com.orderpilot.api.rest;

import com.orderpilot.api.dto.ControlInternalDtos.ControlDiagnosticsResponse;
import com.orderpilot.api.dto.ControlInternalDtos.ControlHealthResponse;
import com.orderpilot.api.dto.ControlInternalDtos.ControlReadinessResponse;
import com.orderpilot.api.dto.ControlInternalDtos.ControlStatusResponse;
import com.orderpilot.application.services.control.ControlPlaneStatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * P1-E - the bounded platform control-plane read surface under {@code /api/v1/internal/control/**},
 * consumed by operantctl. Route-edge security: every route requires a dedicated
 * {@code STAFF_CONTROL_*} permission ({@code ApiRouteSecurityPolicy#controlDecision}); tenant
 * business permissions and the per-tenant {@code STAFF_SUPPORT_*} family are denied, diagnostics
 * require a stronger permission than status reads, and write-shaped variants hit the global
 * default-deny.
 *
 * <p>Responses are platform-scoped and bounded (fixed dependency names and state tokens - never
 * hosts, URLs, paths, configuration values, or raw errors). This slice is read-only: no operation
 * here mutates any state, and no tenant data is readable, so no tenant context is consulted.
 */
@RestController
public class InternalControlController {
  private static final String BASE = "/api/v1/internal/control";

  private final ControlPlaneStatusService statusService;

  public InternalControlController(ControlPlaneStatusService statusService) {
    this.statusService = statusService;
  }

  @GetMapping(BASE + "/status")
  public ControlStatusResponse status() {
    return statusService.status();
  }

  @GetMapping(BASE + "/health")
  public ControlHealthResponse health() {
    return statusService.health();
  }

  @GetMapping(BASE + "/readiness")
  public ControlReadinessResponse readiness() {
    return statusService.readiness();
  }

  @GetMapping(BASE + "/diagnostics")
  public ControlDiagnosticsResponse diagnostics() {
    return statusService.diagnostics();
  }

  @RequestMapping(method = RequestMethod.HEAD, path = BASE + "/status")
  public ResponseEntity<Void> statusHead() {
    return ResponseEntity.ok().build();
  }

  @RequestMapping(method = RequestMethod.HEAD, path = BASE + "/health")
  public ResponseEntity<Void> healthHead() {
    return ResponseEntity.ok().build();
  }

  @RequestMapping(method = RequestMethod.HEAD, path = BASE + "/readiness")
  public ResponseEntity<Void> readinessHead() {
    return ResponseEntity.ok().build();
  }

  @RequestMapping(method = RequestMethod.HEAD, path = BASE + "/diagnostics")
  public ResponseEntity<Void> diagnosticsHead() {
    return ResponseEntity.ok().build();
  }

}
