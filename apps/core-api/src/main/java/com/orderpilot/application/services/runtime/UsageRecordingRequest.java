package com.orderpilot.application.services.runtime;

import com.orderpilot.domain.usage.UsageEventType;
import com.orderpilot.domain.usage.UsageMetricType;
import com.orderpilot.domain.usage.UsagePeriodType;
import com.orderpilot.domain.usage.UsageSource;
import java.util.UUID;

/**
 * OP-CAP-16B Usage Metering Foundation — a request to record one usage event.
 *
 * <p>All fields are typed, bounded, safe tokens. There is deliberately no free-form text/metadata
 * map: the service cannot be handed raw customer message, document, prompt, or AI-output text. The
 * optional {@code workloadType}/{@code modelTier}/{@code reasonCode} are the Stage 16A decision
 * tokens (enum names / stable reason code) and {@code sourceRef} is an internal id reference only.
 *
 * @param tenantId required tenant scope
 * @param eventType required activity type
 * @param metricType required quota metric
 * @param units consumed units; negative/normalized to {@code 0} by the service, stored as {@code long}
 * @param source required originating subsystem
 * @param workloadType nullable Stage 16A {@code AiWorkloadType} name
 * @param modelTier nullable Stage 16A {@code ModelTier} name
 * @param asyncRequired Stage 16A routing flag (advisory metadata)
 * @param humanReviewRequired Stage 16A routing flag (advisory metadata)
 * @param reasonCode nullable stable reason token (never raw text)
 * @param sourceRef nullable internal id reference (e.g. message/document id)
 * @param idempotencyKey nullable; when present, duplicate recording is a no-op
 * @param periodType nullable; defaults to {@link UsagePeriodType#MONTH}
 */
public record UsageRecordingRequest(
    UUID tenantId,
    UsageEventType eventType,
    UsageMetricType metricType,
    long units,
    UsageSource source,
    String workloadType,
    String modelTier,
    boolean asyncRequired,
    boolean humanReviewRequired,
    String reasonCode,
    String sourceRef,
    String idempotencyKey,
    UsagePeriodType periodType) {}
