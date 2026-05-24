package com.orderpilot.domain.extraction;

import java.math.BigDecimal;

public enum FieldConfidenceLevel {
  HIGH,
  MEDIUM,
  LOW;

  public static FieldConfidenceLevel fromScore(BigDecimal score) {
    if (score == null) return LOW;
    if (score.compareTo(new BigDecimal("0.80")) >= 0) return HIGH;
    if (score.compareTo(new BigDecimal("0.50")) >= 0) return MEDIUM;
    return LOW;
  }
}
