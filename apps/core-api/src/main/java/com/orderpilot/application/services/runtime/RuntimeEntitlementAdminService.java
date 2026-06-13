package com.orderpilot.application.services.runtime;

import com.orderpilot.api.dto.RuntimeEntitlementAdminDtos.FeatureEntitlementResponse;
import com.orderpilot.api.dto.RuntimeEntitlementAdminDtos.FeatureStatusResponse;
import com.orderpilot.api.dto.RuntimeEntitlementAdminDtos.RuntimeEntitlementStatusResponse;
import com.orderpilot.api.dto.RuntimeEntitlementAdminDtos.TenantRuntimePlanResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.errors.ConflictException;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.usage.FeatureEntitlement;
import com.orderpilot.domain.usage.FeatureEntitlementRepository;
import com.orderpilot.domain.usage.TenantRuntimePlan;
import com.orderpilot.domain.usage.TenantRuntimePlanCode;
import com.orderpilot.domain.usage.TenantRuntimePlanRepository;
import com.orderpilot.domain.usage.TenantRuntimePlanStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-16I — Tenant Plan &amp; Feature Entitlement Command Surface.
 *
 * <p>The single controlled service for creating/updating tenant runtime plans and enabling/disabling
 * feature entitlements that the persistent runtime guard ({@link PersistentRuntimeFeaturePolicy})
 * already reads. This is runtime <b>governance</b> only — there is no price, billing, money, payment,
 * or external call anywhere in this surface.
 *
 * <p>Invariants:
 *
 * <ul>
 *   <li>Tenant scope always comes from {@link TenantContext}; every read/write is tenant-scoped and a
 *       plan owned by another tenant is invisible (404, never leaked).
 *   <li>Every mutation emits an audit event via {@link AuditEventService} with safe stable tokens.
 *   <li>Creating a plan while another plan is currently active is rejected (409) rather than silently
 *       mutating — no hidden plan switching in 16I.
 *   <li>Entitlement upsert reuses the existing open-ended row for a (tenant, plan, feature) instead of
 *       creating duplicate open-ended rows.
 * </ul>
 *
 * <p>Runtime guard ordering (entitlement → quota → rate) and the no-plan compatibility default are
 * unchanged; this service only writes the rows the existing 16E policy resolves.
 */
@Service
public class RuntimeEntitlementAdminService {
  static final String SOURCE_COMPATIBILITY_DEFAULT = "COMPATIBILITY_DEFAULT";
  static final String SOURCE_ACTIVE_PLAN = "ACTIVE_PLAN";
  static final String SOURCE_PLAN_NOT_ACTIVE = "PLAN_NOT_ACTIVE";

  private final TenantRuntimePlanRepository planRepository;
  private final FeatureEntitlementRepository entitlementRepository;
  private final RuntimeFeaturePolicy runtimeFeaturePolicy;
  private final AuditEventService auditEventService;
  private final Clock clock;

  public RuntimeEntitlementAdminService(
      TenantRuntimePlanRepository planRepository,
      FeatureEntitlementRepository entitlementRepository,
      RuntimeFeaturePolicy runtimeFeaturePolicy,
      AuditEventService auditEventService,
      Clock clock) {
    this.planRepository = planRepository;
    this.entitlementRepository = entitlementRepository;
    this.runtimeFeaturePolicy = runtimeFeaturePolicy;
    this.auditEventService = auditEventService;
    this.clock = clock;
  }

  // --- commands ---

  public record CreatePlanCommand(
      TenantRuntimePlanCode planCode,
      TenantRuntimePlanStatus status,
      Instant effectiveFrom,
      Instant effectiveUntil,
      UUID actorId) {}

  public record UpdatePlanCommand(
      UUID planId,
      TenantRuntimePlanStatus status,
      Instant effectiveFrom,
      Instant effectiveUntil,
      boolean clearEffectiveUntil,
      UUID actorId) {}

  public record UpsertFeatureCommand(
      UUID planId,
      RuntimeFeatureType featureType,
      boolean enabled,
      String reasonCode,
      Instant effectiveFrom,
      Instant effectiveUntil,
      UUID actorId) {}

  public record DisableFeatureCommand(UUID planId, RuntimeFeatureType featureType, String reasonCode, UUID actorId) {}

