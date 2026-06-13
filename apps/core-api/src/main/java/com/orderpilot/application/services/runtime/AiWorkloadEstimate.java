package com.orderpilot.application.services.runtime;

/**
 * Deterministic, derived signals describing the shape of a workload's input. All counts are
 * normalized to be non-negative. Holds no raw input text — only measurements of it.
 */
public record AiWorkloadEstimate(
    int textLength,
    int pageCount,
    int attachmentCount,
    int estimatedInputUnits,
    boolean suspiciousPromptInjectionSignal,
    boolean structuredIdentifierPresent,
    boolean bulkLike) {}
