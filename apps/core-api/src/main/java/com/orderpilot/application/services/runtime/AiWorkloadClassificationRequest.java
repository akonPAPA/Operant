package com.orderpilot.application.services.runtime;

/**
 * Input to the deterministic {@link AiWorkloadClassifier}.
 *
 * <p>{@code text} may be null (treated as empty). Negative {@code pageCount}/{@code attachmentCount}
 * are normalized to zero by the classifier. The classifier never logs or echoes {@code text}.
 */
public record AiWorkloadClassificationRequest(
    AiWorkloadType requestedType,
    String text,
    int pageCount,
    int attachmentCount,
    boolean hasStructuredSkuOrIdentifier,
    boolean suspiciousPromptInjectionSignal) {}
