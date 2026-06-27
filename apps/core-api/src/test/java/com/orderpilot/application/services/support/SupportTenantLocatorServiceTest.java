package com.orderpilot.application.services.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.SupportTenantLocatorDtos.SupportTenantContextResponse;
import com.orderpilot.api.dto.SupportTenantLocatorDtos.SupportTenantLocatorResult;
import com.orderpilot.api.dto.SupportTenantLocatorDtos.SupportTenantSearchResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.support.StaffRole;
import com.orderpilot.domain.support.StaffSupportScope;
import com.orderpilot.domain.support.StaffUser;
import com.orderpilot.domain.support.StaffUserRepository;
import com.orderpilot.domain.support.SupportAccessGrant;
import com.orderpilot.domain.support.SupportAccessGrantRepository;
import com.orderpilot.domain.tenant.Tenant;
import com.orderpilot.domain.tenant.TenantRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-57 — JPA-backed proof for the internal tenant locator + JIT support-grant boundary. Proves that
 * discovery and per-tenant context are filtered strictly to tenants where the staff actor holds an active,
 * approved, unexpired DIAGNOSTICS grant: no-grant / expired / revoked / wrong-tenant / wrong-scope never
 * authorize access, an unknown/disabled principal gets nothing, and page/size caps are enforced.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SupportTenantLocatorServiceTest {
  private static final Instant T0 = Instant.parse("2026-06-26T12:00:00Z");

  @Autowired private StaffUserRepository staffUserRepository;
  @Autowired private SupportAccessGrantRepository grantRepository;
  @Autowired private TenantRepository tenantRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private SupportTenantLocatorService serviceAt(Instant now) {
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    return new SupportTenantLocatorService(
        staffUserRepository,
        grantRepository,
        tenantRepository,
        new AuditEventService(auditEventRepository, clock),
        new ObjectMapper(),
        clock);
  }

  private UUID staff(StaffRole role) {
    return staffUserRepository.save(new StaffUser("staff-" + UUID.randomUUID(), role, T0)).getId();
  }

  private UUID tenant(String slug, String name) {
    return tenantRepository.save(new Tenant(slug, name, "ACTIVE", T0)).getId();
  }

  private void diagnosticsGrant(UUID staff, UUID tenant, Instant expiresAt) {
    grantRepository.save(new SupportAccessGrant(
        staff, tenant, StaffSupportScope.DIAGNOSTICS, "case", expiresAt, staff, T0));
  }

  // --- discovery ---

  @Test
  void searchReturnsOnlyTenantsWithUsableDiagnosticsGrant() {
    UUID actor = staff(StaffRole.SUPPORT_ENGINEER);
    UUID allowed = tenant("acme", "Acme Distribution");
    UUID expiredTenant = tenant("beta", "Beta Parts");
    UUID otherStaffTenant = tenant("gamma", "Gamma Industrial");

    diagnosticsGrant(actor, allowed, T0.plus(Duration.ofHours(1)));
    diagnosticsGrant(actor, expiredTenant, T0.minusSeconds(1)); // expired → excluded
    diagnosticsGrant(staff(StaffRole.SUPPORT_VIEWER), otherStaffTenant, T0.plus(Duration.ofHours(1))); // other staff

    SupportTenantSearchResponse response = serviceAt(T0).search(actor, "", 0, 20);

    assertThat(response.results()).extracting(SupportTenantLocatorResult::tenantId).containsExactly(allowed);
    SupportTenantLocatorResult result = response.results().get(0);
    assertThat(result.displayName()).isEqualTo("Acme Distribution");
    assertThat(result.slug()).isEqualTo("acme");
    assertThat(result.supportScopes()).containsExactly("DIAGNOSTICS");
    assertThat(result.readOnly()).isTrue();
    assertThat(result.externalExecution()).isEqualTo("DISABLED");
  }

  @Test
  void searchFiltersByNameOrSlugQuery() {
    UUID actor = staff(StaffRole.SUPPORT_ENGINEER);
    UUID acme = tenant("acme", "Acme Distribution");
    UUID beta = tenant("beta", "Beta Parts");
    diagnosticsGrant(actor, acme, T0.plus(Duration.ofHours(1)));
    diagnosticsGrant(actor, beta, T0.plus(Duration.ofHours(1)));

    assertThat(serviceAt(T0).search(actor, "acme", 0, 20).results())
        .extracting(SupportTenantLocatorResult::tenantId).containsExactly(acme);
    assertThat(serviceAt(T0).search(actor, "Parts", 0, 20).results())
        .extracting(SupportTenantLocatorResult::tenantId).containsExactly(beta);
    assertThat(serviceAt(T0).search(actor, "nomatch", 0, 20).results()).isEmpty();
  }

  @Test
  void searchReturnsEmptyForStaffWithNoUsableGrant() {
    UUID actor = staff(StaffRole.SUPPORT_ENGINEER);
    tenant("acme", "Acme Distribution"); // exists but no grant for this actor
    assertThat(serviceAt(T0).search(actor, "", 0, 20).results()).isEmpty();
  }

  @Test
  void searchReturnsEmptyForUnknownOrDisabledStaffPrincipal() {
    // Unknown principal.
    assertThat(serviceAt(T0).search(UUID.randomUUID(), "", 0, 20).results()).isEmpty();

    // Disabled principal with an otherwise-usable grant.
    StaffUser disabled = staffUserRepository.save(new StaffUser("disabled", StaffRole.SUPPORT_ENGINEER, T0));
    disabled.disable();
    staffUserRepository.save(disabled);
    UUID tenant = tenant("acme", "Acme Distribution");
    diagnosticsGrant(disabled.getId(), tenant, T0.plus(Duration.ofHours(1)));
    assertThat(serviceAt(T0).search(disabled.getId(), "", 0, 20).results()).isEmpty();
  }

  @Test
  void searchExcludesRevokedGrant() {
    UUID actor = staff(StaffRole.SUPPORT_ENGINEER);
    UUID tenant = tenant("acme", "Acme Distribution");
    SupportAccessGrant grant = grantRepository.save(new SupportAccessGrant(
        actor, tenant, StaffSupportScope.DIAGNOSTICS, "case", T0.plus(Duration.ofHours(1)), actor, T0));
    grant.revoke(actor, T0.plusSeconds(1));
    grantRepository.save(grant);

    assertThat(serviceAt(T0).search(actor, "", 0, 20).results()).isEmpty();
  }

  @Test
  void searchEnforcesPageAndSizeCaps() {
    UUID actor = staff(StaffRole.SUPPORT_ENGINEER);
    UUID tenant = tenant("acme", "Acme Distribution");
    diagnosticsGrant(actor, tenant, T0.plus(Duration.ofHours(1)));

    // Oversized request is clamped to MAX_PAGE_SIZE; zero falls back to the default; negative page → 0.
    assertThat(serviceAt(T0).search(actor, "", 0, 1000).pageSize())
        .isEqualTo(SupportTenantLocatorService.MAX_PAGE_SIZE);
    assertThat(serviceAt(T0).search(actor, "", 0, 0).pageSize())
        .isEqualTo(SupportTenantLocatorService.DEFAULT_PAGE_SIZE);
    assertThat(serviceAt(T0).search(actor, "", -5, 20).page()).isEqualTo(0);
  }

  // --- per-tenant support context (JIT boundary) ---

  @Test
  void supportContextReturnsSafeContextForUsableDiagnosticsGrant() {
    UUID actor = staff(StaffRole.SUPPORT_ENGINEER);
    UUID tenant = tenant("acme", "Acme Distribution");
    diagnosticsGrant(actor, tenant, T0.plus(Duration.ofHours(2)));

    // The controller resolves support context only after the tenant context is set to the selected tenant.
    TenantContext.setTenantId(tenant);
    SupportTenantContextResponse context = serviceAt(T0).supportContext(actor, tenant);

    assertThat(context.tenantId()).isEqualTo(tenant);
    assertThat(context.displayName()).isEqualTo("Acme Distribution");
    assertThat(context.supportScopes()).containsExactly("DIAGNOSTICS");
    assertThat(context.canViewOperations()).isTrue();
    assertThat(context.readOnly()).isTrue();
    assertThat(context.grantExpiresAt()).isEqualTo(T0.plus(Duration.ofHours(2)));
    assertThat(context.externalExecution()).isEqualTo("DISABLED");
  }

  @Test
  void supportContextDeniedWhenNoGrantForTenant() {
    UUID actor = staff(StaffRole.SUPPORT_ENGINEER);
    UUID granted = tenant("acme", "Acme Distribution");
    UUID otherTenant = tenant("beta", "Beta Parts");
    diagnosticsGrant(actor, granted, T0.plus(Duration.ofHours(1)));

    // Wrong-tenant: a grant for one tenant never authorizes another.
    TenantContext.setTenantId(otherTenant);
    assertThatThrownBy(() -> serviceAt(T0).supportContext(actor, otherTenant))
        .isInstanceOf(SupportAccessDeniedException.class)
        .hasMessage("Support access denied");
  }

  @Test
  void supportContextDeniedForExpiredGrant() {
    UUID actor = staff(StaffRole.SUPPORT_ENGINEER);
    UUID tenant = tenant("acme", "Acme Distribution");
    diagnosticsGrant(actor, tenant, T0.minusSeconds(1));

    TenantContext.setTenantId(tenant);
    assertThatThrownBy(() -> serviceAt(T0).supportContext(actor, tenant))
        .isInstanceOf(SupportAccessDeniedException.class);
  }

  @Test
  void supportContextDeniedWhenOnlyNonDiagnosticsScopeGranted() {
    UUID actor = staff(StaffRole.SUPPORT_ENGINEER);
    UUID tenant = tenant("acme", "Acme Distribution");
    // An approved DATA_REPAIR grant is usable, but the read-only operations boundary needs DIAGNOSTICS.
    SupportAccessGrant dataRepair = new SupportAccessGrant(
        actor, tenant, StaffSupportScope.DATA_REPAIR, "case", T0.plus(Duration.ofHours(1)), actor, T0);
    dataRepair.approve(staff(StaffRole.SUPPORT_ENGINEER), "ok", T0.plusSeconds(1));
    grantRepository.save(dataRepair);

    TenantContext.setTenantId(tenant);
    assertThatThrownBy(() -> serviceAt(T0).supportContext(actor, tenant))
        .isInstanceOf(SupportAccessDeniedException.class);
    assertThat(serviceAt(T0).search(actor, "", 0, 20).results()).isEmpty();
  }
}
