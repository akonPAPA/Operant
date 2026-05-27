package com.orderpilot.domain.extraction;

import java.math.BigDecimal;

public record ConfidenceSignal(BigDecimal score, FieldConfidenceLevel level, String reason) {
  public static ConfidenceSignal fromScore(BigDecimal score, String reason) {
    return new ConfidenceSignal(score, FieldConfidenceLevel.fromScore(score), reason);
  }
}
