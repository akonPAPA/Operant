package com.orderpilot.application.services.runtime;

import com.orderpilot.domain.usage.QuotaPolicy;
import com.orderpilot.domain.usage.QuotaPolicyRepository;
import com.orderpilot.domain.usage.UsageCounter;
import com.orderpilot.domain.usage.UsageCounterRepository;
import com.orderpilot.domain.usage.UsageEvent;
import com.orderpilot.domain.usage.UsageEventRepository;
import com.orderpilot.domain.usage.UsageEventType;
import com.orderpilot.domain.usage.UsageMath;
import com.orderpilot.domain.usage.UsageMetricType;
import com.orderpilot.domain.usage.UsagePeriodType;
import com.orderpilot.domain.usage.UsageSource;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-CAP-16B Usage Metering Foundation application service.
 *
 * <p>Records tenant usage as append-only {@link UsageEvent}s and maintains aggregated {@link
 * UsageCounter}s for fast quota checks. It consumes Stage 16A {@link AiRoutingDecision} outputs
 * (estimated input units, workload type, model tier, async/human-review flags) as the primary usage
 * source.
 *
 * <p>Safety boundaries (Stage 16B):
 *
 * <ul>
 *   <li>No AI call, no external/provider call, no billing provider.
 *   <li>No order/quote/inventory/customer/approval/validation/external write — only usage rows.
 *   <li>No raw customer/document/prompt/AI-output/PII text is ever stored; metadata is built from
 *       safe routing tokens only.
 *   <li>{@code checkQuota} is advisory — it never blocks a live request path in this stage.
 *   <li>All counters use overflow-safe {@code long} arithmetic ({@link UsageMath}).
 * </ul>
 */
@Service
public class UsageMeterService {
  private final UsageEventRepository usageEventRepository;
  private final UsageCounterRepository usageCounterRepository;
  private final QuotaPolicyRepository quotaPolicyRepository;
  private final Clock clock;

  public UsageMeterService(
      UsageEventRepository usageEventRepository,
      UsageCounterRepository usageCounterRepository,
      QuotaPolicyRepository quotaPolicyRepository,
      Clock clock) {
    this.usageEventRepository = usageEventRepository;
    this.usageCounterRepository = usageCounterRepository;
    this.quotaPolicyRepository = quotaPolicyRepository;
    this.clock = clock;
  }

  /**
   * Record one usage event and update the matching period counter.
   *
   * <p>Idempotent: when {@code idempotencyKey} is supplied and already recorded for the tenant, the
   * existing event is returned with {@code deduplicated=true} and the counter is NOT incremented a
   * second time. Negative/absurd units are clamped to a safe non-negative {@code long}.
   */
  @Transactional
  public UsageRecordingResult recordUsage(UsageRecordingRequest request) {
    if (request == null) throw new IllegalArgumentException("usage request is required");
    UUID tenantId = require(request.tenantId(), "tenantId");
    UsageEventType eventType = require(request.eventType(), "eventType");
    UsageMetricType metricType = require(request.metricType(), "metricType");
    UsageSource source = request.source() == null ? UsageSource.SYSTEM : request.source();
    UsagePeriodType periodType =
        request.periodType() == null ? UsagePeriodType.MONTH : request.periodType();

    long units = UsageMath.clampNonNegative(request.units());
    String idempotencyKey = normalize(request.idempotencyKey());
    Instant now = clock.instant();
    String periodKey = periodType.periodKey(now);

    // Idempotency: a repeated key resolves the prior event and does not re-increment the counter.
    if (idempotencyKey != null) {
      var existing = usageEventRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
      if (existing.isPresent()) {
        UsageEvent prior = existing.get();
        long counterUnits = currentCounterUnits(tenantId, prior.getMetricType(), periodKey);
        return new UsageRecordingResult(
            prior.getId(), prior.getMetricType(), 0L, periodKey, counterUnits, true);
      }
    }

    UsageEvent event =
        new UsageEvent(
            tenantId,
            eventType,
            metricType,
            normalize(request.workloadType()),
            normalize(request.modelTier()),
            units,
            source,
            normalize(request.sourceRef()),
            idempotencyKey,
            buildMetadataJson(request, units),
            now,
            now);
    UsageEvent saved = usageEventRepository.save(event);

    long counterTotal = incrementCounter(tenantId, metricType, periodKey, units, now);
    return new UsageRecordingResult(
        saved.getId(), metricType, units, periodKey, counterTotal, false);
  }

