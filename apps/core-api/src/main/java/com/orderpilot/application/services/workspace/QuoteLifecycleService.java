package com.orderpilot.application.services.workspace;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.workspace.*;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuoteLifecycleService {
  private static final Set<String> PENDING_SUBSTITUTE_STATES = Set.of("SUBSTITUTE_SUGGESTED", "SUBSTITUTE_APPROVAL_REQUIRED");

  private final DraftQuoteRepository quoteRepository;
  private final DraftQuoteLineRepository lineRepository;
  private final QuoteValidationIssueRepository issueRepository;
  private final Clock clock;

  public QuoteLifecycleService(DraftQuoteRepository quoteRepository, DraftQuoteLineRepository lineRepository, QuoteValidationIssueRepository issueRepository, Clock clock) {
    this.quoteRepository = quoteRepository;
    this.lineRepository = lineRepository;
    this.issueRepository = issueRepository;
    this.clock = clock;
  }

  @Transactional
  public DraftQuote recalculate(DraftQuote quote) {
    UUID tenantId = TenantContext.requireTenantId();
    List<DraftQuoteLine> lines = lineRepository.findByTenantIdAndDraftQuoteId(tenantId, quote.getId());
    long blockingIssues = issueRepository.countByTenantIdAndDraftQuoteIdAndBlockingTrueAndStatus(tenantId, quote.getId(), "OPEN");
    boolean pendingSubstitutes = lines.stream().anyMatch(line -> PENDING_SUBSTITUTE_STATES.contains(line.getSubstituteDecisionStatus()));
    boolean rejectedOrBlocked = lines.stream().anyMatch(line -> Set.of("SUBSTITUTE_REJECTED", "SUBSTITUTE_BLOCKED", "NO_SAFE_SUBSTITUTE_FOUND").contains(line.getSubstituteDecisionStatus()));

    if (pendingSubstitutes) {
      quote.transition("SUBSTITUTION_REVIEW", "NEEDS_REVIEW", true, null, clock.instant());
    } else if (blockingIssues == 0 && !rejectedOrBlocked) {
      quote.transition("READY_FOR_APPROVAL", "VALIDATED", true, null, clock.instant());
    } else {
      quote.transition("NEEDS_REVIEW", "NEEDS_REVIEW", true, null, clock.instant());
    }
    return quoteRepository.save(quote);
  }

  @Transactional(readOnly = true)
  public void requireReadyForApproval(UUID tenantId, DraftQuote quote) {
    List<DraftQuoteLine> lines = lineRepository.findByTenantIdAndDraftQuoteId(tenantId, quote.getId());
    if (issueRepository.countByTenantIdAndDraftQuoteIdAndBlockingTrueAndStatus(tenantId, quote.getId(), "OPEN") > 0) {
      throw new QuoteLifecycleViolation("Quote has unresolved blocking validation issues");
    }
    boolean pendingSubstitutes = lines.stream().anyMatch(line -> PENDING_SUBSTITUTE_STATES.contains(line.getSubstituteDecisionStatus()));
    if (pendingSubstitutes) {
      throw new QuoteLifecycleViolation("Quote has pending substitute decisions");
    }
    boolean unsafeSubstitutes = lines.stream().anyMatch(line -> Set.of("SUBSTITUTE_REJECTED", "SUBSTITUTE_BLOCKED", "NO_SAFE_SUBSTITUTE_FOUND").contains(line.getSubstituteDecisionStatus()));
    if (unsafeSubstitutes) {
      throw new QuoteLifecycleViolation("Quote has unresolved rejected or blocked substitute decisions");
    }
  }
}
