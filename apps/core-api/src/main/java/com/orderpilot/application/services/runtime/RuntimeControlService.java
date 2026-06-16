package com.orderpilot.application.services.runtime;

import com.orderpilot.domain.usage.UsageMath;
import org.springframework.stereotype.Service;

/**
 * OP-CAP-27 Runtime Control Mainline — the single, testable runtime-control decision path for
 * AI/workload-like operations. It consolidates two existing runtime pieces into one verdict without
 * introducing new infrastructure:
 *
 * <ol>
 *   <li>OP-CAP-16A {@link AiWorkloadClassifier} — deterministic workload classification + sync/async +
 *       human-review routing;
 *   <li>OP-CAP-16C/D/E/F {@link RuntimeGuardService} — entitlement → quota → rate enforcement.
 * </ol>
 *
 * <p>Canonical ordering (cheapest, safest first): <b>dedup → classify → entitlement → quota → rate →
 * sync/async</b>. A duplicate idempotent request short-circuits before classification and before any
 * guard budget is consulted (mirroring the existing idempotency-before-guard behavior in
 * {@code AiWorkService}). Classification by itself never grants authority: even a small,
 * deterministic-looking workload must still pass the guard before it is allowed.
 *
 * <p>Safety posture: this service is deterministic and side-effect-free. It records no usage, calls no
 * AI/provider/external service, and mutates no business or advisory table — it only produces a {@link
 * RuntimeControlDecision}. Tenant/actor/operation/feature are trusted inputs the caller resolves
 * server-side; nothing here is taken from a client request body. No raw input text, prompt, or provider
 * payload is read, logged, or returned — only measurements and stable reason codes.
 *
 * <p>Two entry points share the same dedup → classify → guard pipeline:
 * {@link #decide(RuntimeControlRequest)} never throws (denials are returned as a typed outcome), while
 * {@link #enforce(RuntimeControlRequest)} delegates the entitlement/quota/rate step to
 * {@link RuntimeGuardService#enforce} so a denial throws the established stable mapped exception before
 * any work — for callers (e.g. the document-extraction submission boundary) that execute as soon as the
 * decision allows. The rate budget is consulted exactly once on either path.
 */
@Service
public class RuntimeControlService {
  private final AiWorkloadClassifier classifier;
  private final RuntimeGuardService runtimeGuardService;

  public RuntimeControlService(
      AiWorkloadClassifier classifier, RuntimeGuardService runtimeGuardService) {
    this.classifier = classifier;
    this.runtimeGuardService = runtimeGuardService;
  }

  /**
   * Produce the consolidated runtime-control decision. Never throws on a denial — denials are returned
   * as a typed {@link RuntimeControlOutcome} so the caller can map them to a safe response.
   */
  public RuntimeControlDecision decide(RuntimeControlRequest request) {
    validate(request);
    boolean systemActor = request.actorId() == null;

    // 1. Dedup short-circuits before classification and before any guard budget is consulted.
    if (isDuplicate(request)) {
      return deduped(request, systemActor);
    }

    // 2. Deterministic classification (no side effects, no raw text echoed).
    AiRoutingDecision routing = classifier.classify(request.classification());
    RuntimeControlDecision classificationGate = classificationGate(routing, request, systemActor);
    if (classificationGate != null) {
      return classificationGate;
    }

    // 3. Entitlement -> quota -> rate (read-only; consumes no usage here). Classification alone never
    //    grants authority: the guard still gates an otherwise-allowable small/deterministic workload.
    RuntimeGuardDecision guard = runtimeGuardService.checkRuntimeGuard(
        guardRequest(request, effectiveUnits(request, routing), systemActor), request.featureType());
    if (!guard.allowed()) {
      RuntimeControlOutcome outcome = mapDenial(guard.reasonCode());
      return build(outcome, guard.reasonCode(), routing, request, systemActor, false,
          guard.httpStatusHint(), guard.retryAfterSeconds(), denialMessage(outcome));
    }

    // 4. Allowed — the deterministic classifier decides whether work runs sync or must go async.
    return allowed(routing, guard.reasonCode(), request, systemActor);
  }

  /**
   * Enforcing variant for callers that execute as soon as the decision allows. Runs the same dedup →
   * classify → entitlement/quota/rate pipeline as {@link #decide(RuntimeControlRequest)}, but delegates
   * the entitlement/quota/rate step to {@link RuntimeGuardService#enforce} so a denial throws the
   * established stable mapped exception (403 feature/quota, 429 rate) BEFORE any work — preserving the
   * existing API error contract and consuming the rate budget exactly once. A duplicate / needs-review /
   * unsupported outcome is returned (not thrown); the caller fail-closes on any non-allowed decision.
   */
  public RuntimeControlDecision enforce(RuntimeControlRequest request) {
    validate(request);
    boolean systemActor = request.actorId() == null;

    if (isDuplicate(request)) {
      return deduped(request, systemActor);
    }
    AiRoutingDecision routing = classifier.classify(request.classification());
    RuntimeControlDecision classificationGate = classificationGate(routing, request, systemActor);
    if (classificationGate != null) {
      return classificationGate;
    }
    // Throws RuntimeFeatureNotAvailableException (403) / RuntimeQuotaExceededException (403) /
    // RuntimeRateLimitedException (429) on denial, before any work; returns on allow.
    runtimeGuardService.enforce(
        guardRequest(request, effectiveUnits(request, routing), systemActor), request.featureType());
    return allowed(routing, RuntimeGuardReasonCodes.ALLOWED, request, systemActor);
  }

