package com.orderpilot.application.services.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.SupportTenantLocatorDtos.SupportTenantContextResponse;
import com.orderpilot.api.dto.SupportTenantLocatorDtos.SupportTenantLocatorResult;
import com.orderpilot.api.dto.SupportTenantLocatorDtos.SupportTenantSearchResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.domain.support.StaffSupportScope;
import com.orderpilot.domain.support.StaffUser;
import com.orderpilot.domain.support.StaffUserRepository;
import com.orderpilot.domain.support.SupportAccessGrant;
import com.orderpilot.domain.support.SupportAccessGrantRepository;
import com.orderpilot.domain.tenant.Tenant;
import com.orderpilot.domain.tenant.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-57 — the single authority for the READ-ONLY internal support <b>tenant locator + JIT support-grant
 * boundary</b>. It lets an Operant staff principal discover and select only the tenants they may actually
 * support, and exposes a safe per-tenant support context. It is the production-grade replacement for the
 * OP-CAP-56 demo-tenant env assumption.
 *
 * <p>Safety model:
 * <ul>
 *   <li>The staff actor is the trusted, backend-resolved request actor (never a body field). The route-edge
 *       {@code STAFF_SUPPORT_READ} permission is the staff-permission gate; this service is the JIT grant
 *       boundary.</li>
 *   <li>Discovery is filtered to tenants where the actor holds an active, approved, unexpired
 *       {@link StaffSupportScope#DIAGNOSTICS} grant — the same read-only scope the OP-CAP-55 operations
 *       endpoints require. A tenant the actor cannot support is never returned, so cross-tenant discovery is
 *       denied by construction and no-grant/expired/revoked/wrong-tenant grants never authorize access.</li>
 *   <li>Lookups are keyed on the staff principal id, so the locator never scans the full tenant table.</li>
 *   <li>It NEVER mutates any row, runs SQL/script, calls a connector/ERP, or returns a secret/credential/raw
 *       payload/raw customer data/actor id/audit internal. {@code tenantId} is exposed only as a navigation
 *       handle; the backend re-validates the grant on every downstream support call.</li>
 * </ul>
 */
@Service
public class SupportTenantLocatorService {
  /** Default locator page size when the caller does not specify one. */
  public static final int DEFAULT_PAGE_SIZE = 20;
  /** Hard ceiling on the locator page size — a caller can never request an unbounded page. */
  public static final int MAX_PAGE_SIZE = 50;
  /** Upper bound on the search text length we will even consider (defensive; longer is truncated). */
  public static final int MAX_QUERY_LENGTH = 120;

  private static final String EXTERNAL_EXECUTION_DISABLED = "DISABLED";
  private static final String ENTITY_LOCATOR = "SUPPORT_TENANT_LOCATOR";
  private static final List<SupportAccessGrant.ApprovalStatus> USABLE_APPROVAL_STATUSES = List.of(
      SupportAccessGrant.ApprovalStatus.AUTO_APPROVED,
      SupportAccessGrant.ApprovalStatus.APPROVED);

  private final StaffUserRepository staffUserRepository;
  private final SupportAccessGrantRepository grantRepository;
  private final TenantRepository tenantRepository;
  private final AuditEventService auditEventService;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public SupportTenantLocatorService(
      StaffUserRepository staffUserRepository,
      SupportAccessGrantRepository grantRepository,
      TenantRepository tenantRepository,
      AuditEventService auditEventService,
      ObjectMapper objectMapper,
      Clock clock) {
    this.staffUserRepository = staffUserRepository;
    this.grantRepository = grantRepository;
    this.tenantRepository = tenantRepository;
    this.auditEventService = auditEventService;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /**
   * Locate tenants the staff actor may support, filtered by an optional free-text query against the safe
   * display name/slug, bounded by page/size. An unknown/disabled staff principal or one with no usable
   * diagnostics grant simply gets an empty page (safe — never reveals tenants they cannot support).
   */
  @Transactional(readOnly = true)
  public SupportTenantSearchResponse search(UUID staffActor, String rawQuery, int rawPage, int rawSize) {
    int page = Math.max(0, rawPage);
    int size = clampSize(rawSize);
    String query = normalizeQuery(rawQuery);

    List<SupportAccessGrant> usableGrants = allUsableGrants(staffActor);
    // Group the actor's usable grants by tenant: collect the safe scope set and the gating diagnostics
    // grant expiry (earliest, most conservative) per tenant.
    Map<UUID, TenantGrantSummary> byTenant = new LinkedHashMap<>();
    for (SupportAccessGrant grant : usableGrants) {
      byTenant
          .computeIfAbsent(grant.getTenantId(), id -> new TenantGrantSummary())
          .add(grant);
    }
    // Only tenants with a usable DIAGNOSTICS grant are eligible for discovery.
    Set<UUID> eligibleTenantIds = new java.util.LinkedHashSet<>();
    for (SupportAccessGrant grant : usableGrants) {
      if (grant.getScope() == StaffSupportScope.DIAGNOSTICS) {
        eligibleTenantIds.add(grant.getTenantId());
      }
    }

    List<SupportTenantLocatorResult> matched = new ArrayList<>();
    if (!eligibleTenantIds.isEmpty()) {
      for (Tenant tenant : tenantRepository.findAllById(eligibleTenantIds)) {
        if (!matchesQuery(tenant, query)) {
          continue;
        }
        TenantGrantSummary summary = byTenant.get(tenant.getId());
        matched.add(new SupportTenantLocatorResult(
            tenant.getId(),
            tenant.getLegalName(),
            tenant.getSlug(),
            tenant.getStatus(),
            summary == null ? List.of() : summary.scopeNames(),
            summary == null ? null : summary.diagnosticsExpiresAt(),
            true,
            EXTERNAL_EXECUTION_DISABLED));
      }
    }

    matched.sort(Comparator.comparing(
        r -> r.displayName() == null ? "" : r.displayName().toLowerCase(Locale.ROOT)));

    int total = matched.size();
    int from = Math.min(page * size, total);
    int to = Math.min(from + size, total);
    List<SupportTenantLocatorResult> pageResults = List.copyOf(matched.subList(from, to));
    boolean hasMore = to < total;

    // Discovery is intentionally NOT audited here: the locator is cross-tenant, and the existing
    // AuditEventService convention stamps a single tenant from TenantContext (there is no tenant-scoped
    // audit convention for a cross-tenant list). The auditable point of actual tenant access is the
    // per-tenant supportContext read (and the downstream OP-CAP-55 operations reads), which are audited.
    return new SupportTenantSearchResponse(
        query, page, size, pageResults.size(), hasMore, pageResults, clock.instant());
  }

  /**
   * Resolve a safe read-only support context for a selected tenant. Returns only when the staff actor holds
   * an active, approved, unexpired diagnostics grant for that tenant; otherwise fails closed with a generic
   * {@link SupportAccessDeniedException} that never reveals whether the tenant exists.
   */
  @Transactional(readOnly = true)
  public SupportTenantContextResponse supportContext(UUID staffActor, UUID tenantId) {
    Instant now = clock.instant();
    StaffUser staff = staffActor == null ? null : staffUserRepository.findById(staffActor).orElse(null);
    List<SupportAccessGrant> tenantGrants = (staff == null || !staff.isActive())
        ? List.of()
        : grantRepository.findByStaffUserIdAndTenantIdAndStatusAndApprovalStatusInAndExpiresAtAfter(
            staffActor,
            tenantId,
            SupportAccessGrant.Status.ACTIVE,
            USABLE_APPROVAL_STATUSES,
            now);

    Set<StaffSupportScope> usableScopes = EnumSet.noneOf(StaffSupportScope.class);
    Instant diagnosticsExpiresAt = null;
    for (SupportAccessGrant grant : tenantGrants) {
      if (grant.isUsable(now) && staff.permits(grant.getScope())) {
        usableScopes.add(grant.getScope());
        if (grant.getScope() == StaffSupportScope.DIAGNOSTICS
            && (diagnosticsExpiresAt == null || grant.getExpiresAt().isBefore(diagnosticsExpiresAt))) {
          diagnosticsExpiresAt = grant.getExpiresAt();
        }
      }
    }

    if (!usableScopes.contains(StaffSupportScope.DIAGNOSTICS)) {
      // Fail closed without revealing tenant existence or which condition failed.
      audit("SUPPORT_TENANT_CONTEXT_DENIED", tenantId == null ? "n/a" : tenantId.toString(), staffActor, map -> {
        map.put("decision", "DENIED");
        map.put("reasonCode", "NO_ACTIVE_DIAGNOSTICS_GRANT");
      });
      throw new SupportAccessDeniedException("Support access denied");
    }

    Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
    if (tenant == null) {
      // A usable grant references a missing tenant — still fail closed generically.
      throw new SupportAccessDeniedException("Support access denied");
    }

    audit("SUPPORT_TENANT_CONTEXT_VIEWED", tenantId.toString(), staffActor, map -> {
      map.put("tenantId", tenantId.toString());
      map.put("scopes", scopeNames(usableScopes));
    });

    return new SupportTenantContextResponse(
        tenant.getId(),
        tenant.getLegalName(),
        tenant.getSlug(),
        tenant.getStatus(),
        scopeNames(usableScopes),
        diagnosticsExpiresAt,
        true,
        true,
        EXTERNAL_EXECUTION_DISABLED,
        clock.instant());
  }

  private List<SupportAccessGrant> allUsableGrants(UUID staffActor) {
    StaffUser staff = staffActor == null ? null : staffUserRepository.findById(staffActor).orElse(null);
    if (staff == null || !staff.isActive()) {
      return List.of();
    }
    Instant now = clock.instant();
    List<SupportAccessGrant> grants =
        grantRepository.findByStaffUserIdAndStatusAndApprovalStatusInAndExpiresAtAfter(
            staffActor,
            SupportAccessGrant.Status.ACTIVE,
            USABLE_APPROVAL_STATUSES,
            now);
    List<SupportAccessGrant> usable = new ArrayList<>();
    for (SupportAccessGrant grant : grants) {
      if (grant.isUsable(now) && staff.permits(grant.getScope())) {
        usable.add(grant);
      }
    }
    return usable;
  }

  private static int clampSize(int rawSize) {
    if (rawSize <= 0) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.min(rawSize, MAX_PAGE_SIZE);
  }

  private static String normalizeQuery(String rawQuery) {
    if (rawQuery == null) {
      return "";
    }
    String trimmed = rawQuery.trim();
    if (trimmed.length() > MAX_QUERY_LENGTH) {
      trimmed = trimmed.substring(0, MAX_QUERY_LENGTH);
    }
    return trimmed.toLowerCase(Locale.ROOT);
  }

  private static boolean matchesQuery(Tenant tenant, String normalizedQuery) {
    if (normalizedQuery.isEmpty()) {
      return true;
    }
    String name = tenant.getLegalName() == null ? "" : tenant.getLegalName().toLowerCase(Locale.ROOT);
    String slug = tenant.getSlug() == null ? "" : tenant.getSlug().toLowerCase(Locale.ROOT);
    return name.contains(normalizedQuery) || slug.contains(normalizedQuery);
  }

  private static List<String> scopeNames(Set<StaffSupportScope> scopes) {
    Set<String> names = new TreeSet<>();
    for (StaffSupportScope scope : scopes) {
      names.add(scope.name());
    }
    return List.copyOf(names);
  }

  private void audit(String action, String entityId, UUID actorId, java.util.function.Consumer<Map<String, Object>> builder) {
    Map<String, Object> map = new LinkedHashMap<>();
    builder.accept(map);
    String metadataJson;
    try {
      metadataJson = objectMapper.writeValueAsString(map);
    } catch (Exception ex) {
      metadataJson = "{}";
    }
    auditEventService.record(action, ENTITY_LOCATOR, entityId, actorId, metadataJson);
  }

  /** Per-tenant accumulation of the actor's usable scopes + the gating diagnostics grant expiry. */
  private static final class TenantGrantSummary {
    private final Set<StaffSupportScope> scopes = EnumSet.noneOf(StaffSupportScope.class);
    private Instant diagnosticsExpiresAt;

    void add(SupportAccessGrant grant) {
      scopes.add(grant.getScope());
      if (grant.getScope() == StaffSupportScope.DIAGNOSTICS
          && (diagnosticsExpiresAt == null || grant.getExpiresAt().isBefore(diagnosticsExpiresAt))) {
        diagnosticsExpiresAt = grant.getExpiresAt();
      }
    }

    List<String> scopeNames() {
      return SupportTenantLocatorService.scopeNames(scopes);
    }

    Instant diagnosticsExpiresAt() {
      return diagnosticsExpiresAt;
    }
  }
}
