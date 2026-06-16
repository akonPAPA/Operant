package com.orderpilot.application.services.runtime;

/**
 * Deterministic, advisory routing decision for an AI/heavy workload.
 *
 * <p>This is decision metadata only. It does not call AI, select a provider, enforce quotas, or
 * change any business write path. {@code reasonCode} is a stable token (see {@link
 * AiWorkloadReasonCodes}) suitable for metrics and audit — it never contains raw input text.
 */
public record AiRoutingDecision(
    AiWorkloadType workloadType,
    WorkloadSize workloadSize,
    ModelTier selectedTier,
    boolean asyncRequired,
    boolean humanReviewRequired,
    int estimatedInputUnits,
    String reasonCode) {}