  // ----------------------------- shared pipeline helpers -----------------------------

  private static void validate(RuntimeControlRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("runtime control request is required");
    }
    if (request.tenantId() == null) {
      throw new IllegalArgumentException("tenantId is required");
    }
    if (request.operationType() == null) {
      throw new IllegalArgumentException("operationType is required");
    }
    if (request.classification() == null) {
      throw new IllegalArgumentException("classification is required");
    }
  }

  private static boolean isDuplicate(RuntimeControlRequest request) {
    return request.idempotencyKey() != null
        && !request.idempotencyKey().isBlank()
        && request.duplicateDetected();
  }

  private static RuntimeControlDecision deduped(RuntimeControlRequest request, boolean systemActor) {
    AiWorkloadType type = request.classification().requestedType() == null
        ? AiWorkloadType.UNKNOWN : request.classification().requestedType();
    return new RuntimeControlDecision(
        RuntimeControlOutcome.DEDUPED, RuntimeControlReasonCodes.DEDUP_IDEMPOTENT_HIT, type,
        ModelTier.NONE, request.tenantId(), request.actorId(), systemActor, request.idempotencyKey(),
        false, false, false, false, 0, 200, 0L, "Duplicate request; the existing result is returned.");
  }

  /**
   * Classification-only gates that must short-circuit before the guard: unknown workloads fail safe to
   * manual handling (and never consult the guard), and suspicious/bulk workloads route to a human
   * operator. Returns {@code null} when the workload is classified and may proceed to the guard.
   */
  private RuntimeControlDecision classificationGate(
      AiRoutingDecision routing, RuntimeControlRequest request, boolean systemActor) {
    if (routing.workloadType() == AiWorkloadType.UNKNOWN) {
      return build(RuntimeControlOutcome.UNSUPPORTED, routing.reasonCode(), routing, request,
          systemActor, false, 422, 0L, "Workload could not be classified; routed to manual handling.");
    }
    if (routing.humanReviewRequired()) {
      return build(RuntimeControlOutcome.NEEDS_REVIEW, routing.reasonCode(), routing, request,
          systemActor, false, 200, 0L, "Workload routed to human review.");
    }
    return null;
  }

  private static RuntimeControlDecision allowed(
      AiRoutingDecision routing, String reasonCode, RuntimeControlRequest request, boolean systemActor) {
    boolean async = routing.asyncRequired();
    boolean providerAllowed = !async && usesModel(routing.selectedTier());
    RuntimeControlOutcome outcome =
        async ? RuntimeControlOutcome.ALLOW_ASYNC : RuntimeControlOutcome.ALLOW_SYNC;
    String message = async
        ? "Allowed; handed to the asynchronous runtime."
        : providerAllowed
            ? "Allowed for synchronous processing."
            : "Allowed on the deterministic synchronous path.";
    return build(outcome, reasonCode, routing, request, systemActor, providerAllowed, 200, 0L, message);
  }

  private static long effectiveUnits(RuntimeControlRequest request, AiRoutingDecision routing) {
    return request.requestedUnits() >= 0
        ? UsageMath.clampNonNegative(request.requestedUnits())
        : UsageMath.clampNonNegative(routing.estimatedInputUnits());
  }

  private static RuntimeGuardRequest guardRequest(
      RuntimeControlRequest request, long units, boolean systemActor) {
    return new RuntimeGuardRequest(request.tenantId(), request.operationType(), null, units,
        request.idempotencyKey(), systemActor ? "SYSTEM" : "OPERATOR", null);
  }

  private static RuntimeControlOutcome mapDenial(String guardReasonCode) {
    if (RuntimeGuardReasonCodes.isFeatureDenial(guardReasonCode)) {
      return RuntimeControlOutcome.DENY_ENTITLEMENT;
    }
    if (RuntimeGuardReasonCodes.RATE_LIMIT_EXCEEDED.equals(guardReasonCode)) {
      return RuntimeControlOutcome.DENY_RATE_LIMIT;
    }
    return RuntimeControlOutcome.DENY_QUOTA;
  }

  private static String denialMessage(RuntimeControlOutcome outcome) {
    return switch (outcome) {
      case DENY_ENTITLEMENT -> "Denied; this feature is not available for the tenant.";
      case DENY_RATE_LIMIT -> "Denied; too many requests, please retry later.";
      default -> "Denied; the tenant usage quota has been reached.";
    };
  }

  /** A model tier that requires an AI model call (as opposed to rules-only / human-review / none). */
  private static boolean usesModel(ModelTier tier) {
    return tier == ModelTier.SMALL_LOCAL || tier == ModelTier.MEDIUM || tier == ModelTier.LARGE;
  }

  private static RuntimeControlDecision build(
      RuntimeControlOutcome outcome,
      String reasonCode,
      AiRoutingDecision routing,
      RuntimeControlRequest request,
      boolean systemActor,
      boolean providerAllowed,
      int httpStatusHint,
      long retryAfterSeconds,
      String safeMessage) {
    return new RuntimeControlDecision(
        outcome, reasonCode, routing.workloadType(), routing.selectedTier(), request.tenantId(),
        request.actorId(), systemActor, request.idempotencyKey(), false, providerAllowed,
        routing.asyncRequired(), routing.humanReviewRequired(), routing.estimatedInputUnits(),
        httpStatusHint, retryAfterSeconds, safeMessage);
  }
}
