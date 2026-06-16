package com.orderpilot.application.services.runtime;

import com.orderpilot.domain.usage.FeatureEntitlement;
import com.orderpilot.domain.usage.FeatureEntitlementRepository;
import com.orderpilot.domain.usage.TenantRuntimePlan;
import com.orderpilot.domain.usage.TenantRuntimePlanRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * OP-CAP-16E Persistent Tenant Entitlements — database-backed {@link RuntimeFeaturePolicy}.
 *
 * <p>Resolution (all reads tenant-scoped; no cross-tenant access; no external calls, no AI, no
 * business-table writes):
 *
 * <ol>
 *   <li><b>No plan row at all</b> → allowed, {@code FEATURE_POLICY_COMPAT_DEFAULT}. This is the safe
 *       backward-compatibility default that preserves 16D behavior for every existing/demo tenant
 *       (none of which have plan rows yet).
 *   <li><b>Plan rows exist but none currently active</b> (suspended/expired/disabled, or outside the
 *       effective window) → denied, {@code FEATURE_PLAN_NOT_ACTIVE}.
 *   <li><b>Active plan, currently-effective enabled entitlement</b> → allowed, {@code
 *       FEATURE_AVAILABLE}.
 *   <li><b>Active plan, currently-effective disabled entitlement</b> → denied, {@code
 *       FEATURE_NOT_AVAILABLE}.
 *   <li><b>Active plan, no currently-effective entitlement</b> → denied: {@code
 *       FEATURE_ENTITLEMENT_EXPIRED} when a past (bounded) row exists, otherwise {@code
 *       FEATURE_NOT_ENTITLED} (absent or not-yet-effective). An active plan is authoritative.
 * </ol>
 *
 * <p>Duplicate rows are resolved deterministically: the active plan is the newest-effective ACTIVE
 * plan; the entitlement is the currently-effective row with the latest {@code effectiveFrom}.
 */
public class PersistentRuntimeFeaturePolicy implements RuntimeFeaturePolicy {
  private final TenantRuntimePlanRepository planRepository;
  private final FeatureEntitlementRepository entitlementRepository;
  private final Clock clock;

  public PersistentRuntimeFeaturePolicy(
      TenantRuntimePlanRepository planRepository,
      FeatureEntitlementRepository entitlementRepository,
      Clock clock) {
    this.planRepository = planRepository;
    this.entitlementRepository = entitlementRepository;
    this.clock = clock;
  }

  @Override
  public boolean isAvailable(UUID tenantId, RuntimeFeatureType feature) {
    return evaluate(tenantId, feature).available();
  }

  @Override
  public FeatureEntitlementDecision evaluate(UUID tenantId, RuntimeFeatureType feature) {
    if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
    if (feature == null) throw new IllegalArgumentException("feature is required");
    Instant now = clock.instant();

    List<TenantRuntimePlan> plans = planRepository.findByTenantIdOrderByEffectiveFromDesc(tenantId);
    if (plans.isEmpty()) {
      // No persisted plan → safe compatibility default (allow), preserving 16D behavior.
      return allowed(feature, RuntimeGuardReasonCodes.FEATURE_POLICY_COMPAT_DEFAULT);
    }

    Optional<TenantRuntimePlan> activePlan =
        plans.stream().filter(plan -> plan.isActiveAt(now)).findFirst();
    if (activePlan.isEmpty()) {
      return denied(feature, RuntimeGuardReasonCodes.FEATURE_PLAN_NOT_ACTIVE);
    }

    List<FeatureEntitlement> rows =
        entitlementRepository.findByTenantIdAndPlanIdAndFeatureType(
            tenantId, activePlan.get().getId(), feature.name());

    Optional<FeatureEntitlement> effective =
        rows.stream()
            .filter(row -> row.isEffectiveAt(now))
            .max(Comparator.comparing(FeatureEntitlement::getEffectiveFrom));
    if (effective.isPresent()) {
      return effective.get().isEnabled()
          ? allowed(feature, RuntimeGuardReasonCodes.FEATURE_AVAILABLE)
          : denied(feature, RuntimeGuardReasonCodes.FEATURE_NOT_AVAILABLE);
    }

    // Active plan is authoritative but no currently-effective row: distinguish expired from absent.
    boolean anyExpired = rows.stream().anyMatch(row -> row.isExpiredAt(now));
    return denied(
        feature,
        anyExpired
            ? RuntimeGuardReasonCodes.FEATURE_ENTITLEMENT_EXPIRED
            : RuntimeGuardReasonCodes.FEATURE_NOT_ENTITLED);
  }

  private static FeatureEntitlementDecision allowed(RuntimeFeatureType feature, String reasonCode) {
    return new FeatureEntitlementDecision(true, feature, reasonCode, 200);
  }

  private static FeatureEntitlementDecision denied(RuntimeFeatureType feature, String reasonCode) {
    return new FeatureEntitlementDecision(false, feature, reasonCode, 403);
  }
}