  /**
   * Convenience overload consuming a Stage 16A {@link AiRoutingDecision}: records an {@code
   * AI_ROUTING_DECISION} event metered on {@code AI_INPUT_UNITS} using the decision's estimated input
   * units, carrying workload type, model tier, and async/human-review flags as safe metadata.
   */
  @Transactional
  public UsageRecordingResult recordAiRoutingDecision(
      UUID tenantId, AiRoutingDecision decision, String sourceRef, String idempotencyKey) {
    if (decision == null) throw new IllegalArgumentException("routing decision is required");
    long units = UsageMath.clampNonNegative(decision.estimatedInputUnits());
    UsageRecordingRequest request =
        new UsageRecordingRequest(
            tenantId,
            UsageEventType.AI_ROUTING_DECISION,
            UsageMetricType.AI_INPUT_UNITS,
            units,
            UsageSource.AI_ROUTER,
            decision.workloadType() == null ? null : decision.workloadType().name(),
            decision.selectedTier() == null ? null : decision.selectedTier().name(),
            decision.asyncRequired(),
            decision.humanReviewRequired(),
            decision.reasonCode(),
            sourceRef,
            idempotencyKey,
            UsagePeriodType.MONTH);
    return recordUsage(request);
  }

  /**
   * Records safe runtime-control decision evidence. Allowed decisions contribute consumed units only
   * after the caller confirms downstream work was accepted; denied/review/dedup decisions write a
   * zero-unit evidence event with rejected units in metadata, so quota counters are not inflated.
   */
  @Transactional
  public UsageRecordingResult recordRuntimeControlDecision(
      RuntimeControlRequest request,
      RuntimeControlDecision decision,
      UsageSource source,
      String sourceRef,
      String idempotencyKey,
      boolean downstreamInvoked) {
    return recordRuntimeControlDecisionInternal(
        request, decision, source, sourceRef, idempotencyKey, downstreamInvoked);
  }

  /**
   * Same evidence recording, isolated from the caller transaction. Use this for fail-closed
   * admission denials that immediately rethrow, otherwise the evidence row would roll back with the
   * rejected business operation.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public UsageRecordingResult recordRuntimeControlDecisionEvidence(
      RuntimeControlRequest request,
      RuntimeControlDecision decision,
      UsageSource source,
      String sourceRef,
      String idempotencyKey,
      boolean downstreamInvoked) {
    return recordRuntimeControlDecisionInternal(
        request, decision, source, sourceRef, idempotencyKey, downstreamInvoked);
  }

  /**
   * Advisory quota check for {@code metricType} given {@code additionalUnits}. Allows by default when
   * no policy exists. Never blocks a live request path in Stage 16B — callers receive a decision
   * object only.
   */
  @Transactional(readOnly = true)
  public QuotaDecision checkQuota(
      UUID tenantId, UsageMetricType metricType, long additionalUnits) {
    require(tenantId, "tenantId");
    require(metricType, "metricType");
    long requested = UsageMath.clampNonNegative(additionalUnits);

    var policyOpt = quotaPolicyRepository.findByTenantIdAndMetricType(tenantId, metricType);
    if (policyOpt.isEmpty()) {
      // No policy → allow by default. Period key uses the default MONTH window for reporting.
      String periodKey = UsagePeriodType.MONTH.periodKey(clock.instant());
      long used = currentCounterUnits(tenantId, metricType, periodKey);
      return new QuotaDecision(
          true, metricType, periodKey, null, used, requested, null, UsageReasonCodes.NO_POLICY);
    }

    QuotaPolicy policy = policyOpt.get();
    String periodKey = policy.getPeriodType().periodKey(clock.instant());
    long used = currentCounterUnits(tenantId, metricType, periodKey);
    long limit = UsageMath.clampNonNegative(policy.getLimitUnits());
    long projected = UsageMath.safeAdd(used, requested);
    boolean allowed = projected <= limit;
    long remaining = UsageMath.remaining(limit, used);
    return new QuotaDecision(
        allowed,
        metricType,
        periodKey,
        limit,
        used,
        requested,
        remaining,
        allowed ? UsageReasonCodes.WITHIN_LIMIT : UsageReasonCodes.LIMIT_EXCEEDED);
  }

