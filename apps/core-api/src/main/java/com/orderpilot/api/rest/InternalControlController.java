package com.orderpilot.api.rest;

import com.orderpilot.api.dto.ControlInternalDtos.ControlDiagnosticsResponse;
import com.orderpilot.api.dto.ControlInternalDtos.ControlHealthResponse;
import com.orderpilot.api.dto.ControlInternalDtos.ControlReadinessResponse;
import com.orderpilot.api.dto.ControlInternalDtos.ControlStatusResponse;
import com.orderpilot.api.dto.ControlInternalDtos.OperationalEventPage;
import com.orderpilot.application.services.control.OperationalEventAccessAuditor;
import com.orderpilot.application.services.control.OperationalEventReadService;
import com.orderpilot.application.services.control.OperationalEventReadService.InvalidOperationalEventQueryException;
import com.orderpilot.application.services.control.OperationalEventRecorder;
import com.orderpilot.application.services.control.ControlPlaneStatusService;
import com.orderpilot.security.ControlPlanePrincipal;
import java.util.Set;
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
 * <p>The status/health/readiness/diagnostics responses are platform-scoped fixed tokens. The
 * operational-event read exposes a bounded, server-owned TYPED projection (never raw application
 * logs). Status and readiness reads incidentally feed the operational-event producer with observed
 * platform state, so dependency/readiness transitions become typed events.
 */
@RestController
public class InternalControlController {
  private static final String BASE = "/api/v1/internal/control";
  private static final Set<String> ALLOWED_EVENT_QUERY_PARAMS =
      Set.of("severity", "component", "eventCode", "limit", "before");

  private final ControlPlaneStatusService statusService;
  private final OperationalEventReadService operationalEventReadService;
  private final OperationalEventRecorder operationalEventRecorder;
  private final OperationalEventAccessAuditor operationalEventAccessAuditor;

  @org.springframework.beans.factory.annotation.Autowired
  public InternalControlController(
      ControlPlaneStatusService statusService,
      OperationalEventReadService operationalEventReadService,
      OperationalEventRecorder operationalEventRecorder) {
    this(statusService, operationalEventReadService, operationalEventRecorder,
        new OperationalEventAccessAuditor());
  }

  InternalControlController(
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
   * P1-E lifecycle (operational-event slice) - bounded, cursor-paginated, TYPED operational events.
   * Query parameters are a strict allowlist ({@code severity}, {@code component}, {@code eventCode},
   * {@code limit}, {@code before}); an unknown or duplicated parameter, or any malformed value, fails
   * closed with a bounded body-free 400 that never echoes the input. Authority is enforced upstream:
   * this route requires the dedicated STAFF_CONTROL_OPERATIONAL_EVENT_READ permission. The response is
   * {@code Cache-Control: no-store}; a successful read emits a bounded access-audit record (attribution
   * + request shape only, never event content).
   */
  @GetMapping(BASE + "/operational-events")
  public ResponseEntity<OperationalEventPage> operationalEvents(
      @RequestParam MultiValueMap<String, String> params) {
    if (!isStrictlyAllowed(params)) {
      return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
    }
    String severity = params.getFirst("severity");
    String component = params.getFirst("component");
    String eventCode = params.getFirst("eventCode");
    String limit = params.getFirst("limit");
    String before = params.getFirst("before");
    OperationalEventPage page;
    try {
      page = operationalEventReadService.read(severity, component, eventCode, limit, before);
    } catch (InvalidOperationalEventQueryException invalidQuery) {
      return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
    }
    operationalEventAccessAuditor.recordSuccess(
        resolveControlPrincipal(), severity, component, eventCode, limit,
        before != null && !before.isBlank(), page.returned());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
  }

  private static boolean isStrictlyAllowed(MultiValueMap<String, String> params) {
    for (var entry : params.entrySet()) {
      if (!ALLOWED_EVENT_QUERY_PARAMS.contains(entry.getKey())) {
        return false; // unknown parameter fails closed
      }
      if (entry.getValue() != null && entry.getValue().size() > 1) {
        return false; // duplicate parameter fails closed
      }
    }
    return true;
  }

  private static ControlPlanePrincipal resolveControlPrincipal() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof ControlPlanePrincipal principal) {
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

  @RequestMapping(method = RequestMethod.HEAD, path = BASE + "/operational-events")
  public ResponseEntity<Void> operationalEventsHead() {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue()).build();
  }

}
