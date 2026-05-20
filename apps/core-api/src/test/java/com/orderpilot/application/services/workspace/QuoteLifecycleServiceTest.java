package com.orderpilot.application.services.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.workspace.*;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuoteLifecycleService.class, CoreConfiguration.class})
class QuoteLifecycleServiceTest {
  @Autowired private QuoteLifecycleService service;
  @Autowired private DraftQuoteRepository quoteRepository;
  @Autowired private DraftQuoteLineRepository lineRepository;
  @Autowired private QuoteValidationIssueRepository issueRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void pendingSubstituteDecisionKeepsQuoteInSubstitutionReview() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DraftQuote quote = quoteRepository.save(new DraftQuote(tenantId, "DQ-1", "API", null, null, null, null, "NEEDS_REVIEW", "NEEDS_REVIEW", true, "USD", UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-05-20T00:00:00Z")));
    DraftQuoteLine line = lineRepository.save(new DraftQuoteLine(tenantId, quote.getId(), 1, "Brake pads", "BRK", "BRK", UUID.randomUUID(), "Brake pads", BigDecimal.ONE, "EA", null, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ONE, "[]", "NEEDS_REVIEW", "NEEDS_REVIEW", Instant.parse("2026-05-20T00:00:00Z")));
    line.markSubstituteSuggested("SUBSTITUTE_APPROVAL_REQUIRED", "SUBSTITUTE_REQUIRES_APPROVAL", Instant.parse("2026-05-20T00:00:00Z"));
    lineRepository.save(line);

    DraftQuote recalculated = service.recalculate(quote);

    assertThat(recalculated.getStatus()).isEqualTo("SUBSTITUTION_REVIEW");
    assertThatThrownBy(() -> service.requireReadyForApproval(tenantId, recalculated))
        .isInstanceOf(QuoteLifecycleViolation.class)
        .hasMessageContaining("pending substitute");
  }

  @Test
  void noBlockingIssuesAndApprovedSubstituteAllowsReadyState() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DraftQuote quote = quoteRepository.save(new DraftQuote(tenantId, "DQ-2", "API", null, null, null, null, "NEEDS_REVIEW", "NEEDS_REVIEW", true, "USD", UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-05-20T00:00:00Z")));
    DraftQuoteLine line = lineRepository.save(new DraftQuoteLine(tenantId, quote.getId(), 1, "Brake pads", "BRK", "BRK", UUID.randomUUID(), "Brake pads", BigDecimal.ONE, "EA", null, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ONE, "[]", "NEEDS_REVIEW", "NEEDS_REVIEW", Instant.parse("2026-05-20T00:00:00Z")));
    line.setSubstituteDecision("SUBSTITUTE_APPROVED", UUID.randomUUID(), "VEHICLE_CONTEXT_MATCH", UUID.randomUUID(), "ok", Instant.parse("2026-05-20T00:00:00Z"));
    lineRepository.save(line);

    DraftQuote recalculated = service.recalculate(quote);

    assertThat(recalculated.getStatus()).isEqualTo("READY_FOR_APPROVAL");
  }
}