  private long incrementCounter(
      UUID tenantId, UsageMetricType metricType, String periodKey, long units, Instant now) {
    UsageCounter counter =
        usageCounterRepository
            .findWithLockByTenantIdAndMetricTypeAndPeriodKey(tenantId, metricType, periodKey)
            .orElseGet(() -> new UsageCounter(tenantId, metricType, periodKey, 0L, now));
    long total = counter.addUnits(units, now);
    usageCounterRepository.save(counter);
    return total;
  }

  private long currentCounterUnits(UUID tenantId, UsageMetricType metricType, String periodKey) {
    return usageCounterRepository
        .findByTenantIdAndMetricTypeAndPeriodKey(tenantId, metricType, periodKey)
        .map(UsageCounter::getUnitsUsed)
        .orElse(0L);
  }

  private UsageRecordingResult recordRuntimeControlDecisionInternal(
      RuntimeControlRequest request,
      RuntimeControlDecision decision,
      UsageSource source,
      String sourceRef,
      String idempotencyKey,
      boolean downstreamInvoked) {
    if (request == null) throw new IllegalArgumentException("runtime control request is required");
    if (decision == null) throw new IllegalArgumentException("runtime control decision is required");
    UUID tenantId = require(decision.tenantId(), "tenantId");
    if (!tenantId.equals(request.tenantId())) {
      throw new IllegalArgumentException("runtime decision tenant does not match request tenant");
    }

    UsageMetricType metricType = EndpointWeightPolicy.defaultMetricFor(request.operationType());
    if (metricType == null) {
      metricType = UsageMetricType.AI_ROUTING_DECISION;
    }
    UsageSource safeSource = source == null ? UsageSource.SYSTEM : source;
    UsagePeriodType periodType = UsagePeriodType.MONTH;
    Instant now = clock.instant();
    String periodKey = periodType.periodKey(now);
    String normalizedIdempotencyKey = normalize(idempotencyKey);

    if (normalizedIdempotencyKey != null) {
      var existing = usageEventRepository.findByTenantIdAndIdempotencyKey(tenantId, normalizedIdempotencyKey);
      if (existing.isPresent()) {
        UsageEvent prior = existing.get();
        long counterUnits = currentCounterUnits(tenantId, prior.getMetricType(), periodKey);
        return new UsageRecordingResult(
            prior.getId(), prior.getMetricType(), 0L, periodKey, counterUnits, true);
      }
    }

    long requestedUnits = effectiveRuntimeUnits(request, decision);
    long consumedUnits = decision.allowed() && downstreamInvoked ? requestedUnits : 0L;
    long rejectedUnits =
        decision.allowed() || decision.outcome() == RuntimeControlOutcome.DEDUPED ? 0L : requestedUnits;
    RuntimeExecutionMode resolvedMode =
        decision.asyncRequired() ? RuntimeExecutionMode.ASYNC : RuntimeExecutionMode.SYNC;
    UsageEvent event =
        new UsageEvent(
            tenantId,
            UsageEventType.RUNTIME_CONTROL_DECISION,
            metricType,
            decision.runtimeWorkloadType().name(),
            decision.modelTier() == null ? null : decision.modelTier().name(),
            consumedUnits,
            safeSource,
            normalize(sourceRef),
            normalizedIdempotencyKey,
            buildRuntimeDecisionMetadataJson(
                request, decision, requestedUnits, consumedUnits, rejectedUnits, resolvedMode,
                downstreamInvoked),
            now,
            now);
    UsageEvent saved = usageEventRepository.save(event);
    long counterUnits =
        consumedUnits > 0L
            ? incrementCounter(tenantId, metricType, periodKey, consumedUnits, now)
            : currentCounterUnits(tenantId, metricType, periodKey);
    return new UsageRecordingResult(
        saved.getId(), metricType, consumedUnits, periodKey, counterUnits, false);
  }

