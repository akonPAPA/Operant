package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class Stage5Dtos {
  private Stage5Dtos() {}

  public record ValidationRunRequest(UUID extractionResultId, String mode) {}
  public record ValidationRunResponse(UUID id, UUID extractionResultId, String sourceType, String status, String overallStatus, BigDecimal overallConfidence, Instant startedAt, Instant finishedAt, String errorMessage, Instant createdAt) {}
}
