package com.orderpilot.application.services.analytics;

import com.orderpilot.api.dto.Stage8Dtos.RoiAssumptionsRequest;
import com.orderpilot.api.dto.Stage8Dtos.RoiAssumptionsResponse;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.analytics.RoiAssumptions;
import com.orderpilot.domain.analytics.RoiAssumptionsRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoiAssumptionsService {
  static final BigDecimal DEFAULT_MANUAL_MINUTES = new BigDecimal("12.00");
  static final BigDecimal DEFAULT_OPERATOR_HOURLY_COST = new BigDecimal("45.00");
  static final String DEFAULT_CURRENCY = "USD";
  static final String DEFAULT_ATTRIBUTION_MODE = "conservative";
  private static final Set<String> ATTRIBUTION_MODES = Set.of("conservative", "balanced", "aggressive");

  private final RoiAssumptionsRepository repository;
  private final Clock clock;

  public RoiAssumptionsService(RoiAssumptionsRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public RoiAssumptionsResponse current() {
    return currentForTenant(TenantContext.requireTenantId());
  }

  @Transactional(readOnly = true)
  public RoiAssumptionsResponse currentForTenant(UUID tenantId) {
    return repository.findByTenantId(tenantId)
        .map(this::toResponse)
        .orElseGet(() -> defaultResponse(tenantId, clock.instant()));
  }

  @Transactional
  public RoiAssumptionsResponse update(RoiAssumptionsRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    Instant now = clock.instant();
    BigDecimal minutes = positiveOrDefault(request.averageManualHandlingMinutesPerRequest(), DEFAULT_MANUAL_MINUTES);
    BigDecimal hourlyCost = positiveOrDefault(request.averageFullyLoadedOperatorHourlyCost(), DEFAULT_OPERATOR_HOURLY_COST);
    String currency = normalizeCurrency(request.defaultCurrency());
    String mode = normalizeMode(request.valueAttributionMode());
    RoiAssumptions assumptions = repository.findByTenantId(tenantId)
        .orElseGet(() -> new RoiAssumptions(tenantId, minutes, hourlyCost, currency, mode, now));
    assumptions.update(minutes, hourlyCost, currency, mode, now);
    return toResponse(repository.save(assumptions));
  }

  private RoiAssumptionsResponse toResponse(RoiAssumptions assumptions) {
    return new RoiAssumptionsResponse(
        assumptions.getTenantId(),
        assumptions.getAverageManualHandlingMinutesPerRequest(),
        assumptions.getAverageFullyLoadedOperatorHourlyCost(),
        assumptions.getDefaultCurrency(),
        assumptions.getValueAttributionMode(),
        false,
        assumptions.getUpdatedAt());
  }

  private RoiAssumptionsResponse defaultResponse(UUID tenantId, Instant now) {
    return new RoiAssumptionsResponse(tenantId, DEFAULT_MANUAL_MINUTES, DEFAULT_OPERATOR_HOURLY_COST, DEFAULT_CURRENCY, DEFAULT_ATTRIBUTION_MODE, true, now);
  }

  private BigDecimal positiveOrDefault(BigDecimal value, BigDecimal fallback) {
    return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? fallback : value;
  }

  private String normalizeCurrency(String value) {
    if (value == null || value.isBlank()) return DEFAULT_CURRENCY;
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    return normalized.length() == 3 ? normalized : DEFAULT_CURRENCY;
  }

  private String normalizeMode(String value) {
    if (value == null || value.isBlank()) return DEFAULT_ATTRIBUTION_MODE;
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    return ATTRIBUTION_MODES.contains(normalized) ? normalized : DEFAULT_ATTRIBUTION_MODE;
  }
}