  @Transactional
  public TenantRuntimePlanResponse createPlan(CreatePlanCommand command) {
    UUID tenantId = TenantContext.requireTenantId();
    if (command.planCode() == null) throw new IllegalArgumentException("planCode is required");
    if (command.status() == null) throw new IllegalArgumentException("status is required");
    Instant now = clock.instant();
    Instant effectiveFrom = command.effectiveFrom() != null ? command.effectiveFrom() : now;
    validateWindow(effectiveFrom, command.effectiveUntil());

    // OP-CAP-16K: a new ACTIVE plan must not overlap an existing ACTIVE plan's effective window
    // (this generalizes the 16I "no second active plan" rule to bounded windows). Non-ACTIVE plans
    // (staged SUSPENDED/EXPIRED/DISABLED) never conflict.
    if (command.status() == TenantRuntimePlanStatus.ACTIVE) {
      requireNoActiveWindowOverlap(tenantId, null, effectiveFrom, command.effectiveUntil());
    }

    TenantRuntimePlan saved =
        planRepository.save(
            new TenantRuntimePlan(tenantId, command.planCode(), command.status(), effectiveFrom, command.effectiveUntil(), now));
    auditEventService.record(
        "RUNTIME_PLAN_CREATED", "TENANT_RUNTIME_PLAN", saved.getId().toString(), command.actorId(),
        "{\"planCode\":\"" + saved.getPlanCode().name() + "\",\"status\":\"" + saved.getStatus().name() + "\"}");
    return toPlanResponse(saved, entitlementRepository.findByTenantIdAndPlanId(tenantId, saved.getId()));
  }

  @Transactional
  public TenantRuntimePlanResponse updatePlan(UpdatePlanCommand command) {
    UUID tenantId = TenantContext.requireTenantId();
    TenantRuntimePlan plan = requirePlan(command.planId(), tenantId);
    Instant now = clock.instant();

    // OP-CAP-16J explicit effective-until patch semantics:
    //   * clearEffectiveUntil=true  -> reset to open-ended (null);
    //   * effectiveUntil provided    -> set to that value;
    //   * neither                    -> leave unchanged.
    // Asking to both set and clear is contradictory and rejected.
    if (command.clearEffectiveUntil() && command.effectiveUntil() != null) {
      throw new IllegalArgumentException("cannot both set and clear effectiveUntil");
    }
    boolean setEffectiveUntil = command.clearEffectiveUntil() || command.effectiveUntil() != null;
    Instant untilValue = command.clearEffectiveUntil() ? null : command.effectiveUntil();
    Instant resultingFrom = command.effectiveFrom() != null ? command.effectiveFrom() : plan.getEffectiveFrom();
    Instant resultingUntil = setEffectiveUntil ? untilValue : plan.getEffectiveUntil();
    validateWindow(resultingFrom, resultingUntil);

    // OP-CAP-16K: if the resulting plan is ACTIVE, its (possibly cleared/extended) window must not
    // overlap any other ACTIVE plan for the tenant — covering open-ended and bounded overlap. This
    // also enforces, at the service level, the open-ended ACTIVE uniqueness the V43 DB index protects.
    TenantRuntimePlanStatus resultingStatus = command.status() != null ? command.status() : plan.getStatus();
    if (resultingStatus == TenantRuntimePlanStatus.ACTIVE) {
      requireNoActiveWindowOverlap(tenantId, plan.getId(), resultingFrom, resultingUntil);
    }

    TenantRuntimePlanStatus previousStatus = plan.getStatus();
    Instant previousUntil = plan.getEffectiveUntil();
    plan.applyUpdate(command.status(), command.effectiveFrom(), setEffectiveUntil, untilValue, now);
    TenantRuntimePlan saved = planRepository.save(plan);
    auditEventService.record(
        "RUNTIME_PLAN_UPDATED", "TENANT_RUNTIME_PLAN", saved.getId().toString(), command.actorId(),
        "{\"previousStatus\":\"" + previousStatus.name() + "\",\"status\":\"" + saved.getStatus().name() + "\""
            + ",\"previousEffectiveUntil\":" + jsonInstant(previousUntil)
            + ",\"effectiveUntil\":" + jsonInstant(saved.getEffectiveUntil()) + "}");
    return toPlanResponse(saved, entitlementRepository.findByTenantIdAndPlanId(tenantId, saved.getId()));
  }

