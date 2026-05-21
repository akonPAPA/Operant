package com.orderpilot.application.services.connector;

import java.time.Instant;
import java.util.List;

public record ConnectionHealthCheckResult(
    String providerType,
    boolean healthy,
    String statusCode,
    String message,
    Instant checkedAt,
    List<ConnectionDiagnostic> diagnostics) {}
