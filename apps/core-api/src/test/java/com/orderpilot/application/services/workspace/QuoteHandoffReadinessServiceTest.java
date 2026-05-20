package com.orderpilot.application.services.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
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
@Import({QuoteHandoffReadinessService.class, AuditEventService.class, CoreConfiguration.class})
class QuoteHandoffReadinessServiceTest {
  private static final Instant NOW = Instant.parse("2026-05-20T00:00:00Z");

  @Autowired private QuoteHandoffReadinessService service;
  @Autowired private DraftQuoteRepository quoteRepository;
  @Autowired private DraftQuoteLineRepository lineRepository;
  @Autowired private QuoteValidationIssueRepository issueRepository;
  @Autowired private ProductRepository productRepository;
  @Autowired private AuditEventRepository auditEventRepository;

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test
  void approvedQuoteCanBecomeHandoffReady() {
    Scenario s = approvedScenario("APPROVED", "NO_SUBSTITUTE_REQUIRED");

    var response = service.check(s.quoteId(), UUID.randomUUID());

    assertThat(response.handoffReadinessStatus()).isEqualTo("READY_FOR_HANDOFF");
    assertThat(response.blockingIssues()).isEmpty();
    assertThat(response.allowedActions()).contains("PREPARE_HANDOFF");
    assertThat(auditEventRepository.findByTenantIdOrderByOccurredAtDesc(TenantContext.requireTenantId())).extracting("action").contains("QUOTE_HANDOFF_READINESS_CHECKED");
  }

  @Test
  void nonApprovedQuoteCannotPrepareHandoff() {
    Scenario s = approvedScenario("READY_FOR_APPROVAL", "NO_SUBSTITUTE_REQUIRED");

    var response = service.check(s.quoteId(), null);

    assertThat(response.handoffReadinessStatus()).isEqualTo("HANDOFF_BLOCKED");
    assertThat(response.blockingIssues()).anyMatch(issue -> issue.contains("internally APPROVED"));
  }

  @Test
  void pendingSubstituteDecisionBlocksHandoff() {
    Scenario s = approvedScenario("APPROVED", "SUBSTITUTE_APPROVAL_REQUIRED");

    var response = service.check(s.quoteId(), null);

    assertThat(response.handoffReadinessStatus()).isEqualTo("HANDOFF_BLOCKED");
    assertThat(response.blockingIssues()).anyMatch(issue -> issue.contains("pending substitute"));
  }

  @Test
  void unresolvedBlockingValidationIssueBlocksHandoff() {
    Scenario s = approvedScenario("APPROVED", "NO_SUBSTITUTE_REQUIRED");
    issueRepository.save(new QuoteValidationIssue(TenantContext.requireTenantId(), s.quoteId(), s.lineId(), "PRICE_NOT_RESOLVED", "ERROR", true, "Price missing", "{}", NOW));

    var response = service.check(s.quoteId(), null);

    assertThat(response.handoffReadinessStatus()).isEqualTo("HANDOFF_BLOCKED");
    assertThat(response.blockingIssues()).anyMatch(issue -> issue.contains("blocking validation"));
  }

  private Scenario approvedScenario(String quoteStatus, String substituteStatus) {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product product = productRepository.save(new Product(tenantId, "TOY-CAM-2018-BPAD-OE", "Original brake pads", null, "Brake", null, null, "EA", "ACTIVE", null, "USD", NOW));
    DraftQuote quote = quoteRepository.save(new DraftQuote(tenantId, "DQ-11E", "API", null, null, UUID.randomUUID(), "Acme", quoteStatus, "VALIDATED", false, "USD", UUID.randomUUID(), UUID.randomUUID(), NOW));
    DraftQuoteLine line = new DraftQuoteLine(tenantId, quote.getId(), null, product.getId(), null, 1, "Brake pads", BigDecimal.TEN, "EA", new BigDecimal("100.00"), BigDecimal.ZERO, null, "DRAFT", "VALIDATED", NOW);
    if (!"NO_SUBSTITUTE_REQUIRED".equals(substituteStatus)) {
      line.markSubstituteSuggested(substituteStatus, "SUBSTITUTE_REQUIRES_APPROVAL", NOW);
    }
    line = lineRepository.save(line);
    return new Scenario(quote.getId(), line.getId());
  }

  private record Scenario(UUID quoteId, UUID lineId) {}
}
