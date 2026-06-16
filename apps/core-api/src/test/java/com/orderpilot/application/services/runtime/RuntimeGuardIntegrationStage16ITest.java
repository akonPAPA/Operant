package com.orderpilot.application.services.runtime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService.CreatePlanCommand;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService.UpdatePlanCommand;
import com.orderpilot.application.services.runtime.RuntimeEntitlementAdminService.UpsertFeatureCommand;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.usage.TenantRuntimePlanCode;
import com.orderpilot.domain.usage.TenantRuntimePlanStatus;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-16I — end-to-end proof the persistent runtime guard immediately respects entitlements
 * created/changed through {@link RuntimeEntitlementAdminService}: disabled feature denies, enabling
 * allows, suspending the plan denies. Exercises the real {@link RuntimeGuardService} +
 * {@link PersistentRuntimeFeaturePolicy} (via {@link CoreConfiguration}) over the REPORT_EXPORT seam.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RuntimeEntitlementAdminService.class, AuditEventService.class, CoreConfiguration.class,
  RuntimeGuardService.class, QuotaGuard.class, RateLimitService.class, FeatureEntitlementGuard.class, UsageMeterService.class})
class RuntimeGuardIntegrationStage16ITest {
  private static final RuntimeFeatureType FEATURE = RuntimeFeatureType.REPORT_EXPORT;
  private static final Instant PAST = Instant.now().minusSeconds(3600);

  @Autowired private RuntimeEntitlementAdminService adminService;
  @Autowired private RuntimeGuardService runtimeGuardService;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void guardReflectsCommandDrivenEntitlementLifecycle() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    var plan = adminService.createPlan(
        new CreatePlanCommand(TenantRuntimePlanCode.PRO, TenantRuntimePlanStatus.ACTIVE, PAST, null, null));

    // 1) Disabled feature under an active plan → guard denies.
    adminService.upsertFeatureEntitlement(new UpsertFeatureCommand(plan.id(), FEATURE, false, "withheld", PAST, null, null));
    assertThatThrownBy(() -> enforce(tenantId)).isInstanceOf(RuntimeFeatureNotAvailableException.class);

    // 2) Enable the feature → guard allows.
    adminService.upsertFeatureEntitlement(new UpsertFeatureCommand(plan.id(), FEATURE, true, "granted", PAST, null, null));
    assertThatCode(() -> enforce(tenantId)).doesNotThrowAnyException();

    // 3) Suspend the plan → guard denies again (plan not active).
    adminService.updatePlan(new UpdatePlanCommand(plan.id(), TenantRuntimePlanStatus.SUSPENDED, null, null, false, null));
    assertThatThrownBy(() -> enforce(tenantId)).isInstanceOf(RuntimeFeatureNotAvailableException.class);
  }

  private void enforce(UUID tenantId) {
    runtimeGuardService.enforceWithoutRate(
        RuntimeGuardRequest.of(tenantId, RuntimeOperationType.REPORT_GENERATED, 1), FEATURE);
  }
}
