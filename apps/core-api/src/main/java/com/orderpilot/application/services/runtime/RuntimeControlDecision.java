package com.orderpilot.application.services.runtime;

import java.util.UUID;

/**
 * OP-CAP-27 Runtime Control Mainline — the consolidated decision a caller acts on before doing
 * expensive/AI/async work. It composes the deterministic {@link AiWorkloadClassifier} routing with the
 * {@link RuntimeGuardService} entitlement/quota/rate verdict into one safe object.
 *
 * <p>It is decision metadata only: producing it records no usage, calls no provider, and mutates no
 * business state. {@code reasonCode}, {@code safeMessage} and every other field are safe for audit/UI —
 * they never contain raw prompts, provider payloads, secrets, or input text.
 *
 * @param outcome the single stable outcome (allow sync/async, a typed denial, dedup, review, unsupported)
 * @param reasonCode stable reason token (classifier / guard / control reason codes)
 * @param workloadType the classified workload type
 * @param modelTier the advisory routing tier (e.g. RULES_ONLY, SMALL_LOCAL, HUMAN_REVIEW)
 * @param tenantId tenant scope echoed back
 * @param actorId resolved actor ({@code null} when a system/job actor)
 * @param systemActor whether the decision was made for a trusted system/job actor
 * @param idempotencyKey the dedup key carried through (may be {@code null})
 * @param usageMeteringApplied always {@code false} here — the control decision never records usage; the
 *     caller meters per the existing usage design once work actually runs
 * @param providerAllowed whether an AI provider may be invoked synchronously now
 * @param asyncRequired whether the work must be handed to the async runtime
 * @param humanReviewRequired whether the workload must be routed to a human operator
 * @param estimatedInputUnits deterministic estimated input units (no billing meaning)
 * @param httpStatusHint advisory HTTP status (200 / 403 / 422 / 429)
 * @param retryAfterSeconds seconds to wait before retrying (rate-limit denials only; else 0)
 * @param safeMessage a short operator-safe message with no internals
 */
public record RuntimeControlDecision(
    RuntimeControlOutcome outcome,
    String reasonCode,
    AiWorkloadType workloadType,
    ModelTier modelTier,
    UUID tenantId,
    UUID actorId,
    boolean systemActor,
    String idempotencyKey,
    boolean usageMeteringApplied,
    boolean providerAllowed,
    boolean asyncRequired,
    boolean humanReviewRequired,
    int estimatedInputUnits,
    int httpStatusHint,
    long retryAfterSeconds,
    String safeMessage) {

  /** Whether the operation may proceed (synchronously or asynchronously). */
  public boolean allowed() {
    return outcome != null && outcome.allowed();
  }

  public RuntimeWorkloadType runtimeWorkloadType() {
    return RuntimeWorkloadType.from(null, workloadType);
  }
}
