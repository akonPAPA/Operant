package com.orderpilot.application.services.extraction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;

@Service
public class ConfidenceScoringService {
  public BigDecimal overall(double textQuality, double providerConfidence, boolean schemaValid, boolean promptRisk) {
    double score = (textQuality * 0.35) + (providerConfidence * 0.55) + (schemaValid ? 0.10 : 0);
    if (promptRisk) score = score * 0.75;
    return BigDecimal.valueOf(Math.max(0.0, Math.min(1.0, score))).setScale(4, RoundingMode.HALF_UP);
  }
  public BigDecimal field(double providerConfidence) {
    return BigDecimal.valueOf(Math.max(0.0, Math.min(1.0, providerConfidence))).setScale(4, RoundingMode.HALF_UP);
  }
}