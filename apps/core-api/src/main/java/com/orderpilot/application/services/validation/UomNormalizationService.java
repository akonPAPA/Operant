package com.orderpilot.application.services.validation;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractedLineItem;
import com.orderpilot.domain.validation.UomNormalizationResult;
import com.orderpilot.domain.validation.UomNormalizationResultRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UomNormalizationService {
  private static final Map<String, String> UOM_MAP = Map.ofEntries(
      Map.entry("PCS", "EA"), Map.entry("PC", "EA"), Map.entry("PIECE", "EA"), Map.entry("PIECES", "EA"),
      Map.entry("EACH", "EA"), Map.entry("EA", "EA"), Map.entry("BOX", "BOX"), Map.entry("BX", "BOX"),
      Map.entry("SET", "SET"), Map.entry("KIT", "KIT"));
  private final UomNormalizationResultRepository repository;
  private final ValidationIssueService issueService;
  private final Clock clock;

  public UomNormalizationService(UomNormalizationResultRepository repository, ValidationIssueService issueService, Clock clock) {
    this.repository = repository;
    this.issueService = issueService;
    this.clock = clock;
  }

  @Transactional
  public UomNormalizationResult normalize(UUID validationRunId, UUID extractionResultId, ExtractedLineItem line) {
    String raw = line.getRawUom() == null ? line.getNormalizedUom() : line.getRawUom();
    String key = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    String normalized = UOM_MAP.get(key);
    String status = normalized == null ? "UNKNOWN" : normalized.equals(key) ? "SAME_AS_INPUT" : "NORMALIZED";
    BigDecimal confidence = normalized == null ? BigDecimal.ZERO : new BigDecimal("0.9800");
    UomNormalizationResult result = repository.save(new UomNormalizationResult(TenantContext.requireTenantId(), validationRunId, line.getId(), raw, normalized, status, confidence, clock.instant()));
    if (normalized == null) {
      issueService.open(validationRunId, extractionResultId, line.getId(), null, "UOM_UNKNOWN", "WARNING", "Unit of measure requires human review", "{\"rawUom\":\"" + (raw == null ? "" : raw) + "\"}");
    } else if ("NORMALIZED".equals(status)) {
      issueService.open(validationRunId, extractionResultId, line.getId(), null, "UOM_NORMALIZED", "INFO", "Unit of measure was normalized to " + normalized, "{\"normalizedUom\":\"" + normalized + "\"}");
    }
    return result;
  }

  @Transactional(readOnly = true)
  public List<UomNormalizationResult> list(UUID validationRunId) {
    return repository.findByTenantIdAndValidationRunId(TenantContext.requireTenantId(), validationRunId);
  }
}
