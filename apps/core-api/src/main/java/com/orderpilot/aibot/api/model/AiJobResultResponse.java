package com.orderpilot.aibot.api.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Public, advisory-only view of a completed AI job for preview result retrieval.
 *
 * <p>Deliberately safe by construction: it carries ONLY the operator-facing advisory outcome and
 * lifecycle metadata. It must never expose the prompt, raw provider payload, request envelope, lease
 * owner, fencing token, internal tenant id, stack trace, or credentials. Intent fields are null until
 * an intent-classification job reaches a terminal advisory result.
 */
public record AiJobResultResponse(
    String jobId,
    String purpose,
    String status,
    boolean terminal,
    String intentKey,
    BigDecimal confidence,
    String responseDraft,
    Boolean handoffSuggested,
    String validationSummary,
    String failureClass,
    Instant createdAt,
    Instant completedAt) {}
