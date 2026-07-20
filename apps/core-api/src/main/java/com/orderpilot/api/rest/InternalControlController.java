package com.orderpilot.api.rest;

import com.orderpilot.api.dto.ControlInternalDtos.ControlDiagnosticsResponse;
import com.orderpilot.api.dto.ControlInternalDtos.ControlHealthResponse;
import com.orderpilot.api.dto.ControlInternalDtos.ControlReadinessResponse;
import com.orderpilot.api.dto.ControlInternalDtos.ControlStatusResponse;
import com.orderpilot.api.dto.ControlInternalDtos.OperationalEventPage;
import com.orderpilot.application.services.control.ControlPlaneStatusService;
import com.orderpilot.application.services.control.OperationalEventAccessAuditor;
import com.orderpilot.application.services.control.OperationalEventReadService;
import com.orderpilot.application.services.control.OperationalEventReadService.InvalidOperationalEventQueryException;
import com.orderpilot.application.services.control.OperationalEventRecorder;
import com.orderpilot.security.ControlPlanePrincipal;
import java.util.Set;
import org.springframework.context.annotation.Import;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P1-E - the bounded platform control-plane read surface under {@code /api/v1/internal/control/**},
 * consumed by the human-operated operantctl (Operant Support & Maintenance access plane). Route-edge
 * security: every route requires a dedicated {@code STAFF_CONTROL_*} permission
 * ({@code ApiRouteSecurityPolicy#controlDecision}); tenant business permissions and the per-tenant
 * {@code STAFF_SUPPORT_*} family are denied, and write-shaped variants hit the global default-deny.
 *
 * <p>The operational-event projection is a bounded, process-local history of changed states sampled
 * when the authenticated status/readiness endpoints are polled. It is not raw logging and not an
 * independent background incident detector. The first sampled value establishes a baseline; only a
 * later changed sample becomes an event.
 */
@RestController
@Import(OperationalEventAccessAuditor.class)
public class InternalControlController {
  private static final String BASE = "/api/v1/internal/control";
  private static final Set<String> ALLOWED_EVENT_QUERY_PARAMS =
      Set.of("severity", "component", "eventCode", "limit", "before");

  private final ControlPlaneStatusService statusService;
  private final OperationalEventReadService operationalEventReadService;
  private final OperationalEventRecorder operationalEventRecorder;
  private final OperationalEventAccessAuditor operationalEventAccessAuditor;

  public InternalControlController(
      ControlPlaneStatusService statusService,
      OperationalEventReadService operationalEventReadService,
      OperationalEventRecorder operationalEventRecorder,
      OperationalEventAccessAuditor operationalEventAccessAuditor) {
    this.statusService = statusService;
    this.operationalEventReadService = operationalEventReadService;
    this.operationalEventRecorder = operationalEventRecorder;
    this.operationalEventAccessAuditor = operationalEventAccessAuditor;
  }

  @GetMapping(BASE + "/status")
  public ControlStatusResponse status() {
    ControlStatusResponse response = statusService.status();
    operationalEventRecorder.observeDependencies(response.dependencies());
    return response;
  }

  @GetMapping(BASE + "/health")
  public ControlHealthResponse health() {
    return statusService.health();
  }

  @GetMapping(BASE + "/readiness")
  public ControlReadinessResponse readiness() {
    ControlReadinessResponse response = statusService.readiness();
    operationalEventRecorder.observeDependencies(response.dependencies());
    operationalEventRecorder.observeReadiness(response.ready());
    return response;
  }

  @GetMapping(BASE + "/diagnostics")
  public ControlDiagnosticsResponse diagnostics() {
    return statusService.diagnostics();
  }

  /**
   * Bounded, cursor-paginated typed operational-event read. Unknown/duplicated parameters and malformed
   * values fail closed with a body-free 400. A successful GET emits one bounded access-audit record.
   */
  @GetMapping(BASE + "/operational-events")
  public ResponseEntity<OperationalEventPage> operationalEvents(
      @RequestParam MultiValueMap<String, String> params) {
    try {
      OperationalEventPage page = readOperationalEvents(params);
      recordSuccessfulEventAccess(params, page);
      return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    } catch (InvalidOperationalEventQueryException invalidQuery) {
      return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
    }
  }

  private OperationalEventPage readOperationalEvents(MultiValueMap<String, String> params) {
    if (!isStrictlyAllowed(params)) {
      throw new InvalidOperationalEventQueryException();
    }
    return operationalEventReadService.read(
        params.getFirst("severity"),
        params.getFirst("component"),
        params.getFirst("eventCode"),
        params.getFirst("limit"),
        params.getFirst("before"));
  }

  private void recordSuccessfulEventAccess(
      MultiValueMap<String, String> params,
      OperationalEventPage page) {
    operationalEventAccessAuditor.recordSuccess(
        resolveControlPrincipal(),
        hasValue(params, "severity"),
        hasValue(params, "component"),
        hasValue(params, "eventCode"),
        hasValue(params, "limit"),
        hasValue(params, "before"),
        page.returned());
  }

  private static boolean hasValue(MultiValueMap<String, String> params, String key) {
    String value = params.getFirst(key);
    return value != null && !value.isBlank();
  }

  private static boolean isStrictlyAllowed(MultiValueMap<String, String> params) {
    for (var entry : params.entrySet()) {
      if (!ALLOWED_EVENT_QUERY_PARAMS.contains(entry.getKey())) {
        return false;
      }
      if (entry.getValue() != null && entry.getValue().size() > 1) {
        return false;
      }
    }
    return true;
  }

  private static ControlPlanePrincipal resolveControlPrincipal() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal() instanceof ControlPlanePrincipal principal) {
      return principal;
    }
    return null;
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

  /**
   * HEAD has GET-equivalent validation, status and audit semantics while deliberately emitting no body.
   */
  @RequestMapping(method = RequestMethod.HEAD, path = BASE + "/operational-events")
  public ResponseEntity<Void> operationalEventsHead(
      @RequestParam MultiValueMap<String, String> params) {
    try {
      OperationalEventPage page = readOperationalEvents(params);
      recordSuccessfulEventAccess(params, page);
      return ResponseEntity.ok()
          .header(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue())
          .build();
    } catch (InvalidOperationalEventQueryException invalidQuery) {
      return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
    }
  }
}
