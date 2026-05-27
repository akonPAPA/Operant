package com.orderpilot.application.services.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractedLineItem;
import com.orderpilot.domain.validation.UomNormalizationResult;
import com.orderpilot.domain.validation.UomNormalizationResultRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UomNormalizationServiceTest {
  private final UomNormalizationResultRepository repository = mock(UomNormalizationResultRepository.class);
  private final ValidationIssueService issueService = mock(ValidationIssueService.class);
  private final ApprovalRequirementService approvalService = mock(ApprovalRequirementService.class);
  private final UomNormalizationService service = new UomNormalizationService(repository, issueService, approvalService, Clock.fixed(Instant.parse("2026-05-18T00:00:00Z"), ZoneOffset.UTC));

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void normalizesPcsToEach() {
    TenantContext.setTenantId(UUID.randomUUID());
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    ExtractedLineItem line = new ExtractedLineItem(UUID.randomUUID(), UUID.randomUUID(), 1, "SKU-1", "Filter", "2", new BigDecimal("2"), "pcs", null, new BigDecimal("0.9"), null, Instant.now());

    UomNormalizationResult result = service.normalize(UUID.randomUUID(), UUID.randomUUID(), line);

    assertThat(result.getStatus()).isEqualTo("NORMALIZED");
    assertThat(result.getNormalizedUom()).isEqualTo("EA");
  }
}