  @Transactional
  public FeatureEntitlementResponse upsertFeatureEntitlement(UpsertFeatureCommand command) {
    UUID tenantId = TenantContext.requireTenantId();
    if (command.featureType() == null) throw new IllegalArgumentException("featureType is required");
    TenantRuntimePlan plan = requirePlan(command.planId(), tenantId);
    Instant now = clock.instant();
    Instant effectiveFrom = command.effectiveFrom() != null ? command.effectiveFrom() : now;
    validateWindow(effectiveFrom, command.effectiveUntil());

    FeatureEntitlement row =
        upsertRow(tenantId, plan.getId(), command.featureType(), command.enabled(), command.reasonCode(), effectiveFrom, command.effectiveUntil(), now);
    auditEventService.record(
        "FEATURE_ENTITLEMENT_UPSERTED", "FEATURE_ENTITLEMENT", row.getId().toString(), command.actorId(),
        "{\"feature\":\"" + command.featureType().name() + "\",\"enabled\":" + row.isEnabled()
            + ",\"reasonCode\":" + jsonString(row.getReasonCode()) + "}");
    return toFeatureResponse(row);
  }

  @Transactional
  public FeatureEntitlementResponse disableFeatureEntitlement(DisableFeatureCommand command) {
    UUID tenantId = TenantContext.requireTenantId();
    if (command.featureType() == null) throw new IllegalArgumentException("featureType is required");
    TenantRuntimePlan plan = requirePlan(command.planId(), tenantId);
    Instant now = clock.instant();

    Optional<FeatureEntitlement> existingOpen = openEndedRow(tenantId, plan.getId(), command.featureType());
    FeatureEntitlement row;
    if (existingOpen.isPresent()) {
      row = existingOpen.get();
      row.apply(false, command.reasonCode(), null, null, now);
      row = entitlementRepository.save(row);
    } else {
      row =
          entitlementRepository.save(
              new FeatureEntitlement(tenantId, plan.getId(), command.featureType().name(), false, command.reasonCode(), now, null, now));
    }
    auditEventService.record(
        "FEATURE_ENTITLEMENT_DISABLED", "FEATURE_ENTITLEMENT", row.getId().toString(), command.actorId(),
        "{\"feature\":\"" + command.featureType().name() + "\",\"enabled\":false,\"reasonCode\":" + jsonString(row.getReasonCode()) + "}");
    return toFeatureResponse(row);
  }

  @Transactional(readOnly = true)
  public RuntimeEntitlementStatusResponse getCurrentRuntimeEntitlements() {
    UUID tenantId = TenantContext.requireTenantId();
    Instant now = clock.instant();
    List<TenantRuntimePlan> plans = planRepository.findByTenantIdOrderByEffectiveFromDesc(tenantId);

    String source;
    TenantRuntimePlanResponse currentPlan;
    if (plans.isEmpty()) {
      source = SOURCE_COMPATIBILITY_DEFAULT;
      currentPlan = null;
    } else {
      Optional<TenantRuntimePlan> active = plans.stream().filter(plan -> plan.isActiveAt(now)).findFirst();
      TenantRuntimePlan shown = active.orElse(plans.get(0));
      source = active.isPresent() ? SOURCE_ACTIVE_PLAN : SOURCE_PLAN_NOT_ACTIVE;
      currentPlan = toPlanResponse(shown, entitlementRepository.findByTenantIdAndPlanId(tenantId, shown.getId()));
    }

    // Feature statuses reflect exactly what the runtime guard sees right now (including the
    // compatibility default), so command-created entitlements are visible immediately.
    List<FeatureStatusResponse> featureStatuses =
        java.util.Arrays.stream(RuntimeFeatureType.values())
            .map(feature -> runtimeFeaturePolicy.evaluate(tenantId, feature))
            .map(decision -> new FeatureStatusResponse(decision.featureType().name(), decision.available(), decision.reasonCode()))
            .toList();
    return new RuntimeEntitlementStatusResponse(tenantId, source, currentPlan, featureStatuses);
  }

  // --- internals ---

