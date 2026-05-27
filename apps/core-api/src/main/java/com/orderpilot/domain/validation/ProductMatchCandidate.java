package com.orderpilot.domain.validation;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductMatchCandidate(
    UUID productId,
    String matchType,
    BigDecimal confidence,
    String status
) {}
