package com.orderpilot.application.services.runtime;

import com.orderpilot.domain.usage.UsageMetricType;
import java.util.UUID;

/**
 * OP-CAP-16B Usage Metering Foundation — outcome of {@code recordUsage}.
 *
 * @param eventId id of the recorded (or, when deduplicated, the previously recorded) usage event
 * @param metricType the metric the event contributed to
 * @param unitsRecorded units attributed to this event (0 when deduplicated)
 * @param periodKey deterministic counter period key
 * @param counterUnitsUsed the counter total after this recording
 * @param deduplicated true when an existing idempotency key short-circuited a second increment
 */
public record UsageRecordingResult(
    UUID eventId,
    UsageMetricType metricType,
    long unitsRecorded,
    String periodKey,
    long counterUnitsUsed,
    boolean deduplicated) {}