  private FeatureEntitlement upsertRow(
      UUID tenantId, UUID planId, RuntimeFeatureType feature, boolean enabled, String reasonCode, Instant effectiveFrom, Instant effectiveUntil, Instant now) {
    Optional<FeatureEntitlement> existingOpen = openEndedRow(tenantId, planId, feature);
    if (existingOpen.isPresent()) {
      FeatureEntitlement row = existingOpen.get();
      row.apply(enabled, reasonCode, effectiveFrom, effectiveUntil, now);
      return entitlementRepository.save(row);
    }
    return entitlementRepository.save(
        new FeatureEntitlement(tenantId, planId, feature.name(), enabled, reasonCode, effectiveFrom, effectiveUntil, now));
  }

  /** The open-ended (no effectiveUntil) row for a feature, latest effectiveFrom — the one to mutate. */
  private Optional<FeatureEntitlement> openEndedRow(UUID tenantId, UUID planId, RuntimeFeatureType feature) {
    return entitlementRepository.findByTenantIdAndPlanIdAndFeatureType(tenantId, planId, feature.name()).stream()
        .filter(row -> row.getEffectiveUntil() == null)
        .max(Comparator.comparing(FeatureEntitlement::getEffectiveFrom));
  }

  /**
   * Reject when an ACTIVE plan window {@code [from, until)} (null {@code until} = open-ended/+infinity)
   * overlaps any other ACTIVE plan for the tenant. Bounded by a single tenant-scoped, index-backed
   * query. Only ACTIVE plans are considered; SUSPENDED/EXPIRED/DISABLED never conflict.
   */
  private void requireNoActiveWindowOverlap(UUID tenantId, UUID excludePlanId, Instant from, Instant until) {
    boolean overlap =
        planRepository.findByTenantIdOrderByEffectiveFromDesc(tenantId).stream()
            .filter(other -> excludePlanId == null || !other.getId().equals(excludePlanId))
            .filter(other -> other.getStatus() == TenantRuntimePlanStatus.ACTIVE)
            .anyMatch(other -> rangesOverlap(from, until, other.getEffectiveFrom(), other.getEffectiveUntil()));
    if (overlap) {
      throw new ConflictException("An overlapping active runtime plan window already exists for this tenant");
    }
  }

  /** Half-open range overlap with null {@code until} meaning +infinity (both ranges have a non-null from). */
  private static boolean rangesOverlap(Instant aFrom, Instant aUntil, Instant bFrom, Instant bUntil) {
    boolean aStartsBeforeBEnds = bUntil == null || aFrom.isBefore(bUntil);
    boolean bStartsBeforeAEnds = aUntil == null || bFrom.isBefore(aUntil);
    return aStartsBeforeBEnds && bStartsBeforeAEnds;
  }

  private TenantRuntimePlan requirePlan(UUID planId, UUID tenantId) {
    if (planId == null) throw new IllegalArgumentException("planId is required");
    return planRepository
        .findByIdAndTenantId(planId, tenantId)
        .orElseThrow(() -> new NotFoundException("Runtime plan not found: " + planId));
  }

  private static void validateWindow(Instant effectiveFrom, Instant effectiveUntil) {
    if (effectiveFrom != null && effectiveUntil != null && !effectiveUntil.isAfter(effectiveFrom)) {
      throw new IllegalArgumentException("effectiveUntil must be after effectiveFrom");
    }
  }

  private static TenantRuntimePlanResponse toPlanResponse(TenantRuntimePlan plan, List<FeatureEntitlement> features) {
    return new TenantRuntimePlanResponse(
        plan.getId(), plan.getTenantId(), plan.getPlanCode(), plan.getStatus(), plan.getEffectiveFrom(), plan.getEffectiveUntil(),
        plan.getCreatedAt(), plan.getUpdatedAt(),
        features.stream().map(RuntimeEntitlementAdminService::toFeatureResponse).toList());
  }

  private static FeatureEntitlementResponse toFeatureResponse(FeatureEntitlement row) {
    return new FeatureEntitlementResponse(
        row.getId(), row.getFeatureType(), row.isEnabled(), row.getReasonCode(), row.getEffectiveFrom(), row.getEffectiveUntil(),
        row.getCreatedAt(), row.getUpdatedAt());
  }

  /** Safe JSON token for an instant: an ISO-8601 quoted string, or {@code null}. */
  private static String jsonInstant(Instant value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  /** Minimal JSON string encoder for safe audit metadata (escapes quotes/backslashes/control). */
  private static String jsonString(String value) {
    if (value == null) return "null";
    StringBuilder sb = new StringBuilder(value.length() + 2);
    sb.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
    return sb.toString();
  }
}
