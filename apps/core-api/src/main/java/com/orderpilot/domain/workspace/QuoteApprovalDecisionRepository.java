package com.orderpilot.domain.workspace;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuoteApprovalDecisionRepository extends JpaRepository<QuoteApprovalDecision, UUID> {
  List<QuoteApprovalDecision> findByTenantIdAndDraftQuoteIdOrderByDecidedAtDesc(UUID tenantId, UUID draftQuoteId);
}
