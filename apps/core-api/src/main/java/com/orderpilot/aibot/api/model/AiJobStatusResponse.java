package com.orderpilot.aibot.api.model;

import java.time.Instant;

public record AiJobStatusResponse(
    String jobId,
    String purpose,
    String status,
    Instant createdAt,
    Instant completedAt,
    String validationSummary,
    String failureClass) {}