  private static long effectiveRuntimeUnits(
      RuntimeControlRequest request, RuntimeControlDecision decision) {
    if (request.requestedUnits() >= 0L) {
      return UsageMath.clampNonNegative(request.requestedUnits());
    }
    return UsageMath.clampNonNegative(decision.estimatedInputUnits());
  }

  private static <T> T require(T value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static String normalize(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /**
   * Build bounded, sanitized usage metadata from safe routing tokens only. Never serializes raw
   * customer/document/prompt/AI-output text — the request type does not even carry such text.
   */
  private static String buildMetadataJson(UsageRecordingRequest request, long units) {
    StringBuilder sb = new StringBuilder("{");
    sb.append("\"estimatedInputUnits\":").append(units);
    sb.append(",\"asyncRequired\":").append(request.asyncRequired());
    sb.append(",\"humanReviewRequired\":").append(request.humanReviewRequired());
    appendOptional(sb, "workloadType", normalize(request.workloadType()));
    appendOptional(sb, "modelTier", normalize(request.modelTier()));
    appendOptional(sb, "reasonCode", normalize(request.reasonCode()));
    appendOptional(sb, "sourceRef", normalize(request.sourceRef()));
    return sb.append("}").toString();
  }

  private static String buildRuntimeDecisionMetadataJson(
      RuntimeControlRequest request,
      RuntimeControlDecision decision,
      long requestedUnits,
      long consumedUnits,
      long rejectedUnits,
      RuntimeExecutionMode resolvedMode,
      boolean downstreamInvoked) {
    StringBuilder sb = new StringBuilder("{");
    sb.append("\"requestedCostUnits\":").append(requestedUnits);
    sb.append(",\"consumedCostUnits\":").append(consumedUnits);
    sb.append(",\"rejectedCostUnits\":").append(rejectedUnits);
    sb.append(",\"downstreamInvoked\":").append(downstreamInvoked);
    sb.append(",\"asyncRequired\":").append(decision.asyncRequired());
    sb.append(",\"humanReviewRequired\":").append(decision.humanReviewRequired());
    appendOptional(sb, "outcome", decision.outcome() == null ? null : decision.outcome().name());
    appendOptional(sb, "reasonCode", normalize(decision.reasonCode()));
    appendOptional(sb, "operationType", request.operationType() == null ? null : request.operationType().name());
    appendOptional(sb, "workloadType", decision.runtimeWorkloadType().name());
    appendOptional(sb, "aiWorkloadType", decision.workloadType() == null ? null : decision.workloadType().name());
    appendOptional(sb, "modelTier", decision.modelTier() == null ? null : decision.modelTier().name());
    appendOptional(sb, "requestedExecutionMode", request.effectiveRequestedExecutionMode().name());
    appendOptional(sb, "resolvedExecutionMode", resolvedMode.name());
    appendOptional(sb, "actorType", decision.systemActor() ? "SYSTEM" : "OPERATOR");
    return sb.append("}").toString();
  }

  private static void appendOptional(StringBuilder sb, String key, String value) {
    if (value != null) {
      sb.append(",\"").append(key).append("\":").append(jsonStr(value));
    }
  }

  private static String jsonStr(String value) {
    StringBuilder sb = new StringBuilder("\"");
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
    return sb.append("\"").toString();
  }
}
