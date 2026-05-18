package com.orderpilot.common.errors;

import java.time.Instant;
import java.util.List;

public record ApiErrorResponse(
    String code,
    String message,
    int status,
    String path,
    Instant timestamp,
    List<FieldViolation> violations
) {
  public record FieldViolation(String field, String message) {
  }
}